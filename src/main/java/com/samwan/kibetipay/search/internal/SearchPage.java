package com.samwan.kibetipay.search.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import java.util.List;
import java.util.Map;

/**
 * One page of search results plus facets computed over the whole match set, not just this page.
 *
 * @param total  exact hit count ({@code track_total_hits=true}; the default stops counting at 10,000)
 * @param facets aggregation results, shaped per index
 */
record SearchPage<T, F>(long total, int page, int size, List<Hit<T>> hits, F facets) {

    /** @param highlights field -> fragments with the matched terms wrapped in {@code <em>} */
    record Hit<T>(T item, Double score, Map<String, List<String>> highlights) {}

    /** {@code from + size} is capped by {@code index.max_result_window} (10,000); use search_after beyond that. */
    static final int MAX_WINDOW = 10_000;

    record Paging(int page, int size) {
        Paging {
            page = Math.max(page, 0);
            size = Math.clamp(size, 1, 100);
            if ((long) (page + 1) * size > MAX_WINDOW) {
                throw new DomainException.RuleViolation("page_too_deep",
                        "Pages beyond " + MAX_WINDOW + " results are not available; narrow the filters");
            }
        }

        int from() {
            return page * size;
        }
    }
}
