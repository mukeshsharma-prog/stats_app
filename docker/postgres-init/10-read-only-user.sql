-- Stats App — the read-only database role.
--
-- Only Module 2 (stats-writer) writes to the database. That is
-- enforced by GRANT, not by convention — so a mistake in stats-api fails loudly
-- at the database instead of silently writing a row.
--
-- Runs ONCE, when the postgres data directory is first initialised. Changing this
-- file has no effect on an existing volume; `docker compose down -v` first.
--
-- The owner role (POSTGRES_USER, default `stats`) is created by the image itself
-- and is what stats-writer and Flyway connect as. Keep that tracker-approved
-- single-owner design; this script states its application grants explicitly.

-- psql does NOT pick up environment variables on its own; every one has to be
-- pulled in explicitly. The backtick form runs the command in a shell, and the
-- entrypoint has all four of these in the environment.
\set ro_user   `echo "$POSTGRES_RO_USER"`
\set ro_pass   `echo "$POSTGRES_RO_PASSWORD"`
\set db_name   `echo "$POSTGRES_DB"`
\set owner     `echo "$POSTGRES_USER"`

CREATE ROLE :"ro_user"
    LOGIN PASSWORD :'ro_pass'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- Writer/Flyway capability. CREATE on the schema permits tables, indexes and
-- partitions; object ownership is what permits ALTER and DROP. The explicit
-- table grants document and preserve the runtime data path even if ownership is
-- separated from migrations later.
GRANT CONNECT ON DATABASE :"db_name" TO :"owner";
GRANT USAGE, CREATE ON SCHEMA public TO :"owner";
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO :"owner";
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
    GRANT SELECT, INSERT ON TABLES TO :"owner";

GRANT CONNECT ON DATABASE :"db_name" TO :"ro_user";
GRANT USAGE ON SCHEMA public TO :"ro_user";

-- Existing objects. There are none at first init — Flyway has not run yet — but
-- this keeps the script correct if it is ever replayed against a populated
-- database.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"ro_user";

-- The line that actually matters. Flyway creates the per-type tables LATER, as
-- the owner role, so the grant has to be a standing default rather than a
-- one-time sweep. SELECT only: no INSERT, UPDATE, DELETE or TRUNCATE, ever.
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public
    GRANT SELECT ON TABLES TO :"ro_user";

-- Sequences are deliberately NOT granted. `uid` is its own column with a unique
-- constraint, never a database-generated identifier, so a
-- read-only caller has no reason to touch a sequence.

-- Belt and braces: revoke the public schema's default CREATE from everyone, so a
-- module cannot create a scratch table it was never meant to have.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- PostgreSQL grants TEMPORARY on a database to PUBLIC by default. A SELECT-only
-- role must not be able to create even session-local tables.
REVOKE TEMPORARY ON DATABASE :"db_name" FROM PUBLIC;
