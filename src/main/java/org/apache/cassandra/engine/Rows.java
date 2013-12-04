/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.engine;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Static utilities to work on Row objects.
 */
public abstract class Rows
{
    private Rows() {}

    public static void copyRow(Row r, Writer writer)
    {
        for (int i = 0; i < r.clusteringSize(); i++)
            writer.setClusteringColumn(i, r.getClusteringColumn(i));

        int pos = r.startPosition();
        int limit = r.endPosition();
        while (pos < limit)
        {
            Column c = r.columnForPosition(pos);
            for (int i = 0; i < r.size(c); i++)
            {
                writer.addCell(c, r.isTombstone(pos), r.key(pos), r.value(pos), r.timestamp(pos), r.ttl(pos), r.localDeletionTime(pos));
                ++pos;
            }
        }
        writer.done();
    }

    public static String toString(Atom atom)
    {
        return toString(atom, false);
    }

    public static String toString(Atom atom, boolean includeTimestamps)
    {
        if (atom == null)
            return "null";

        switch (atom.kind())
        {
            case ROW: return toString((Row)atom, includeTimestamps);
            case RANGE_TOMBSTONE: return toString((RangeTombstone)atom, includeTimestamps);
            case COLLECTION_TOMBSTONE: throw new UnsupportedOperationException(); // TODO
        }
        throw new AssertionError();
    }

    public static String toString(RangeTombstone rt, boolean includeTimestamps)
    {
        String str = String.format("[%s, %s]", toString(rt.metadata(), (Clusterable)rt.min()), toString(rt.metadata(), (Clusterable)rt.max()));
        if (includeTimestamps)
            str += "@" + rt.delTime().markedForDeleteAt();
        return str;
    }

