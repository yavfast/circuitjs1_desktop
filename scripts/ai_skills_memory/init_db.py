#!/usr/bin/env python3

import argparse

import chromadb

from common import (
    CHROMA_COLLECTION_EDGES,
    CHROMA_COLLECTION_EPISODES,
    CHROMA_COLLECTION_NODES,
    DEFAULT_CHROMA_PATH,
    DEFAULT_DB_PATH,
    connect_db,
    ensure_schema,
)


def main() -> int:
    p = argparse.ArgumentParser(description="Initialize AI Skills Memory DB (SQLite + ChromaDB).")
    p.add_argument("--db", default=DEFAULT_DB_PATH, help="Path to SQLite DB file")
    p.add_argument("--chroma", default=DEFAULT_CHROMA_PATH, help="Path to Chroma persist directory")
    args = p.parse_args()

    conn = connect_db(args.db)
    ensure_schema(conn)

    client = chromadb.PersistentClient(path=args.chroma)
    # Collections are created if they do not exist.
    client.get_or_create_collection(name=CHROMA_COLLECTION_NODES)
    client.get_or_create_collection(name=CHROMA_COLLECTION_EDGES)
    client.get_or_create_collection(name=CHROMA_COLLECTION_EPISODES)

    print(f"OK: db={args.db} chroma={args.chroma}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
