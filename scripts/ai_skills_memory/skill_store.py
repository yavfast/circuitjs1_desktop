import json
import sqlite3
from typing import Any, Dict, List, Optional

from common import now_iso


def _json_dump(value: Any) -> Optional[str]:
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=False)


def _json_load(value: Optional[str]) -> Any:
    if not value:
        return None
    try:
        return json.loads(value)
    except Exception:
        return None


def upsert_skill_def(conn: sqlite3.Connection, payload: Dict[str, Any]) -> None:
    skill_id = (payload.get("skill_id") or payload.get("id") or "").strip()
    if not skill_id:
        raise ValueError("skill_id is required")

    now = now_iso()
    title = payload.get("title")
    description = payload.get("description")
    tags = payload.get("tags")
    contexts = payload.get("contexts")
    io_schema = payload.get("io_schema")
    examples = payload.get("examples")
    permissions = payload.get("permissions")

    row = conn.execute("SELECT skill_id FROM skill_defs WHERE skill_id=?", (skill_id,)).fetchone()
    if row:
        conn.execute(
            """
            UPDATE skill_defs
               SET title=COALESCE(?, title),
                   description=COALESCE(?, description),
                   tags_json=COALESCE(?, tags_json),
                   contexts_json=COALESCE(?, contexts_json),
                   io_schema_json=COALESCE(?, io_schema_json),
                   examples_json=COALESCE(?, examples_json),
                   permissions_json=COALESCE(?, permissions_json),
                   updated_at=?
             WHERE skill_id=?
            """,
            (
                title,
                description,
                _json_dump(tags),
                _json_dump(contexts),
                _json_dump(io_schema),
                _json_dump(examples),
                _json_dump(permissions),
                now,
                skill_id,
            ),
        )
        return

    conn.execute(
        """
        INSERT INTO skill_defs(skill_id,title,description,tags_json,contexts_json,io_schema_json,examples_json,permissions_json,created_at,updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        """,
        (
            skill_id,
            title,
            description,
            _json_dump(tags),
            _json_dump(contexts),
            _json_dump(io_schema),
            _json_dump(examples),
            _json_dump(permissions),
            now,
            now,
        ),
    )


def get_skill_def(conn: sqlite3.Connection, skill_id: str) -> Optional[Dict[str, Any]]:
    row = conn.execute(
        "SELECT skill_id,title,description,tags_json,contexts_json,io_schema_json,examples_json,permissions_json,created_at,updated_at FROM skill_defs WHERE skill_id=?",
        (skill_id,),
    ).fetchone()
    if not row:
        return None

    return {
        "skill_id": row["skill_id"],
        "title": row["title"],
        "description": row["description"],
        "tags": _json_load(row["tags_json"]) or [],
        "contexts": _json_load(row["contexts_json"]) or [],
        "io_schema": _json_load(row["io_schema_json"]),
        "examples": _json_load(row["examples_json"]) or [],
        "permissions": _json_load(row["permissions_json"]),
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def list_skill_defs(conn: sqlite3.Connection, *, limit: int = 50) -> List[Dict[str, Any]]:
    rows = conn.execute(
        "SELECT skill_id,title,description,updated_at FROM skill_defs ORDER BY updated_at DESC LIMIT ?",
        (max(1, int(limit)),),
    ).fetchall()
    out: List[Dict[str, Any]] = []
    for r in rows:
        out.append({"skill_id": r["skill_id"], "title": r["title"], "description": r["description"], "updated_at": r["updated_at"]})
    return out
