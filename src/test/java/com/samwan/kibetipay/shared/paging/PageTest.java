package com.samwan.kibetipay.shared.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.samwan.kibetipay.shared.error.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void cursorRoundTripsWithMicrosecondPrecision() {
        var cursor = new Cursor(Instant.parse("2026-11-26T08:15:30.123456Z"), UUID.randomUUID());
        assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void blankCursorMeansNewestAndGarbageIsRejected() {
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode(" ")).isNull();
        assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
                .isInstanceOf(DomainException.RuleViolation.class);
    }

    @Test
    void overfetchedRowSignalsAnotherPage() {
        record Row(Instant at, UUID id) {}
        var rows = List.of(new Row(Instant.parse("2026-11-03T00:00:00Z"), UUID.randomUUID()),
                new Row(Instant.parse("2026-11-02T00:00:00Z"), UUID.randomUUID()),
                new Row(Instant.parse("2026-11-01T00:00:00Z"), UUID.randomUUID()));

        var page = Page.fromOverfetch(rows, 2, r -> new Cursor(r.at(), r.id()));
        assertThat(page.items()).containsExactly(rows.get(0), rows.get(1));
        assertThat(Cursor.decode(page.nextCursor())).isEqualTo(new Cursor(rows.get(1).at(), rows.get(1).id()));

        var last = Page.fromOverfetch(rows, 3, r -> new Cursor(r.at(), r.id()));
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void dateRangeIsInclusiveInTheBusinessZone() {
        var nairobi = ZoneId.of("Africa/Nairobi");
        var q = Page.Query.of(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null, 500, nairobi);
        assertThat(q.from()).isEqualTo(Instant.parse("2026-10-31T21:00:00Z"));
        assertThat(q.toExclusive()).isEqualTo(Instant.parse("2026-11-30T21:00:00Z"));
        assertThat(q.limit()).isEqualTo(Page.MAX_LIMIT);
        assertThatThrownBy(() -> Page.Query.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1), null, 10, nairobi))
                .isInstanceOf(DomainException.RuleViolation.class);
    }
}
