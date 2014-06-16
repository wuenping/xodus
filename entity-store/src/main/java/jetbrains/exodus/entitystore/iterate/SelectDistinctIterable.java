/**
 * Copyright 2010 - 2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.iterate;

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.core.dataStructures.hash.IntHashMap;
import jetbrains.exodus.core.dataStructures.hash.ObjectProcedure;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.tables.LinkValue;
import jetbrains.exodus.entitystore.tables.PropertyKey;
import jetbrains.exodus.entitystore.util.EntityIdSet;
import jetbrains.exodus.env.Cursor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings({"RawUseOfParameterizedType"})
public final class SelectDistinctIterable extends EntityIterableDecoratorBase {

    private final int linkId;

    static {
        registerType(getType(), new EntityIterableInstantiator() {
            @Override
            public EntityIterableBase instantiate(PersistentStoreTransaction txn, PersistentEntityStoreImpl store, Object[] parameters) {
                return new SelectDistinctIterable(store,
                        (EntityIterableBase) parameters[1], Integer.valueOf((String) parameters[0]));
            }
        });
    }

    public SelectDistinctIterable(@NotNull final PersistentEntityStoreImpl store,
                                  @NotNull final EntityIterableBase source,
                                  final int linkId) {
        super(store, source);
        this.linkId = linkId;
    }

    public static EntityIterableType getType() {
        return EntityIterableType.SELECT_DISTINCT;
    }

    @Override
    public boolean isEmpty() {
        return source.isEmpty();
    }

    @Override
    @NotNull
    public EntityIteratorBase getIteratorImpl(@NotNull final PersistentStoreTransaction txn) {
        return new EntityIteratorFixingDecorator(this, new SelectDistinctIterator(txn));
    }

    @Override
    @NotNull
    protected EntityIterableHandle getHandleImpl() {
        return new EntityIterableHandleBase(getStore(), SelectDistinctIterable.getType()) {
            @Nullable
            private final int[] linkIds = mergeLinkIds(new int[]{linkId}, getDecorated().getHandle().getLinkIds());

            @Nullable
            @Override
            public int[] getLinkIds() {
                return linkIds;
            }

            @Override
            public void getStringHandle(@NotNull final StringBuilder builder) {
                super.getStringHandle(builder);
                builder.append('-');
                source.getHandle().getStringHandle(builder);
                builder.append('-');
                builder.append(linkId);
            }

            @Override
            public boolean isMatchedEntityAdded(@NotNull final EntityId added) {
                return source.getHandle().isMatchedEntityAdded(added);
            }

            @Override
            public boolean isMatchedEntityDeleted(@NotNull final EntityId deleted) {
                return source.getHandle().isMatchedEntityDeleted(deleted);
            }

            @Override
            public boolean isMatchedLinkAdded(@NotNull final EntityId source, @NotNull final EntityId target, final int linkId) {
                return linkId == SelectDistinctIterable.this.linkId || getDecorated().getHandle().isMatchedLinkAdded(source, target, linkId);
            }

            @Override
            public boolean isMatchedLinkDeleted(@NotNull final EntityId source, @NotNull final EntityId target, final int linkId) {
                return linkId == SelectDistinctIterable.this.linkId || getDecorated().getHandle().isMatchedLinkDeleted(source, target, linkId);
            }

            @Override
            public boolean isMatchedPropertyChanged(final int typeId,
                                                    final int propertyId,
                                                    @Nullable final Comparable oldValue,
                                                    @Nullable final Comparable newValue) {
                return source.getHandle().isMatchedPropertyChanged(typeId, propertyId, oldValue, newValue);
            }
        };
    }

    private class SelectDistinctIterator extends EntityIteratorBase {

        @NotNull
        private final EntityIteratorBase sourceIt;
        @NotNull
        private final IntHashMap<Cursor> usedCursors;
        private final EntityIdSet usedIds;
        private EntityId nextId;
        private boolean nullProcessed;
        @NotNull
        private final PersistentStoreTransaction txn;

        private SelectDistinctIterator(@NotNull final PersistentStoreTransaction txn) {
            super(SelectDistinctIterable.this);
            sourceIt = (EntityIteratorBase) source.iterator();
            usedCursors = new IntHashMap<Cursor>();
            usedIds = new EntityIdSet();
            nextId = null;
            nullProcessed = false;
            this.txn = txn;
        }

        @SuppressWarnings({"ObjectAllocationInLoop"})
        @Override
        protected boolean hasNextImpl() {
            if (linkId < 0) {
                return !nullProcessed && sourceIt.hasNext();
            }
            while (sourceIt.hasNext()) {
                final EntityId nextSourceId = sourceIt.nextId();
                if (nextSourceId == null) {
                    continue;
                }
                final int typeId = nextSourceId.getTypeId();
                Cursor cursor = usedCursors.get(typeId);
                if (cursor == null) {
                    cursor = getStore().getLinksFirstIndexCursor(txn, typeId);
                    usedCursors.put(typeId, cursor);
                }
                final ByteIterable keyEntry = PropertyKey.propertyKeyToEntry(new PropertyKey(nextSourceId.getLocalId(), linkId));
                final ByteIterable value = cursor.getSearchKey(keyEntry);
                if (value == null) {
                    if (!nullProcessed) {
                        nextId = null;
                        return true;
                    }
                } else {
                    final LinkValue linkValue = LinkValue.entryToLinkValue(value);
                    final EntityId nextId = linkValue.getEntityId();
                    if (!usedIds.contains(nextId)) {
                        this.nextId = nextId;
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        @Nullable
        public EntityId nextIdImpl() {
            final EntityId nextId = this.nextId;
            if (nextId == null) {
                nullProcessed = true;
            } else {
                usedIds.add(nextId);
            }
            return nextId;
        }

        @Override
        public boolean dispose() {
            sourceIt.disposeIfShouldBe();
            return super.dispose() && usedCursors.forEachValue(new ObjectProcedure<Cursor>() {
                @Override
                public boolean execute(final Cursor object) {
                    object.close();
                    return true;
                }
            });
        }
    }
}