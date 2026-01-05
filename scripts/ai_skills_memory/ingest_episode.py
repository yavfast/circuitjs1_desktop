#!/usr/bin/env python3

import argparse
import chromadb
import json
import sys
from typing import Any, Dict, List, Optional, Tuple

from common import (
    CHROMA_COLLECTION_EDGES,
    CHROMA_COLLECTION_EPISODES,
    CHROMA_COLLECTION_NODES,
    DEFAULT_CHROMA_PATH,
    DEFAULT_DB_PATH,
    connect_db,
    ensure_schema,
    failure_signature,
    link_episode_to_edge,
    link_episode_to_node,
    new_id,
    normalize_id,
    normalize_path,
    now_iso,
    ollama_embed_batch,
    read_json_file,
    upsert_edge,
    upsert_node,
    stable_id,
)


def normalize_event(episode: Dict[str, Any]) -> Dict[str, Any]:
    e = dict(episode)
    e.setdefault("timestamp", now_iso())

    used_skills = e.get("used_skills") or []
    if not isinstance(used_skills, list):
        used_skills = [str(used_skills)]
    e["used_skills"] = sorted({normalize_id(str(x)) for x in used_skills if str(x).strip()})

    touched = e.get("touched_artifacts") or []
    if not isinstance(touched, list):
        touched = [str(touched)]
    e["touched_artifacts"] = sorted({normalize_path(str(x)) for x in touched if str(x).strip()})

    errors = e.get("errors") or []
    if not isinstance(errors, list):
        errors = [str(errors)]
    e["errors"] = [str(x).strip() for x in errors if str(x).strip()]

    outcome = e.get("outcome") or {}
    if not isinstance(outcome, dict):
        outcome = {"status": str(outcome)}
    status = normalize_id(str(outcome.get("status") or "partial"))
    outcome["status"] = status or "partial"
    e["outcome"] = outcome

    # External context(s) as free-form text describing the chat/environment.
    # Accepts:
    # - context_text: string
    # - context_texts: list[string]
    # - context/contexts: legacy fields treated as context text
    ctxs: List[str] = []
    for k in ("context_text", "context"):
        v = e.get(k)
        if isinstance(v, str) and v.strip():
            ctxs.append(v.strip())
    for k in ("context_texts", "contexts"):
        v = e.get(k)
        if isinstance(v, list):
            for item in v:
                s = str(item).strip()
                if s:
                    ctxs.append(s)
        elif isinstance(v, str) and v.strip():
            ctxs.append(v.strip())

    # Keep free-form text for vectorization; normalize whitespace only.
    ctxs2: List[str] = []
    for x in ctxs:
        t = " ".join(str(x).strip().split())
        if t:
            ctxs2.append(t)
    e["contexts"] = list(dict.fromkeys(ctxs2))

    return e


def weight_delta_from_outcome(status: str, confidence: float) -> float:
    if status == "success":
        return 1.0 * confidence
    if status == "failure":
        return -1.0 * confidence
    return 0.0


def chroma_upsert_batch(collection, *, ids: List[str], texts: List[str], metadatas: List[Dict[str, Any]]) -> None:
    if not ids:
        return
    embeddings = ollama_embed_batch(texts)
    collection.upsert(ids=ids, documents=texts, embeddings=embeddings, metadatas=metadatas)


