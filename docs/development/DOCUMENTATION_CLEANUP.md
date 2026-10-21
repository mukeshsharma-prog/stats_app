# Documentation cleanup — 19 October 2026

The project documentation was reorganized to remove obsolete assistant scaffolding
and dated scratch material from the active repository while preserving architectural
requirements, decisions, current plans and operational evidence.

## Preserved and relocated

- The full decision log moved from `claude/DECISIONS.md` to `docs/decisions/DECISIONS.md`.
  Its dated entries retain their original wording and decision identifiers.
- Project context, the active plan, engineering rules, development instructions,
  release checklist and LTE/netstats implementation record moved to `docs/development/`.
- The execution flow and current ARD v1.1 moved to `docs/architecture/`.
- The historical open-question decision register moved to `docs/decisions/`.
- The manual verification guide moved to `docs/runbooks/` and dated LTE/netstats
  verification evidence moved to `docs/verification/`.
- Original SRS/BRD/ARB baseline sources remain under `docs/requirements/source/`.
  Ratified answer documents and original internal decisions remain under
  `docs/decisions/source/`. Later approved amendments still govern conflicts.
- The supplied netstats sample remains under `docs/schemas/reference/`.
- All seven repository ADRs, requirements, contracts, runtime schemas and fixtures,
  current Keycloak configuration, verification results and handover guides remain.

Queue and retention procedures now have direct operational runbooks. Alert rule links
and their expected test labels were updated, and the packaging scripts include those
runbooks. Existing deployed bundles and Docker services were not changed by cleanup.

## Retired documents

The following files were removed from the active tree after saving recoverable copies:

- Root `CLAUDE.md` auto-load pointer and `.claude/settings.json`.
- `docs/prompts/CLAUDE_PROMPTS.md`, the old assistant prompt templates.
- `claude/OUTBOX_03SEP2026.md`, dated draft messages explicitly marked as non-decisions.
- `docs/source/Datahub_Core_StatsApp_ARD_v1_0.pdf`, superseded by retained v1.1.
- `docs/source/Stats_App_Questions_v1_0.docx`, the original question list; answered
  decisions and the historical reconciliation register remain in active docs.
- `docs/source/Stats_App_Claude_Ready_PostgreSQL_Pack.zip`, the duplicate original
  delivery pack containing stale assistant context.
- Ignored generated `stats-api-v1-draft.html`; rebuild it with
  `docs/contracts/build-html.sh` or obtain the CI artifact.
- The two dated workspace scratchpads, `Tasks to do next` and
  `Tasks to do after T-08,T-10,T-11`.

## Recovery

The local, pre-cleanup copies are outside the active Git repository, at:

```text
../.project-archive/2026-10-19-documentation-cleanup/
├── README.md
├── manifest.json
└── before/
    ├── stats_app/
    └── workspace/
```

`manifest.json` records original and destination paths and original SHA-256 hashes.
The backup includes the pre-existing uncommitted documentation changes, not just HEAD.
Tracked historical versions also remain available through Git history. The local
backup is not part of a cloned repository or distributable Docker bundle.

Credentials, live `dist/` installations and archives, recovery/database backups,
Docker volumes, source code, SQL migrations, schemas and test fixtures were retained.
A new bundle must be built explicitly to incorporate source documentation changes;
this cleanup does not overwrite a running installation or its private `.env`.

## Validation

- 427 application source, migration, schema and fixture files verified unchanged.
- Complete original decision-log body and all its existing decision identifiers retained.
- Relocated PDFs, Word sources, original sample and JSON evidence verified against their original SHA-256 hashes.
- Every retired file has a verified recoverable local copy.
- Local Markdown link targets and writer alert runbook targets checked.
- Schema fixture gate: 124 assertions, 0 failures.
- OpenAPI parity gate and 8 contract regression tests passed.
- All 30 Docker helper tests passed.
- Prometheus rules validated: 16 rules across three files; all rule test suites passed.
- Packaging shell syntax and Git whitespace checks passed.

The Java runtime was not rebuilt for this documentation reorganization. Existing
source changes from earlier work remain in the working tree and were not reverted.
