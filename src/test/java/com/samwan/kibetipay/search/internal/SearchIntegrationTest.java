package com.samwan.kibetipay.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.samwan.kibetipay.lending.LendingEvents.LoanDisbursed;
import com.samwan.kibetipay.lending.LendingEvents.RepaymentReceived;
import com.samwan.kibetipay.search.internal.SearchIndices.Kind;
import com.samwan.kibetipay.search.internal.SearchPage.Paging;
import com.samwan.kibetipay.transfer.TransferEvents.TransferCompleted;
import com.samwan.kibetipay.transfer.TransferEvents.TransferHeldForReview;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real OpenSearch, no Spring context: wires the module's classes by hand so each test exercises the actual
 * mappings, analyzers, Painless scripts and aggregations. Skipped (not failed) when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class SearchIntegrationTest {

    @Container
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>("opensearchproject/opensearch:3.9.0");

    static OpenSearchTransport transport;
    static OpenSearchClient client;

    SearchIndices indices;
    TransferIndexer transferIndexer;
    LoanIndexer loanIndexer;
    TransferSearch transferSearch;
    LoanSearch loanSearch;

    @BeforeAll
    static void connect() throws Exception {
        transport = new OpenSearchConfig().openSearchTransport(
                new SearchProperties(true, OPENSEARCH.getHttpHostAddress(), null, null, "unused"));
        client = new OpenSearchClient(transport);
    }

    @AfterAll
    static void close() throws IOException {
        transport.close();
    }

    /** A fresh prefix per test = fresh indices, so tests never see each other's documents. */
    @BeforeEach
    void wire() {
        indices = new SearchIndices(client, props("t" + UUID.randomUUID().toString().substring(0, 8)));
        var writer = new DocumentWriter(client, indices);
        transferIndexer = new TransferIndexer(writer);
        loanIndexer = new LoanIndexer(writer);
        transferSearch = new TransferSearch(client, indices, "KES");
        loanSearch = new LoanSearch(client, indices, "KES");
    }

    @Test
    void firstWriteCreatesTheVersionedIndexBehindTheAlias() throws IOException {
        // No ensureAll(): simulates the cluster being down at startup.
        transferIndexer.on(completed("alice", "bob", 1_200_00, "lunch at Java House"));

        var aliases = client.indices().getAlias(a -> a.name(indices.alias(Kind.TRANSFERS)));
        assertThat(aliases.result()).containsOnlyKeys(indices.concreteIndex(Kind.TRANSFERS));
    }

    @Test
    void fullTextIsStemmedFuzzyAndPrefixAware() throws IOException {
        transferIndexer.on(completed("alice", "bob", 1_200_00, "Lunch at Java House"));
        transferIndexer.on(completed("alice", "carol", 500_00, "school fees"));
        refresh();

        assertThat(texts("lunches")).containsExactly("Lunch at Java House");  // stemming
        assertThat(texts("lnch")).containsExactly("Lunch at Java House");     // fuzziness AUTO
        assertThat(texts("jav")).containsExactly("Lunch at Java House");      // edge n-gram prefix
        assertThat(texts("carol")).containsExactly("school fees");            // party id

        var hit = transferSearch.search(criteria("java"), null).hits().getFirst();
        assertThat(hit.highlights().get("note")).anySatisfy(f -> assertThat(f).contains("<em>Java</em>"));
    }

    @Test
    void lateHeldEventDoesNotRegressACompletedTransfer() throws IOException {
        var done = completed("alice", "bob", 30_000_00, "rent");
        transferIndexer.on(done);
        transferIndexer.on(new TransferHeldForReview(done.transferId(), "alice", 35,
                done.completedAt().minusSeconds(60)));
        refresh();

        var view = transferSearch.search(criteria(null), null).hits().getFirst().item();
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.updatedAt()).isEqualTo(done.completedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void usersOnlySeeTheirOwnTransfersAndFacetsCountEverything() throws IOException {
        transferIndexer.on(completed("alice", "bob", 100_00, "airtime"));
        transferIndexer.on(completed("bob", "alice", 200_00, "refund"));
        transferIndexer.on(completed("carol", "dave", 300_00, "airtime"));
        transferIndexer.on(new TransferHeldForReview(UUID.randomUUID(), "carol", 40, Instant.now()));
        refresh();

        var alice = transferSearch.search(criteria(null), "alice");
        assertThat(alice.total()).isEqualTo(2);

        var admin = transferSearch.search(criteria(null), null);
        assertThat(admin.total()).isEqualTo(4);
        assertThat(admin.facets().byStatus()).containsEntry("COMPLETED", 3L).containsEntry("HELD_FOR_REVIEW", 1L);
        assertThat(admin.facets().amount().count()).isEqualTo(3);
    }

    @Test
    void redeliveredRepaymentIsCountedOnceEvenBeforeDisbursementIsIndexed() throws IOException {
        UUID loan = UUID.randomUUID();
        var repayment = new RepaymentReceived(loan, UUID.randomUUID(), "alice", "WALLET", 850_00, Instant.now());

        loanIndexer.on(repayment);   // arrives first (async listeners race)
        loanIndexer.on(repayment);   // outbox redelivery
        loanIndexer.on(new LoanDisbursed(loan, "alice", UUID.randomUUID(), 3_000_00, "KES",
                Instant.now().minusSeconds(3600)));
        refresh();

        var page = loanSearch.search(new LoanSearch.Criteria("alice", null, null, null, new Paging(0, 10)));
        var view = page.hits().getFirst().item();
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.repaymentCount()).isEqualTo(1);
        assertThat(page.facets()).singleElement().satisfies(t -> {
            assertThat(t.status()).isEqualTo("ACTIVE");
            assertThat(t.loans()).isEqualTo(1);
        });
    }

    // ---------------------------------------------------------------- helpers

    private java.util.List<String> texts(String q) {
        return transferSearch.search(criteria(q), null).hits().stream().map(h -> h.item().note()).toList();
    }

    private static TransferSearch.Criteria criteria(String q) {
        return new TransferSearch.Criteria(q, null, null, null, null, null, null, new Paging(0, 20));
    }

    private static TransferCompleted completed(String from, String to, long minor, String note) {
        return new TransferCompleted(UUID.randomUUID(), from, to, minor, "KES", note, 15, Instant.now());
    }

    private void refresh() throws IOException {
        // Near-real-time: documents become searchable on refresh (every 1s by default). Tests force it.
        client.indices().refresh(r -> r.index(indices.alias(Kind.TRANSFERS), indices.alias(Kind.LOANS))
                .ignoreUnavailable(true));
    }

    private static SearchProperties props(String prefix) {
        return new SearchProperties(true, "http://unused", null, null, prefix);
    }
}
