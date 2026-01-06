#!/usr/bin/env python3

import argparse
import chromadb
import json
import os
from pathlib import Path
from typing import Any, Dict, List

from common import connect_db, ensure_schema
from ingest_episode import ingest_episode
from skill_store import upsert_skill_def

DEFAULT_TEST_DB = os.environ.get("AI_SKILLS_TEST_DB", "./ai_memory/ai_skills_memory_test.sqlite")
DEFAULT_TEST_CHROMA = os.environ.get("AI_SKILLS_TEST_CHROMA", "./ai_memory/ai_skills_chroma_test")


def read_json(path: str) -> Any:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def read_jsonl(path: str) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            out.append(json.loads(line))
    return out


def main() -> int:
    p = argparse.ArgumentParser(description="Seed (and optionally reset) a persistent AI Skills test DB.")
    p.add_argument("--db", default=DEFAULT_TEST_DB)
    p.add_argument("--chroma", default=DEFAULT_TEST_CHROMA)
    p.add_argument(
        "--episodes",
        default=str(Path(__file__).resolve().parent / "test_data" / "episodes.jsonl"),
        help="Path to episodes.jsonl",
    )
    p.add_argument(
        "--skills",
        default=str(Path(__file__).resolve().parent / "test_data" / "skill_defs.json"),
        help="Path to skill_defs.json",
    )
    p.add_argument("--reset", action="store_true", help="Delete existing test DB + chroma dir")
    p.add_argument("--no-embed", action="store_true", help="Skip embeddings + chroma upserts")
    args = p.parse_args()

    db_path = Path(args.db)
    chroma_path = Path(args.chroma)

    if args.reset:
        if db_path.exists():
            db_path.unlink()
        if chroma_path.exists():
            # chroma persist is a directory
            import shutil

            shutil.rmtree(chroma_path)

    db_path.parent.mkdir(parents=True, exist_ok=True)
    chroma_path.mkdir(parents=True, exist_ok=True)

    conn = connect_db(str(db_path))
    ensure_schema(conn)
    client = chromadb.PersistentClient(path=str(chroma_path))

    # Upsert skill metadata.
    skills = read_json(args.skills)
    if isinstance(skills, list):
        for s in skills:
            if isinstance(s, dict):
                upsert_skill_def(conn, s)
    conn.commit()

    # Ingest episodes.
    episodes = read_jsonl(args.episodes)
    for ep in episodes:
        ingest_episode(conn, client, ep, embed=(not args.no_embed))

    conn.commit()
    print(f"OK: seeded db={db_path} chroma={chroma_path} episodes={len(episodes)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
