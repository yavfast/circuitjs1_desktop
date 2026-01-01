#!/usr/bin/env python3

import argparse
import json
from typing import Any, Dict

from common import DEFAULT_DB_PATH, connect_db, ensure_schema


def main() -> int:
    p = argparse.ArgumentParser(description="Export lightweight onboarding snapshot (YAML-ish JSON) from SQLite.")
    p.add_argument("--db", default=DEFAULT_DB_PATH)
    p.add_argument("--out", default="./ai_skills/onboarding_snapshot.yaml")
    args = p.parse_args()

    conn = connect_db(args.db)
    ensure_schema(conn)

    # MVP: very small snapshot.
    episode_count = conn.execute("SELECT COUNT(*) AS c FROM episodes").fetchone()["c"]
    node_count = conn.execute("SELECT COUNT(*) AS c FROM nodes").fetchone()["c"]
    edge_count = conn.execute("SELECT COUNT(*) AS c FROM edges").fetchone()["c"]

    snapshot: Dict[str, Any] = {
        "meta": {"generated_from": args.db},
        "counts": {"episodes": episode_count, "nodes": node_count, "edges": edge_count},
        "how_to": {
            "init": "./ai_mem.sh init  # (or) python3 scripts/ai_skills_memory/mem.py init --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/",
            "ingest": "./ai_mem.sh ingest --json episode.json  # (or) python3 scripts/ai_skills_memory/mem.py ingest --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/ --json episode.json",
            "search": "./ai_mem.sh search --query '...'  # (or) python3 scripts/ai_skills_memory/mem.py search --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/ --query '...'",
        },
    }

    top_skills = conn.execute(
        """
        SELECT n.key AS skill_id, COALESCE(SUM(e.weight),0.0) AS score
          FROM nodes n
          LEFT JOIN edges e
            ON e.valid_to IS NULL AND (e.src_id=n.node_id OR e.dst_id=n.node_id)
         WHERE n.node_type='skill'
         GROUP BY n.key
         ORDER BY score DESC
         LIMIT 20
        """
    ).fetchall()
    snapshot["top_skills"] = [{"skill_id": r["skill_id"], "score": float(r["score"] or 0.0)} for r in top_skills]

    # Write as YAML-ish: keep it JSON to avoid adding PyYAML dependency.
    with open(args.out, "w", encoding="utf-8") as f:
        f.write("# Auto-generated onboarding snapshot (JSON written with .yaml extension)\n")
        json.dump(snapshot, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
