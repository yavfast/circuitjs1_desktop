import hashlib
import json
import ollama
import os
import requests
import sqlite3
from typing import Any, Dict, List, Optional

DEFAULT_DB_PATH = os.environ.get("AI_SKILLS_DB", "./ai_skills_memory.sqlite")
DEFAULT_CHROMA_PATH = os.environ.get("AI_SKILLS_CHROMA", "./ai_skills_chroma")


CHROMA_COLLECTION_NODES = "chroma_nodes"
CHROMA_COLLECTION_EDGES = "chroma_edges"
CHROMA_COLLECTION_EPISODES = "chroma_episodes"


def _get_ollama_client():
    host = os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434")
    return ollama.Client(host=host)


def ollama_embed(text: str) -> List[float]:
    model = os.environ.get("AI_EMBED_MODEL", "nomic-embed-text")
    client = _get_ollama_client()
    # Use the new 'embed' method if available, or fallback to 'embeddings'
    try:
        resp = client.embed(model=model, input=text)
        return resp["embeddings"][0]
    except (AttributeError, KeyError):
        # Fallback for older API/client
        resp = client.embeddings(model=model, prompt=text)
        return resp["embedding"]


def ollama_embed_batch(texts: List[str]) -> List[List[float]]:
    if not texts:
        return []
    model = os.environ.get("AI_EMBED_MODEL", "nomic-embed-text")
    client = _get_ollama_client()
    
    try:
        resp = client.embed(model=model, input=texts)
        return resp["embeddings"]
    except (AttributeError, KeyError):
        # Fallback: sequential
        return [ollama_embed(t) for t in texts]


