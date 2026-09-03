-- D14b (claude/CLAUDE.md §7, DL-094).
--
-- D14 requires unknown members inside `data` to be accepted AND PRESERVED.
-- lorastats-v1.schema.json is `additionalProperties: true`, and ADR-003 D2 gives
-- lorastats typed columns -- which have nowhere to put an unmapped member, so the
-- Writer dropped it silently. That is the exact outcome ADR-003 D2 rejected in
-- writing when it chose a jsonb interface map for netstats rather than fixed
-- eth0_*/wwan_* columns.
--
-- Nullable, and NULL for every record whose payload is exactly the schema. A NULL
-- jsonb costs one null-bitmap bit, so this does not reintroduce the per-row key
-- name overhead D2 rejected (~375 GB/type): key names are stored only for the
-- records that actually carry an unmapped member.
--
-- ADD COLUMN with no DEFAULT is catalog-only on PostgreSQL 11+ -- no table rewrite,
-- which is what makes this safe to add to a two-billion-row table later.
--
-- Not indexed. Nothing queries it. It exists so that a member Heimdal adds between
-- releases survives until the schema catches up, and so the writer can meter the
-- drift instead of discarding it.

ALTER TABLE lorastats ADD COLUMN data_extra jsonb;

COMMENT ON COLUMN lorastats.data_extra IS
    'Unknown data members, preserved verbatim (D14/D14b). Never read by the application.';
