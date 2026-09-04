-- clsbox is stored in ONE canonical case (DL-115).
--
-- The ARD specifies `gateway CHAR(12) NOT NULL CHECK (gateway ~ '^[0-9A-F]{12}$')`
-- -- uppercase only. V1 dropped the constraint when the column was renamed to
-- clsbox (ADR-007 §1) and nothing replaced the guarantee, so `04b64810652b` and
-- `04B64810652B` -- the same physical device -- were two distinct keys in
-- idx_lorastats_tenant_clsbox_device_uid.
--
-- That is invisible rather than loud. `GET /v1/stats/{type}?clsbox=...` promises a
-- case-insensitive match (openapi.yaml, `Gateway` parameter), and clsbox is half of
-- the dominant query pattern (Q14). A mixed-case estate therefore returns a
-- SILENTLY PARTIAL page: the rows are stored, the query is well-formed, the response
-- is 200, and the missing rows look like an absence of data. No fixture could catch
-- it either -- every clsbox in docs/schemas/fixtures/ was uppercase until
-- valid/lorastats-clsbox-lowercase.json was added with this change.
--
-- The fix is normalise-then-constrain, NOT reject:
--
--   * The Writer uppercases clsbox before the INSERT (LorastatsBatchWriter), so the
--     stored form is canonical whatever the wire sends.
--   * This CHECK is the backstop that makes the invariant a property of the table
--     rather than a property of one class -- the next type's writer cannot forget it
--     without failing loudly here.
--   * envelope-v1.schema.json deliberately KEEPS `^[0-9A-Fa-f]{12}$`. Rejecting a
--     lowercase record at Input would 400 a valid measurement over a cosmetic
--     difference, and rejecting it here would dead-letter it -- the same mistake
--     netstats made by requiring ip/gw/mask/dns (claude/CLAUDE.md §6) and the one
--     DL-105 has just finished reversing for the lorastats dialects.
--
-- Hex case carries no meaning, so normalising loses nothing a reader can use. It is
-- still a normalisation: the exact bytes Heimdal sent are recoverable only from the
-- published message, not from the row. ADR-004's byte-identical publication is
-- untouched -- Input republishes the received bytes and never this column.
--
-- EVERY new stat-type table must carry this constraint, for the same reason every
-- one must carry data_extra (D14b, V4). Omitting it silently reopens the split key.

UPDATE lorastats SET clsbox = upper(clsbox) WHERE clsbox <> upper(clsbox);

ALTER TABLE lorastats
    ADD CONSTRAINT lorastats_clsbox_canonical_hex
    CHECK (clsbox ~ '^[0-9A-F]{12}$');

COMMENT ON COLUMN lorastats.clsbox IS
    'Device identifier, 12 uppercase hex characters. Normalised by the Writer and '
    'constrained here so (tenant, clsbox, ...) cannot split one device across two '
    'keys (DL-115).';