def connect_db(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    return conn


def ensure_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA journal_mode=WAL;
        PRAGMA foreign_keys=ON;

        CREATE TABLE IF NOT EXISTS nodes (
            node_id TEXT PRIMARY KEY,
            node_type TEXT NOT NULL,
            key TEXT,
            name TEXT,
            summary TEXT,
            meta_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            UNIQUE(node_type, key)
        );

        CREATE TABLE IF NOT EXISTS edges (
            edge_id TEXT PRIMARY KEY,
            src_id TEXT NOT NULL,
            dst_id TEXT NOT NULL,
            rel_type TEXT NOT NULL,
            fact TEXT,
            weight REAL NOT NULL DEFAULT 0.0,
            valid_from TEXT NOT NULL,
            valid_to TEXT,
            last_seen_at TEXT NOT NULL,
            evidence_json TEXT,
            FOREIGN KEY(src_id) REFERENCES nodes(node_id),
            FOREIGN KEY(dst_id) REFERENCES nodes(node_id)
        );

                -- Active-edge uniqueness guard (temporal): only one active edge per (src,dst,rel,fact)
                CREATE UNIQUE INDEX IF NOT EXISTS uidx_edges_active
                    ON edges(src_id, dst_id, rel_type, IFNULL(fact,''))
                    WHERE valid_to IS NULL;

        CREATE INDEX IF NOT EXISTS idx_edges_src ON edges(src_id);
        CREATE INDEX IF NOT EXISTS idx_edges_dst ON edges(dst_id);
        CREATE INDEX IF NOT EXISTS idx_edges_rel ON edges(rel_type);

        CREATE TABLE IF NOT EXISTS episodes (
            episode_id TEXT PRIMARY KEY,
            timestamp TEXT NOT NULL,
            task_text TEXT,
            summary TEXT,
            outcome_status TEXT,
            trace_json TEXT
        );

        CREATE TABLE IF NOT EXISTS contexts (
            context_key TEXT PRIMARY KEY,
            kind TEXT,
            name TEXT,
            meta_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS episode_contexts (
            episode_id TEXT NOT NULL,
            context_key TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY(episode_id) REFERENCES episodes(episode_id),
            FOREIGN KEY(context_key) REFERENCES contexts(context_key)
        );
        CREATE UNIQUE INDEX IF NOT EXISTS uidx_episode_contexts
            ON episode_contexts(episode_id, context_key);

        CREATE TABLE IF NOT EXISTS edge_contexts (
            edge_id TEXT NOT NULL,
            context_key TEXT NOT NULL,
            valid_from TEXT NOT NULL,
            valid_to TEXT,
            last_seen_at TEXT NOT NULL,
            FOREIGN KEY(edge_id) REFERENCES edges(edge_id),
            FOREIGN KEY(context_key) REFERENCES contexts(context_key)
        );
        CREATE UNIQUE INDEX IF NOT EXISTS uidx_edge_contexts_active
            ON edge_contexts(edge_id, context_key)
            WHERE valid_to IS NULL;
        CREATE INDEX IF NOT EXISTS idx_edge_contexts_ctx
            ON edge_contexts(context_key);

        CREATE TABLE IF NOT EXISTS episode_links (
            episode_id TEXT NOT NULL,
            node_id TEXT,
            edge_id TEXT,
            FOREIGN KEY(episode_id) REFERENCES episodes(episode_id),
            FOREIGN KEY(node_id) REFERENCES nodes(node_id),
            FOREIGN KEY(edge_id) REFERENCES edges(edge_id)
        );

        CREATE INDEX IF NOT EXISTS idx_episode_links_episode ON episode_links(episode_id);

                CREATE UNIQUE INDEX IF NOT EXISTS uidx_episode_links_node
                    ON episode_links(episode_id, node_id)
                    WHERE node_id IS NOT NULL;

                CREATE UNIQUE INDEX IF NOT EXISTS uidx_episode_links_edge
                    ON episode_links(episode_id, edge_id)
                    WHERE edge_id IS NOT NULL;

        CREATE TABLE IF NOT EXISTS skill_defs (
            skill_id TEXT PRIMARY KEY,
            title TEXT,
            description TEXT,
            tags_json TEXT,
            contexts_json TEXT,
            io_schema_json TEXT,
            examples_json TEXT,
            permissions_json TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_skill_defs_title ON skill_defs(title);
        """
    )

    # Lightweight migrations for existing DBs (SQLite can't ALTER inside CREATE TABLE IF NOT EXISTS).
    def _has_column(table: str, col: str) -> bool:
        try:
            rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
        except Exception:
            return False
        return any((r["name"] if isinstance(r, sqlite3.Row) else r[1]) == col for r in rows)

    if conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='skill_defs'").fetchone():
        if not _has_column("skill_defs", "contexts_json"):
            conn.execute("ALTER TABLE skill_defs ADD COLUMN contexts_json TEXT")
    conn.commit()


def normalize_context_key(value: str) -> str:
    v = (value or "").strip()
    if not v:
        return ""
    # Keep ':' to support kind:key convention.
    return "".join(ch for ch in v if ch.isalnum() or ch in ("-", "_", ".", ":", "/"))


def normalize_contexts(raw: Any) -> List[str]:
    # Accept:
    # - string: "repo:foo"
    # - list[str]
    # - dict: {"repo": "foo", "branch": "main"} -> ["repo:foo","branch:main"]
    if raw is None:
        return []
    ctxs: List[str] = []
    if isinstance(raw, str):
        ctxs = [raw]
    elif isinstance(raw, list):
        ctxs = [str(x) for x in raw]
    elif isinstance(raw, dict):
        for k, v in raw.items():
            k2 = normalize_id(str(k))
            v2 = str(v).strip()
            if k2 and v2:
                ctxs.append(f"{k2}:{v2}")
    else:
        ctxs = [str(raw)]

    out = sorted({normalize_context_key(x) for x in ctxs if normalize_context_key(x)})
    return out


def upsert_context(conn: sqlite3.Connection, *, context_key: str, kind: Optional[str] = None, name: Optional[str] = None, meta: Optional[Dict[str, Any]] = None) -> str:
    now = now_iso()
    ck = normalize_context_key(context_key)
    if not ck:
        raise ValueError("context_key is required")
    k = normalize_id(kind or "") or None
    row = conn.execute("SELECT context_key FROM contexts WHERE context_key=?", (ck,)).fetchone()
    if row:
        conn.execute(
            "UPDATE contexts SET kind=COALESCE(?,kind), name=COALESCE(?,name), meta_json=COALESCE(?,meta_json), updated_at=? WHERE context_key=?",
            (k, name, json.dumps(meta, ensure_ascii=False) if meta is not None else None, now, ck),
        )
        return ck

    conn.execute(
        "INSERT INTO contexts(context_key,kind,name,meta_json,created_at,updated_at) VALUES (?,?,?,?,?,?)",
        (ck, k, name, json.dumps(meta, ensure_ascii=False) if meta is not None else None, now, now),
    )
    return ck


def link_episode_to_context(conn: sqlite3.Connection, episode_id: str, context_key: str) -> None:
    ck = normalize_context_key(context_key)
    if not ck:
        return
    upsert_context(conn, context_key=ck)
    conn.execute(
        "INSERT OR IGNORE INTO episode_contexts(episode_id, context_key, created_at) VALUES (?,?,?)",
        (episode_id, ck, now_iso()),
    )


def link_edge_to_context(conn: sqlite3.Connection, edge_id: str, context_key: str, *, now: Optional[str] = None) -> None:
    ck = normalize_context_key(context_key)
    if not ck:
        return
    now2 = now or now_iso()
    upsert_context(conn, context_key=ck)
    conn.execute(
        "INSERT OR IGNORE INTO edge_contexts(edge_id, context_key, valid_from, valid_to, last_seen_at) VALUES (?,?,?,?,?)",
        (edge_id, ck, now2, None, now2),
    )
    # Touch last_seen_at for existing active mapping.
    conn.execute(
        "UPDATE edge_contexts SET last_seen_at=? WHERE edge_id=? AND context_key=? AND valid_to IS NULL",
        (now2, edge_id, ck),
    )


def stable_id(prefix: str, *parts: str) -> str:
    h = hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()[:16]
    return f"{prefix}_{h}"


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def normalize_id(value: str) -> str:
    return "".join(ch for ch in (value or "").strip().lower() if ch.isalnum() or ch in ("-", "_", ".", ":"))


def normalize_path(value: str) -> str:
    return (value or "").strip().replace("\\", "/")


def failure_signature(error_text: str) -> str:
    t = (error_text or "").strip()
    if not t:
        return "unknown"
    return hashlib.sha256(t.encode("utf-8")).hexdigest()[:24]


def upsert_node(
    conn: sqlite3.Connection,
    *,
    node_type: str,
    key: str,
    name: Optional[str] = None,
    summary: Optional[str] = None,
    meta: Optional[Dict[str, Any]] = None,
) -> str:
    now = now_iso()
    node_type = normalize_id(node_type)
    key = key.strip()
    if not key:
        raise ValueError("node key is required")

    row = conn.execute(
        "SELECT node_id FROM nodes WHERE node_type=? AND key=?",
        (node_type, key),
    ).fetchone()

    if row:
        node_id = row["node_id"]
        conn.execute(
            "UPDATE nodes SET name=COALESCE(?,name), summary=COALESCE(?,summary), meta_json=COALESCE(?,meta_json), updated_at=? WHERE node_id=?",
            (name, summary, json.dumps(meta, ensure_ascii=False) if meta is not None else None, now, node_id),
        )
        return node_id

    node_id = stable_id("n", node_type, key)
    conn.execute(
        "INSERT INTO nodes(node_id,node_type,key,name,summary,meta_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
        (
            node_id,
            node_type,
            key,
            name,
            summary,
            json.dumps(meta, ensure_ascii=False) if meta is not None else None,
            now,
            now,
        ),
    )
    return node_id


def upsert_edge(
    conn: sqlite3.Connection,
    *,
    src_id: str,
    dst_id: str,
    rel_type: str,
    fact: str,
    episode_id: str,
    weight_delta: float,
    now: Optional[str] = None,
) -> str:
    now = now or now_iso()
    rel_type = normalize_id(rel_type)
    fact = (fact or "").strip()

    row = conn.execute(
        "SELECT edge_id, weight, evidence_json FROM edges WHERE src_id=? AND dst_id=? AND rel_type=? AND IFNULL(fact,'')=? AND valid_to IS NULL",
        (src_id, dst_id, rel_type, fact),
    ).fetchone()

    if row:
        edge_id = row["edge_id"]
        weight = float(row["weight"] or 0.0)
        evidence = []
        if row["evidence_json"]:
            try:
                evidence = json.loads(row["evidence_json"]) or []
            except Exception:
                evidence = []
        if episode_id not in evidence:
            evidence.append(episode_id)

        weight = clamp(weight + weight_delta, -10.0, 10.0)
        conn.execute(
            "UPDATE edges SET weight=?, last_seen_at=?, evidence_json=? WHERE edge_id=?",
            (weight, now, json.dumps(evidence, ensure_ascii=False), edge_id),
        )
        return edge_id

    # Edge versions are temporal; allow re-creation after invalidation.
    # Include `now` in the ID to avoid collisions with historical versions.
    edge_id = stable_id("e", src_id, rel_type, dst_id, fact, now)
    evidence_json = json.dumps([episode_id], ensure_ascii=False)
    conn.execute(
        "INSERT INTO edges(edge_id,src_id,dst_id,rel_type,fact,weight,valid_from,valid_to,last_seen_at,evidence_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
        (edge_id, src_id, dst_id, rel_type, fact, clamp(weight_delta, -10.0, 10.0), now, None, now, evidence_json),
    )
    return edge_id


def link_episode_to_node(conn: sqlite3.Connection, episode_id: str, node_id: str) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO episode_links(episode_id,node_id,edge_id) VALUES (?,?,NULL)",
        (episode_id, node_id),
    )


def link_episode_to_edge(conn: sqlite3.Connection, episode_id: str, edge_id: str) -> None:
    conn.execute(
        "INSERT OR IGNORE INTO episode_links(episode_id,node_id,edge_id) VALUES (?,NULL,?)",
        (episode_id, edge_id),
    )


def get_setting_bool(name: str, default: bool) -> bool:
    v = os.environ.get(name)
    if v is None:
        return default
    return v.strip().lower() in ("1", "true", "yes", "y", "on")


def now_iso() -> str:
    # Avoid importing datetime in multiple scripts; stable ISO format.
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()


def new_id(prefix: str) -> str:
    import uuid

    return f"{prefix}_{uuid.uuid4().hex}"


def read_json_file(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)
