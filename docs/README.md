# Documentation index

| Location | Contents |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | The design record: modules, record contract, delivery and failure paths, storage and retention, tenant isolation, open external decisions |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | Build, run, test, code conventions, logging rules, pull requests |
| [contracts/](contracts/) | Read API (`openapi.yaml`) and ingest (`openapi-input.yaml`, `heimdal-input-contract-v1.md`) contracts, the AMQP topology and Writer metrics contracts, and the contract tooling CI runs (`check_schema_drift.py`, `build-html.sh`) |
| [schemas/](schemas/SCHEMA_CATALOGUE.md) | The runtime JSON Schema catalogue — bundled into `stats-common` and used to validate every record — and the fixtures every module's tests read ([manifest](schemas/fixtures/MANIFEST.md)) |
| [requirements/](requirements/) | SRS, BRD and ARB v1.2 implementation amendments, pending approval |
| [decisions/DATA-SENSITIVITY-INVENTORY.md](decisions/DATA-SENSITIVITY-INVENTORY.md) | Field-level sensitivity, where each field comes to rest, and the open data-handling questions |
| [setup/](setup/keycloak-realm-statsapp.md) | The development Keycloak realm and user profile, imported by Compose |

## Runbooks

* [Queue troubleshooting](runbooks/queue-troubleshooting.md)
* [DLQ redrive](runbooks/dlq-redrive.md)
* [Partition restore](runbooks/partition-restore.md)
* [Retention policy changes](runbooks/retention-policy.md)
* [Manual verification](runbooks/manual-verification.md)
* [Burst load test](runbooks/load-test-burst.md) and [replay drill](runbooks/replay-drill.md)

## Operations

* [Prometheus alert rules](../ops/prometheus/README.md), [Grafana dashboards](../ops/grafana/README.md)
  and [operational drills](../ops/drills/README.md).
* [Docker bundle guide](../docker/BUNDLE.md) and [manual walkthrough](../docker/MANUAL.md).
