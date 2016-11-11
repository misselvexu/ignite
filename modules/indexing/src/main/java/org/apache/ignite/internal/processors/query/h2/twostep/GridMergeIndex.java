/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.twostep;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.cache.CacheException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.internal.GridKernalContext;
import org.h2.engine.Constants;
import org.h2.engine.Session;
import org.h2.index.BaseIndex;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.IndexColumn;
import org.h2.table.TableFilter;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentHashMap8;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_SQL_MERGE_TABLE_MAX_SIZE;
import static org.apache.ignite.IgniteSystemProperties.getInteger;

/**
 * Merge index.
 */
public abstract class GridMergeIndex extends BaseIndex {
    /** */
    private static final int MAX_FETCH_SIZE = getInteger(IGNITE_SQL_MERGE_TABLE_MAX_SIZE, 10_000);

    /** All rows number. */
    private final AtomicInteger expRowsCnt = new AtomicInteger(0);

    /** Remaining rows per source node ID. */
    private final ConcurrentMap<UUID, Counter> remainingRows = new ConcurrentHashMap8<>();

    /** */
    private final AtomicBoolean lastSubmitted = new AtomicBoolean();

    /**
     * Will be r/w from query execution thread only, does not need to be threadsafe.
     */
    private ArrayList<Row> fetched = new ArrayList<>();

    /** */
    private int fetchedCnt;

    /** */
    private final GridKernalContext ctx;

    /**
     * @param ctx Context.
     * @param tbl Table.
     * @param name Index name.
     * @param type Type.
     * @param cols Columns.
     */
    public GridMergeIndex(GridKernalContext ctx,
        GridMergeTable tbl,
        String name,
        IndexType type,
        IndexColumn[] cols) {
        this.ctx = ctx;

        initBaseIndex(tbl, 0, name, cols, type);
    }

