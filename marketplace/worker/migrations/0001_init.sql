CREATE TABLE IF NOT EXISTS oauth_states (
  state_hash TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_oauth_states_expiry ON oauth_states(expires_at);
CREATE TABLE IF NOT EXISTS auth_codes (
  code_hash TEXT PRIMARY KEY,
  user_json TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_codes_expiry ON auth_codes(expires_at);
CREATE TABLE IF NOT EXISTS items (
  id TEXT PRIMARY KEY,
  slug TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL CHECK (kind IN ('script','plugin')),
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  category TEXT NOT NULL,
  cover_key TEXT,
  author_github_id TEXT NOT NULL,
  author_login TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_items_public ON items(status,kind,updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_items_author ON items(author_github_id,created_at DESC);
CREATE TABLE IF NOT EXISTS versions (
  id TEXT PRIMARY KEY,
  item_id TEXT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  version TEXT NOT NULL,
  changelog TEXT NOT NULL,
  min_ksl_version TEXT NOT NULL DEFAULT '',
  mc_versions_json TEXT NOT NULL DEFAULT '[]',
  asset_key TEXT NOT NULL,
  filename TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected')),
  created_at TEXT NOT NULL,
  UNIQUE(item_id,version)
);
CREATE INDEX IF NOT EXISTS idx_versions_item ON versions(item_id,status,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_versions_pending ON versions(status,created_at);