    // TODO: not exactly at the right place
    public static String toString(Layout metadata, Clusterable c)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.clusteringSize(); i++)
        {
            if (i > 0) sb.append(":");
            sb.append(metadata.getClusteringType(i).getString((c.getClusteringColumn(i))));
        }
        return sb.toString();
    }

    public static String toString(Row row, boolean includeTimestamps)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(toString(row.metadata(), (Clusterable)row)).append("](");
        int pos = row.startPosition();
        while (pos < row.endPosition())
        {
            if (pos != row.startPosition())
                sb.append(", ");

            Column c = row.columnForPosition(pos);
            sb.append(c).append(":");
            if (c.isCollection())
            {
                int size = row.size(c);
                sb.append("{");
                for (int i = 0; i < size; i++)
                {
                    if (i > 0) sb.append(", ");
                    appendCell(sb, row, c, pos + i, includeTimestamps);
                }
                sb.append("}");
                pos += size;
            }
            else
            {
                appendCell(sb, row, c, pos++, includeTimestamps);
            }
        }
        return sb.append(")").toString();
    }

    private static StringBuilder appendCell(StringBuilder sb, Row r, Column c, int pos, boolean includeTimestamps)
    {
        if (r.key(pos) != null)
            sb.append(r.metadata().getKeyType(c).getString(r.key(pos))).append(":");
        sb.append(r.value(pos) == null ? "null" : r.metadata().getType(c).getString(r.value(pos)));
        if (includeTimestamps)
            sb.append("@").append(r.timestamp(pos));
        return sb;
    }

    // Merge multiple rows that are assumed to represent the same row (same clustering prefix).
    public static void merge(Layout layout, List<Row> rows, MergeHelper helper)
    {
        Row first = rows.get(0);
        for (int i = 0; i < layout.clusteringSize(); i++)
            helper.writer.setClusteringColumn(i, first.getClusteringColumn(i));

        helper.setRows(rows);
        boolean isMerger = helper.writer instanceof Merger;
        Merger.Resolution resolution = null;
        while (helper.advance())
        {
            Column nextColumn = helper.nextColumn();
            int count = helper.nextMergedCount();
            Row toMerge = null;
            int toMergePos = -1;
            for (int i = 0; i < count; i++)
            {
                Row row = helper.nextRow(i);
                int pos = helper.nextPos(i);
                if (row == null || (nextColumn.isCollection() && row.key(pos) == null))
                    continue;

                boolean overwite = toMerge == null || leftCellOverwrites(row, pos, toMerge, toMergePos, helper.now);

                // If we have a two row merger, sets the resolution
                if (isMerger)
                {
                    if (toMerge == null)
                        resolution = helper.isLeft(i) ? Merger.Resolution.ONLY_IN_LEFT : Merger.Resolution.ONLY_IN_RIGHT;
                    else
                        resolution = overwite ? Merger.Resolution.MERGED_FROM_RIGHT : Merger.Resolution.MERGED_FROM_LEFT;
                }

                if (overwite)
                {
                    toMerge = row;
                    toMergePos = pos;
                }
            }
            addCell(nextColumn, toMerge, toMergePos, helper.writer, resolution);
        }
        helper.writer.done();
    }

    private static void addCell(Column c, Row row, int pos, Writer writer, Merger.Resolution resolution)
    {
        if (resolution != null)
            ((Merger)writer).nextCellResolution(resolution);
        writer.addCell(c, row.isTombstone(pos), row.key(pos), row.value(pos), row.timestamp(pos), row.ttl(pos), row.localDeletionTime(pos));
    }

    // TODO: deal with counters
    private static boolean leftCellOverwrites(Row left, int leftPos, Row right, int rightPos, int now)
    {
        if (left.timestamp(leftPos) < right.timestamp(rightPos))
            return false;
        if (left.timestamp(leftPos) > right.timestamp(rightPos))
            return true;

        // Tombstones take precedence (if the other is also a tombstone, it doesn't matter).
        if (left.isDeleted(leftPos, now))
            return true;
        if (right.isDeleted(rightPos, now))
            return true;

        // Same timestamp, no tombstones, compare values
        return left.value(leftPos).compareTo(right.value(rightPos)) < 0;
    }

    /**
     * Generic interface for a row writer.
     */
    public interface Writer
    {
        public void setClusteringColumn(int i, ByteBuffer value);
        public void addCell(Column c, boolean isTombstone, ByteBuffer key, ByteBuffer value, long timestamp, int ttl, long deletionTime);
        public void done();
    }

    /**
     * Handle writing the result of merging 2 rows.
     * <p>
     * Unlike a simple Writer, a Merger knows where the resulting row comes from (the Resolution).
     */
    public interface Merger extends Writer
    {
        public enum Resolution { ONLY_IN_LEFT, ONLY_IN_RIGHT, MERGED_FROM_LEFT, MERGED_FROM_RIGHT }

        // Called before every addCell() call to indicate from which input row the newly added cell is from.
        public void nextCellResolution(Resolution resolution);
    }

    /**
     * Utility object to merge multiple rows.
     * <p>
     * We don't want to use a MergeIterator to merge multiple rows because we do that often
     * in the process of merging AtomIterators and we don't want to allocate iterators every
     * time (this object is reused over the course of merging multiple AtomIterator) and is
     * overall cheaper by being specialized.
     */
    public static class MergeHelper
    {
        public final Writer writer;
        public final int now;

        private List<Row> rows;
        private final Column currentColumns[];
        private final int[] positions;
        private final int[] limits;

        private int nextMergedCount;
        private Column nextColumn;
        private final int[] nextIndexes;
        private final int[] nextRemainings;

        public MergeHelper(Writer writer, int now, int maxRowCount)
        {
            this.writer = writer;
            this.now = now;

            this.currentColumns = new Column[maxRowCount];
            this.positions = new int[maxRowCount];
            this.limits = new int[maxRowCount];
            this.nextIndexes = new int[maxRowCount];
            this.nextRemainings = new int[maxRowCount];
        }

        @Override
        public String toString()
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Rows:\n");
            for (int i = 0; i < rows.size(); i++)
                sb.append("  ").append(i).append(": ").append(toString(rows.get(i))).append("\n");
            sb.append("\ncurrent columns:").append(Arrays.toString(currentColumns));
            sb.append("\npositions:      ").append(Arrays.toString(positions));
            sb.append("\nlimits:         ").append(Arrays.toString(limits));
            sb.append("\n");
            sb.append("\nnext count:      ").append(nextMergedCount);
            sb.append("\nnext indexes:    ").append(Arrays.toString(nextIndexes));
            sb.append("\nnext remainings: ").append(Arrays.toString(nextRemainings));
            return sb.append("\n").toString();
        }

        private void setRows(List<Row> rows)
        {
            this.rows = rows;

            nextMergedCount = 0;
            for (int i = 0; i < rows.size(); i++)
            {
                Row row = rows.get(i);
                positions[i] = row.startPosition();
                limits[i] = row.endPosition();
                currentColumns[i] = row.columnForPosition(positions[i]);
            }
        }

        // Find the next rows to merge together, setting nextMergedCount and
        // nextIndexes accordingly. Return false if we're done with the merge.
        private boolean advance()
        {
            boolean lastColumnDone = true;
            // First, advance on rows we've previously merged.
            for (int i = 0; i < nextMergedCount; i++)
            {
                // if we don't have remaining for that index, it means we're
                // still on a collection column but have no more element for
                // that specific row
                if (nextRemainings[i] == 0)
                    continue;

                int idx = nextIndexes[i];
                Row row = rows.get(idx);

                // nextColumn is the column we've just returned values for. If
                // it's the current column for the row considered, it means we've
                // just returned a value for it, advance the position.
                // Note: nextColumn can't be null since initially nextMergedCount == 0.
                if (nextColumn.equals(currentColumns[idx]))
                {
                    int newPos = ++positions[idx];
                    int newRemaining = --nextRemainings[i];
                    if (nextRemainings[idx] == 0)
                        currentColumns[idx] = newPos < limits[idx] ? row.columnForPosition(newPos) : null;
                    else
                        lastColumnDone = false;
                }
            }

            // If the last column is not done, we're all set.
            if (!lastColumnDone)
                return true;

            // Done with last column, find the next smallest column
            int nbRows = rows.size();
            nextColumn = null;
            for (int i = 0; i < nbRows; i++)
            {
                Column c = currentColumns[i];
                if (c != null && (nextColumn == null || nextColumn.compareTo(c) > 0))
                    nextColumn = c;
            }

            if (nextColumn == null)
                return false;

            // Lastly, collect the indexes/remainings of all row have this column
            nextMergedCount = 0;
            for (int i = 0; i < nbRows; i++)
            {
                Column c = currentColumns[i];
                if (c != null && c.equals(nextColumn))
                {
                    nextIndexes[nextMergedCount] = i;
                    nextRemainings[nextMergedCount] = rows.get(i).size(c);
                    ++nextMergedCount;
                }
            }
            return true;
        }

        private Column nextColumn()
        {
            return nextColumn;
        }

        private int nextMergedCount()
        {
            return nextMergedCount;
        }

        private Row nextRow(int i)
        {
            return nextRemainings[i] == 0 ? null : rows.get(nextIndexes[i]);
        }

        private int nextPos(int i)
        {
            return positions[nextIndexes[i]];
        }

        private boolean isLeft(int i)
        {
            // This should only be called if we have only 2 rows.
            return nextIndexes[i] == 0;
        }
    }
}