    /**
     * @param ctx Context.
     */
    protected GridMergeIndex(GridKernalContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @return Return source nodes for this merge index.
     */
    public Set<UUID> sources() {
        return remainingRows.keySet();
    }

    /**
     * Fails index if any source node is left.
     */
    protected final void checkSourceNodesAlive() {
        for (UUID nodeId : sources()) {
            if (!ctx.discovery().alive(nodeId)) {
                fail(nodeId);

                return;
            }
        }
    }

    /**
     * @param nodeId Node ID.
     * @return {@code true} If this index needs data from the given source node.
     */
    public boolean hasSource(UUID nodeId) {
        return remainingRows.containsKey(nodeId);
    }

    /** {@inheritDoc} */
    @Override public long getRowCount(Session ses) {
        return expRowsCnt.get();
    }

    /** {@inheritDoc} */
    @Override public long getRowCountApproximation() {
        return getRowCount(null);
    }

    /**
     * @param nodeId Node ID.
     */
    public void addSource(UUID nodeId) {
        if (remainingRows.put(nodeId, new Counter()) != null)
            throw new IllegalStateException();
    }

    /**
     * @param e Error.
     */
    public void fail(final CacheException e) {
        for (UUID nodeId0 : remainingRows.keySet()) {
            addPage0(new GridResultPage(null, nodeId0, null) {
                @Override public boolean isFail() {
                    return true;
                }

                @Override public void fetchNextPage() {
                    throw e;
                }
            });
        }
    }

    /**
     * @param nodeId Node ID.
     */
    public void fail(UUID nodeId) {
        addPage0(new GridResultPage(null, nodeId, null) {
            @Override public boolean isFail() {
                return true;
            }
        });
    }

    /**
     * @param page Page.
     */
    public final void addPage(GridResultPage page) {
        int pageRowsCnt = page.rowsInPage();

        Counter cnt = remainingRows.get(page.source());

        // RemainingRowsCount should be updated before page adding to avoid race
        // in GridMergeIndexUnsorted cursor iterator
        int remainingRowsCount;

        int allRows = page.response().allRows();

        if (allRows != -1) { // Only the first page contains allRows count and is allowed to init counter.
            assert cnt.state == State.UNINITIALIZED : "Counter is already initialized.";

            remainingRowsCount = cnt.addAndGet(allRows - pageRowsCnt);

            expRowsCnt.addAndGet(allRows);

            // Add page before setting initialized flag to avoid race condition with adding last page
            if (pageRowsCnt > 0)
                addPage0(page);

            // We need this separate flag to handle case when the first source contains only one page
            // and it will signal that all remaining counters are zero and fetch is finished.
            cnt.state = State.INITIALIZED;
        }
        else {
            remainingRowsCount = cnt.addAndGet(-pageRowsCnt);

            if (pageRowsCnt > 0)
                addPage0(page);
        }

        if (remainingRowsCount == 0) { // Result can be negative in case of race between messages, it is ok.
            if (cnt.state == State.UNINITIALIZED)
                return;

            // Guarantee that finished state possible only if counter is zero and all pages was added
            cnt.state = State.FINISHED;

            for (Counter c : remainingRows.values()) { // Check all the sources.
                if (c.state != State.FINISHED)
                    return;
            }

            if (lastSubmitted.compareAndSet(false, true)) {
                // Add page-marker that last page was added
                addPage0(new GridResultPage(null, page.source(), null) {
                    @Override public boolean isLast() {
                        return true;
                    }
                });
            }
        }
    }

    /**
     * @param page Page.
     */
    protected abstract void addPage0(GridResultPage page);

    /**
     * @param page Page.
     */
    protected void fetchNextPage(GridResultPage page) {
        if (remainingRows.get(page.source()).get() != 0)
            page.fetchNextPage();
    }

    /** {@inheritDoc} */
    @Override public Cursor find(Session ses, SearchRow first, SearchRow last) {
        if (fetched == null)
            throw new IgniteException("Fetched result set was too large.");

        if (fetchedAll())
            return findAllFetched(fetched, first, last);

        return findInStream(first, last);
    }

    /**
     * @return {@code true} If we have fetched all the remote rows.
     */
    public boolean fetchedAll() {
        return fetchedCnt == expRowsCnt.get();
    }

    /**
     * @param first First row.
     * @param last Last row.
     * @return Cursor. Usually it must be {@link FetchingCursor} instance.
     */
    protected abstract Cursor findInStream(@Nullable SearchRow first, @Nullable SearchRow last);

    /**
     * @param fetched Fetched rows.
     * @param first First row.
     * @param last Last row.
     * @return Cursor.
     */
    protected abstract Cursor findAllFetched(List<Row> fetched, @Nullable SearchRow first, @Nullable SearchRow last);

    /** {@inheritDoc} */
    @Override public void checkRename() {
        throw DbException.getUnsupportedException("rename");
    }

    /** {@inheritDoc} */
    @Override public void close(Session ses) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void add(Session ses, Row row) {
        throw DbException.getUnsupportedException("add");
    }

    /** {@inheritDoc} */
    @Override public void remove(Session ses, Row row) {
        throw DbException.getUnsupportedException("remove row");
    }

    /** {@inheritDoc} */
    @Override public double getCost(Session ses, int[] masks, TableFilter filter, SortOrder sortOrder) {
        return getRowCountApproximation() + Constants.COST_ROW_OFFSET;
    }

    /** {@inheritDoc} */
    @Override public void remove(Session ses) {
        throw DbException.getUnsupportedException("remove index");
    }

    /** {@inheritDoc} */
    @Override public void truncate(Session ses) {
        throw DbException.getUnsupportedException("truncate");
    }

    /** {@inheritDoc} */
    @Override public boolean canGetFirstOrLast() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public Cursor findFirstOrLast(Session ses, boolean first) {
        throw DbException.getUnsupportedException("findFirstOrLast");
    }

    /** {@inheritDoc} */
    @Override public boolean needRebuild() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public long getDiskSpaceUsed() {
        return 0;
    }

    /**
     * Cursor over iterator.
     */
    protected class IteratorCursor implements Cursor {
        /** */
        protected Iterator<Row> iter;

        /** */
        protected Row cur;

        /**
         * @param iter Iterator.
         */
        public IteratorCursor(Iterator<Row> iter) {
            assert iter != null;

            this.iter = iter;
        }

        /** {@inheritDoc} */
        @Override public Row get() {
            return cur;
        }

        /** {@inheritDoc} */
        @Override public SearchRow getSearchRow() {
            return get();
        }

        /** {@inheritDoc} */
        @Override public boolean next() {
            cur = iter.hasNext() ? iter.next() : null;

            return cur != null;
        }

        /** {@inheritDoc} */
        @Override public boolean previous() {
            throw DbException.getUnsupportedException("previous");
        }
    }

    /**
     * Fetching cursor.
     */
    protected class FetchingCursor extends IteratorCursor {
        /** */
        private Iterator<Row> stream;

        /**
         * @param stream Iterator.
         */
        public FetchingCursor(Iterator<Row> stream) {
            super(new FetchedIterator());

            assert stream != null;

            this.stream = stream;
        }

        /** {@inheritDoc} */
        @Override public boolean next() {
            if (super.next()) {
                assert cur != null;

                if (iter == stream && fetched != null) { // Cache fetched rows for reuse.
                    if (fetched.size() == MAX_FETCH_SIZE)
                        fetched = null; // Throw away fetched result if it is too large.
                    else
                        fetched.add(cur);
                }

                fetchedCnt++;

                return true;
            }

            if (iter == stream) // We've fetched the stream.
                return false;

            iter = stream; // Switch from cached to stream.

            return next();
        }
    }

    /**
     * List iterator without {@link ConcurrentModificationException}.
     */
    private class FetchedIterator implements Iterator<Row> {
        /** */
        private int idx;

        /** {@inheritDoc} */
        @Override public boolean hasNext() {
            return fetched != null && idx < fetched.size();
        }

        /** {@inheritDoc} */
        @Override public Row next() {
            return fetched.get(idx++);
        }

        /** {@inheritDoc} */
        @Override public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    /** */
    enum State {
        UNINITIALIZED, INITIALIZED, FINISHED
    }

    /**
     * Counter with initialization flag.
     */
    private static class Counter extends AtomicInteger {
        /** */
        volatile State state = State.UNINITIALIZED;
    }
}