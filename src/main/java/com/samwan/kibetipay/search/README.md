# Search

Search is an optional OpenSearch read model built from transfer and lending events. PostgreSQL remains authoritative for loans, balances, journals and idempotency. Enable it with `app.search.enabled=true`; configure `app.search.url`, credentials and `index-prefix` in `application.yml` or environment overrides. The dev profile enables it and the local compose file supplies OpenSearch and Dashboards without the security plugin; that setup is for local development.

## Data flow and APIs

`TransferIndexer` consumes completed, held and rejected transfer events; `LoanIndexer` consumes lending events. `DocumentWriter` applies scripted updates designed for duplicate and out-of-order delivery. `SearchIndices` manages versioned index names and aliases. Search data is eventually consistent and should not be used to authorize or execute a payment.

| Endpoint | Scope |
| --- | --- |
| `GET /api/search/transfers` | Transfers scoped to the authenticated participant. |
| `GET /api/admin/search/transfers` | Administrative transfer search and facets. |
| `GET /api/admin/search/loans` | Administrative loan search and facets. |

The admin endpoints are protected by the shared security configuration. Search filters, pagination and DTOs are defined in `SearchController`, `TransferSearch`, and `LoanSearch`; see the architecture document for example parameters. Listener failures are retried and the publication registry can retain incomplete deliveries for replay. Search availability is separate from wallet settlement. Schema/mapping changes need versioned index and alias handling; rebuilding requires an operational reindex process, not merely assuming existing historical data is present.

`SearchIntegrationTest` and `SearchProjectionIntegrationTest` use Testcontainers and require Docker.
