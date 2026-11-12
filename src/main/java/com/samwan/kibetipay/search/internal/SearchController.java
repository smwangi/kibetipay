package com.samwan.kibetipay.search.internal;

import com.samwan.kibetipay.search.internal.SearchPage.Paging;
import com.samwan.kibetipay.shared.security.CurrentUser;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search endpoints. Admin routes see everything; {@code /api/search/transfers} is scoped to the caller by a
 * filter clause, which is how per-tenant / per-user isolation is normally done in a shared index.
 */
@RestController
@ConditionalOnBooleanProperty("app.search.enabled")
class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final TransferSearch transfers;
    private final LoanSearch loans;

    SearchController(TransferSearch transfers, LoanSearch loans) {
        this.transfers = transfers;
        this.loans = loans;
    }

    @GetMapping("/api/admin/search/transfers")
    SearchPage<TransferSearch.TransferView, TransferSearch.Facets> allTransfers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String party,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transfers.search(new TransferSearch.Criteria(q, status, party, minAmount, maxAmount, from, to,
                new Paging(page, size)), null);
    }

    @GetMapping("/api/search/transfers")
    SearchPage<TransferSearch.TransferView, TransferSearch.Facets> myTransfers(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transfers.search(new TransferSearch.Criteria(q, status, null, minAmount, maxAmount, from, to,
                new Paging(page, size)), CurrentUser.id());
    }

    @GetMapping("/api/admin/search/loans")
    SearchPage<LoanSearch.LoanView, List<LoanSearch.StatusTotals>> allLoans(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String borrower,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return loans.search(new LoanSearch.Criteria(q, status, agentId, borrower, new Paging(page, size)));
    }

    /** The cluster is a read model: when it is down, search degrades to 503 and nothing else breaks. */
    @ExceptionHandler({UncheckedIOException.class, OpenSearchException.class})
    ProblemDetail searchUnavailable(RuntimeException ex) {
        log.warn("Search unavailable: {}", ex.toString());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Search is temporarily unavailable");
        pd.setProperty("code", "search_unavailable");
        return pd;
    }
}
