#!/usr/bin/env python3

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, Optional


# Ensure sibling imports work no matter the current working directory.
_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))


from common import DEFAULT_CHROMA_PATH, DEFAULT_DB_PATH, connect_db, ensure_schema  # noqa: E402


def cmd_ingest(args: argparse.Namespace) -> int:
    episode: Dict[str, Any]
    if args.stdin:
        episode = json.load(sys.stdin)
    else:
        with open(args.json, "r", encoding="utf-8") as f:
            episode = json.load(f)

    if args.context:
        ctx = episode.get("contexts")
        if isinstance(ctx, list):
            episode["contexts"] = list(dict.fromkeys([*ctx, *args.context]))
        elif ctx is None:
            episode["contexts"] = list(dict.fromkeys(args.context))
        else:
            # If stored as single value/dict, keep it and still append list.
            episode["contexts"] = list(dict.fromkeys([str(ctx), *args.context]))

    import chromadb

    from ingest_episode import ingest_episode

    conn = connect_db(args.db)
    ensure_schema(conn)
    client = chromadb.PersistentClient(path=args.chroma)

    eid = ingest_episode(conn, client, episode, embed=(not args.no_embed))
    print(eid)
    return 0


def cmd_search(args: argparse.Namespace) -> int:
    # Delegate to search.py for now to keep logic in one place.
    from search import main as search_main

    argv = [
        "--db",
        args.db,
        "--chroma",
        args.chroma,
        "--query",
        args.query,
        "--top",
        str(args.top),
        "--depth",
        str(args.depth),
        "--evidence",
        str(args.evidence),
        "--tau-days",
        str(args.tau_days),
        "--seed-mode",
        str(args.seed_mode),
    ]
    for c in args.context or []:
        argv.extend(["--context", c])
    for c in args.context_text or []:
        argv.extend(["--context-text", c])
    argv.extend(["--context-top", str(args.context_top)])
    argv.extend(["--context-depth", str(args.context_depth)])
    if args.context_strict:
        argv.append("--context-strict")
    if args.json:
        argv.append("--json")

    old_argv = sys.argv
    try:
        sys.argv = [old_argv[0]] + argv
        return int(search_main() or 0)
    finally:
        sys.argv = old_argv


def cmd_skill_put(args: argparse.Namespace) -> int:
    conn = connect_db(args.db)
    ensure_schema(conn)

    payload: Dict[str, Any]
    if args.json:
        with open(args.json, "r", encoding="utf-8") as f:
            payload = json.load(f)
    else:
        payload = {
            "skill_id": args.id,
            "title": args.title,
            "description": args.description,
            "tags": args.tags or [],
        }

    from skill_store import upsert_skill_def

    upsert_skill_def(conn, payload)
    conn.commit()
    print(payload.get("skill_id") or args.id)
    return 0


def cmd_skill_get(args: argparse.Namespace) -> int:
    conn = connect_db(args.db)
    ensure_schema(conn)

    from skill_store import get_skill_def

    row = get_skill_def(conn, args.id)
    if not row:
        return 2

    if args.json:
        print(json.dumps(row, ensure_ascii=False, indent=2))
        return 0

    print(f"{row.get('skill_id')}\t{row.get('title') or ''}")
    if row.get("description"):
        print(row["description"])
    return 0


def cmd_skill_list(args: argparse.Namespace) -> int:
    conn = connect_db(args.db)
    ensure_schema(conn)

    from skill_store import list_skill_defs

    rows = list_skill_defs(conn, limit=args.limit)
    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0
    for r in rows:
        print(f"{r.get('skill_id')}\t{r.get('title') or ''}")
    return 0


