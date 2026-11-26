package com.samwan.kibetipay.shared.paging;

import com.samwan.kibetipay.shared.error.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;

/**
 * One page of a newest-first list. {@code nextCursor} is {@code null} on the last page.
 */
public record Page<T>(List<T> items, String nextCursor) {

    public static final int MAX_LIMIT = 100;

    /**
     * Builds a page from a query that fetched {@code limit + 1} rows: the extra row only proves
     * there is more, and the cursor points at the last row returned.
     */
    public static <T> Page<T> fromOverfetch(List<T> rows, int limit, Function<T, Cursor> position) {
        if (rows.size() <= limit) {
            return new Page<>(List.copyOf(rows), null);
        }
        List<T> items = List.copyOf(rows.subList(0, limit));
        return new Page<>(items, position.apply(items.getLast()).encode());
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), nextCursor);
    }

    public static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    /**
     * A newest-first history query: optional date range (inclusive dates in the business time
     * zone), optional cursor, and a page size.
     */
    public record Query(Instant from, Instant toExclusive, Cursor before, int limit) {

        public Query {
            limit = clampLimit(limit);
        }

        public static Query of(LocalDate from, LocalDate to, String cursor, int limit, ZoneId zone) {
            if (from != null && to != null && to.isBefore(from)) {
                throw new DomainException.RuleViolation("invalid_range",
                        "'to' must not be before 'from'");
            }
            return new Query(from == null ? null : from.atStartOfDay(zone).toInstant(),
                    to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(),
                    Cursor.decode(cursor), limit);
        }
    }
}