def ingest_episode(conn, client, episode: Dict[str, Any], *, embed: bool) -> str:
    ensure_schema(conn)

    e = normalize_event(episode)
    episode_id = e.get("episode_id") or new_id("ep")
    ts = e.get("timestamp") or now_iso()

    # Idempotency: if caller provides an episode_id that already exists, do not
    # re-apply weight deltas / duplicate evidence (important for repeatable seeding).
    if e.get("episode_id"):
        existing = conn.execute("SELECT 1 FROM episodes WHERE episode_id=?", (episode_id,)).fetchone()
        if existing:
            return episode_id

    conn.execute(
        "INSERT OR REPLACE INTO episodes(episode_id, timestamp, task_text, summary, outcome_status, trace_json) VALUES (?,?,?,?,?,?)",
        (
            episode_id,
            ts,
            e.get("task_text"),
            e.get("summary"),
            (e.get("outcome") or {}).get("status"),
            json.dumps(e, ensure_ascii=False),
        ),
    )

    # --- Graph formation (structured facts; MVP) ---
    now = ts
    outcome_status = (e.get("outcome") or {}).get("status") or "partial"
    contexts: List[str] = e.get("contexts") or []

    episode_node_id = upsert_node(
        conn,
        node_type="episode",
        key=episode_id,
        name=f"Episode {episode_id}",
        summary=(e.get("summary") or e.get("task_text")),
        meta={"timestamp": ts},
    )
    link_episode_to_node(conn, episode_id, episode_node_id)

    # Context nodes + episode -> context edges.
    # Context is treated as a node with summary text (vectorizable).
    # If the context contains '/', it is interpreted as a hierarchical path:
    #   "біологія/віруси/реплікація" => Context nodes for each prefix + CHILD_OF edges.
    for ctx_text in contexts:
        raw = str(ctx_text).strip()
        if not raw:
            continue

        # Hierarchy via '/' path; otherwise treat as a single leaf context.
        if "/" in raw:
            segments = [s.strip() for s in raw.split("/") if s.strip()]
        else:
            segments = [raw]

        # Create context nodes for each prefix, linking them with CHILD_OF.
        parent_ctx_node_id: Optional[str] = None
        prefix_parts: List[str] = []
        leaf_ctx_node_id: Optional[str] = None

        for level, seg in enumerate(segments):
            prefix_parts.append(seg)
            path = "/".join(prefix_parts)
            ctx_key = stable_id("ctx", path)
            ctx_node_id = upsert_node(
                conn,
                node_type="context",
                key=ctx_key,
                name="context",
                summary=path,
                meta={"path": path, "label": seg, "level": int(level)},
            )
            link_episode_to_node(conn, episode_id, ctx_node_id)

            if parent_ctx_node_id is not None:
                child_edge_id = upsert_edge(
                    conn,
                    src_id=ctx_node_id,
                    dst_id=parent_ctx_node_id,
                    rel_type="CHILD_OF",
                    fact="child_of",
                    episode_id=episode_id,
                    weight_delta=0.0,
                    now=now,
                )
                link_episode_to_edge(conn, episode_id, child_edge_id)

            parent_ctx_node_id = ctx_node_id
            leaf_ctx_node_id = ctx_node_id

        # Episode is considered in the most specific (leaf) context.
        if leaf_ctx_node_id is not None:
            ectx_edge_id = upsert_edge(
                conn,
                src_id=episode_node_id,
                dst_id=leaf_ctx_node_id,
                rel_type="IN_CONTEXT",
                fact="in_context",
                episode_id=episode_id,
                weight_delta=0.0,
                now=now,
            )
            link_episode_to_edge(conn, episode_id, ectx_edge_id)

    # Outcome node
    outcome_node_id = upsert_node(
        conn,
        node_type="outcome",
        key=outcome_status,
        name=f"Outcome {outcome_status}",
        summary=(e.get("outcome") or {}).get("notes"),
        meta=None,
    )
    link_episode_to_node(conn, episode_id, outcome_node_id)

    # Episode -> Outcome edge
    edge_id = upsert_edge(
        conn,
        src_id=episode_node_id,
        dst_id=outcome_node_id,
        rel_type="RESULTED_IN",
        fact=outcome_status,
        episode_id=episode_id,
        weight_delta=weight_delta_from_outcome(outcome_status, 1.0),
        now=now,
    )
    link_episode_to_edge(conn, episode_id, edge_id)

    # Skills
    used_skills: List[str] = e.get("used_skills") or []
    for sid in used_skills:
        skill_node_id = upsert_node(conn, node_type="skill", key=sid, name=sid, summary=None, meta=None)
        link_episode_to_node(conn, episode_id, skill_node_id)
        edge_id = upsert_edge(
            conn,
            src_id=episode_node_id,
            dst_id=skill_node_id,
            rel_type="USED_SKILL",
            fact=f"used {sid}",
            episode_id=episode_id,
            weight_delta=weight_delta_from_outcome(outcome_status, 1.0),
            now=now,
        )
        link_episode_to_edge(conn, episode_id, edge_id)

    # Artifacts
    touched_artifacts: List[str] = e.get("touched_artifacts") or []
    for path in touched_artifacts:
        art_node_id = upsert_node(conn, node_type="artifact", key=path, name=path, summary=None, meta=None)
        link_episode_to_node(conn, episode_id, art_node_id)
        edge_id = upsert_edge(
            conn,
            src_id=episode_node_id,
            dst_id=art_node_id,
            rel_type="TOUCHED_ARTIFACT",
            fact=f"touched {path}",
            episode_id=episode_id,
            weight_delta=0.0,
            now=now,
        )
        link_episode_to_edge(conn, episode_id, edge_id)

    # Failure modes (if errors exist)
    errors: List[str] = e.get("errors") or []
    if errors or outcome_status == "failure":
        for err in errors:
            sig = failure_signature(err)
            fm_node_id = upsert_node(
                conn,
                node_type="failure_mode",
                key=sig,
                name=f"Failure {sig}",
                summary=err,
                meta=None,
            )
            link_episode_to_node(conn, episode_id, fm_node_id)
            edge_id = upsert_edge(
                conn,
                src_id=outcome_node_id,
                dst_id=fm_node_id,
                rel_type="HAS_FAILURE_MODE",
                fact=err,
                episode_id=episode_id,
                weight_delta=-0.5,
                now=now,
            )
            link_episode_to_edge(conn, episode_id, edge_id)

    # --- Chroma indexing ---
    col_eps = client.get_or_create_collection(name=CHROMA_COLLECTION_EPISODES)
    col_nodes = client.get_or_create_collection(name=CHROMA_COLLECTION_NODES)
    col_edges = client.get_or_create_collection(name=CHROMA_COLLECTION_EDGES)

    if embed:
        # 1. Episode
        summary = e.get("summary") or e.get("task_text") or ""
        if summary.strip():
            chroma_upsert_batch(
                col_eps,
                ids=[episode_id],
                texts=[summary],
                metadatas=[{"kind": "episode", "id": episode_id, "type": "episode"}],
            )

        # 2. Nodes
        node_rows = conn.execute(
            "SELECT DISTINCT n.node_id, n.node_type, n.name, n.summary FROM nodes n JOIN episode_links l ON l.node_id=n.node_id WHERE l.episode_id=?",
            (episode_id,),
        ).fetchall()
        
        n_ids, n_texts, n_metas = [], [], []
        for r in node_rows:
            text = " ".join([x for x in [r["name"], r["summary"]] if x])
            if text.strip():
                n_ids.append(r["node_id"])
                n_texts.append(text)
                n_metas.append({"kind": "node", "id": r["node_id"], "type": r["node_type"]})
        
        chroma_upsert_batch(col_nodes, ids=n_ids, texts=n_texts, metadatas=n_metas)

        # 3. Edges
        edge_rows = conn.execute(
            "SELECT DISTINCT e.edge_id, e.rel_type, e.fact FROM edges e JOIN episode_links l ON l.edge_id=e.edge_id WHERE l.episode_id=?",
            (episode_id,),
        ).fetchall()

        e_ids, e_texts, e_metas = [], [], []
        for r in edge_rows:
            text = r["fact"] or r["rel_type"]
            if text.strip():
                e_ids.append(r["edge_id"])
                e_texts.append(text)
                e_metas.append({"kind": "edge", "id": r["edge_id"], "type": r["rel_type"]})

        chroma_upsert_batch(col_edges, ids=e_ids, texts=e_texts, metadatas=e_metas)

    conn.commit()
    return episode_id


def main() -> int:
    p = argparse.ArgumentParser(description="Ingest an episode JSON into SQLite + Chroma.")
    p.add_argument("--db", default=DEFAULT_DB_PATH)
    p.add_argument("--chroma", default=DEFAULT_CHROMA_PATH)
    p.add_argument("--no-embed", action="store_true", help="Skip Ollama embeddings + Chroma upserts")
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("--json", help="Path to episode.json")
    g.add_argument("--stdin", action="store_true", help="Read episode JSON from stdin")
    args = p.parse_args()

    episode: Dict[str, Any]
    if args.stdin:
        episode = json.load(sys.stdin)
    else:
        episode = read_json_file(args.json)

    conn = connect_db(args.db)
    ensure_schema(conn)
    client = chromadb.PersistentClient(path=args.chroma)

    episode_id = ingest_episode(conn, client, episode, embed=(not args.no_embed))
    print(episode_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