def main() -> int:
    p = argparse.ArgumentParser(prog="mem", description="AI Skills Memory CLI (SQLite graph + Chroma vectors + Ollama embeddings).")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp_init = sub.add_parser("init", help="Initialize SQLite schema + Chroma collections")
    sp_init.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_init.add_argument("--chroma", default=DEFAULT_CHROMA_PATH)
    sp_init.set_defaults(_run_init=True)

    sp_ingest = sub.add_parser("ingest", help="Ingest an episode JSON into DB and update graph/vectors")
    sp_ingest.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_ingest.add_argument("--chroma", default=DEFAULT_CHROMA_PATH)
    g = sp_ingest.add_mutually_exclusive_group(required=True)
    g.add_argument("--json", help="Path to episode.json")
    g.add_argument("--stdin", action="store_true", help="Read episode JSON from stdin")
    sp_ingest.add_argument("--no-embed", action="store_true", help="Skip Ollama embeddings + Chroma upserts")
    sp_ingest.add_argument("--context", action="append", default=[], help="Append external context key(s) at ingest time")
    sp_ingest.set_defaults(_run=cmd_ingest)

    sp_search = sub.add_parser("search", help="Hybrid search (Chroma seed -> SQLite BFS -> rank skills)")
    sp_search.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_search.add_argument("--chroma", default=DEFAULT_CHROMA_PATH)
    sp_search.add_argument("--query", required=True)
    sp_search.add_argument("--top", type=int, default=10)
    sp_search.add_argument("--depth", type=int, default=2)
    sp_search.add_argument("--evidence", type=int, default=3, help="Top evidence items per skill")
    sp_search.add_argument("--tau-days", type=float, default=30.0, help="Recency decay time constant (days)")
    sp_search.add_argument("--seed-mode", choices=["chroma", "sql"], default="chroma")
    sp_search.add_argument("--context", action="append", default=[], help="External context key to constrain retrieval (repeatable)")
    sp_search.add_argument("--context-text", action="append", default=[], help="Free-form context text snippet (repeatable)")
    sp_search.add_argument("--context-top", type=int, default=3)
    sp_search.add_argument("--context-depth", type=int, default=2)
    sp_search.add_argument("--context-strict", action="store_true")
    sp_search.add_argument("--json", action="store_true")
    sp_search.set_defaults(_run=cmd_search)

    sp_exp = sub.add_parser("export-onboarding", help="Export onboarding snapshot")
    sp_exp.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_exp.add_argument("--out", default="./ai_skills/onboarding_snapshot.yaml")
    sp_exp.set_defaults(_run_export=True)

    sp_skill = sub.add_parser("skill", help="Manage DB-first skill metadata")
    skill_sub = sp_skill.add_subparsers(dest="skill_cmd", required=True)

    sp_skill_put = skill_sub.add_parser("put", help="Upsert a skill definition")
    sp_skill_put.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_skill_put.add_argument("--json", help="Path to skill definition JSON")
    sp_skill_put.add_argument("--id", help="Skill id (if not using --json)")
    sp_skill_put.add_argument("--title", default=None)
    sp_skill_put.add_argument("--description", default=None)
    sp_skill_put.add_argument("--tags", nargs="*", default=None)
    sp_skill_put.set_defaults(_run=cmd_skill_put)

    sp_skill_get = skill_sub.add_parser("get", help="Get a skill definition")
    sp_skill_get.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_skill_get.add_argument("--id", required=True)
    sp_skill_get.add_argument("--json", action="store_true")
    sp_skill_get.set_defaults(_run=cmd_skill_get)

    sp_skill_list = skill_sub.add_parser("list", help="List skill definitions")
    sp_skill_list.add_argument("--db", default=DEFAULT_DB_PATH)
    sp_skill_list.add_argument("--limit", type=int, default=50)
    sp_skill_list.add_argument("--json", action="store_true")
    sp_skill_list.set_defaults(_run=cmd_skill_list)

    args = p.parse_args()

    if getattr(args, "_run_init", False):
        # call init_db.py main with overridden argv
        from init_db import main as init_db_main

        old = sys.argv
        try:
            sys.argv = [old[0], "--db", args.db, "--chroma", args.chroma]
            return int(init_db_main() or 0)
        finally:
            sys.argv = old

    if getattr(args, "_run_export", False):
        from export_onboarding import main as export_onboarding_main

        old = sys.argv
        try:
            sys.argv = [old[0], "--db", args.db, "--out", args.out]
            return int(export_onboarding_main() or 0)
        finally:
            sys.argv = old

    return int(args._run(args) or 0)


if __name__ == "__main__":
    raise SystemExit(main())
