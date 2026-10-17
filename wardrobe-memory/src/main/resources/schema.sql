CREATE TABLE IF NOT EXISTS counter (
    name TEXT    PRIMARY KEY,
    n    INTEGER NOT NULL
)@@

INSERT OR IGNORE INTO counter (name, n) VALUES ('turn', 0), ('fact', 0)@@

CREATE TABLE IF NOT EXISTS turn (
    id               TEXT    PRIMARY KEY,
    external_id      TEXT    NOT NULL UNIQUE,
    user_id          TEXT    NOT NULL,
    session_id       TEXT    NOT NULL,
    user_text        TEXT    NOT NULL,
    assistant_text   TEXT    NOT NULL DEFAULT '',
    status           TEXT    NOT NULL CHECK (status IN ('open', 'pending', 'done', 'failed', 'abandoned')),
    fail_reason      TEXT    NOT NULL DEFAULT '',
    forget_requested INTEGER NOT NULL DEFAULT 0 CHECK (forget_requested IN (0, 1)),
    created_at       TEXT    NOT NULL,
    erased_at        TEXT,
    CHECK (erased_at IS NOT NULL OR user_text <> ''),
    CHECK (erased_at IS NULL OR user_text = '')
)@@

CREATE UNIQUE INDEX IF NOT EXISTS turn_one_open ON turn (user_id) WHERE status = 'open'@@

CREATE TABLE IF NOT EXISTS fact (
    id                TEXT PRIMARY KEY,
    user_id           TEXT NOT NULL,
    kind              TEXT NOT NULL CHECK (kind IN ('preference', 'constraint', 'background')),
    scope             TEXT NOT NULL,
    fact              TEXT NOT NULL,
    quote             TEXT NOT NULL,
    standing          TEXT NOT NULL CHECK (standing IN ('stated', 'inferred')),
    source_turn_id    TEXT NOT NULL REFERENCES turn (id),
    source_session_id TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    supersedes        TEXT REFERENCES fact (id),
    status            TEXT NOT NULL CHECK (status IN ('active', 'superseded', 'forgotten')),
    forgotten_at      TEXT,
    forgotten_in      TEXT,
    CHECK (forgotten_at IS NOT NULL OR (fact <> '' AND quote <> '')),
    CHECK (forgotten_at IS NULL OR (fact = '' AND quote = '')),
    CHECK (status <> 'forgotten' OR forgotten_at IS NOT NULL)
)@@

CREATE UNIQUE INDEX IF NOT EXISTS fact_one_active ON fact (user_id, scope) WHERE status = 'active'@@

CREATE INDEX IF NOT EXISTS fact_source ON fact (source_turn_id)@@

CREATE VIRTUAL TABLE IF NOT EXISTS fact_fts USING fts5(body, fact_id UNINDEXED, tokenize = 'unicode61')@@

CREATE TRIGGER IF NOT EXISTS fact_fts_close
AFTER UPDATE OF status ON fact
WHEN new.status <> 'active'
BEGIN
    DELETE FROM fact_fts WHERE fact_id = old.id;
END@@

CREATE TRIGGER IF NOT EXISTS fact_append_only
BEFORE UPDATE ON fact
WHEN new.kind <> old.kind OR new.scope <> old.scope OR new.user_id <> old.user_id OR new.standing <> old.standing
  OR new.supersedes IS NOT old.supersedes OR new.source_turn_id <> old.source_turn_id
  OR new.source_session_id <> old.source_session_id OR new.created_at <> old.created_at
  OR (new.fact <> old.fact AND new.fact <> '') OR (new.quote <> old.quote AND new.quote <> '')
  OR (new.status <> old.status AND old.status <> 'active')
BEGIN
    SELECT RAISE(ABORT, 'MEM-STATE-010');
END@@
