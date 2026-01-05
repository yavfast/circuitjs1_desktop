#!/usr/bin/env python3

import argparse
import chromadb
import json
import re
import sqlite3
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Set, Tuple

from common import (
    CHROMA_COLLECTION_EDGES,
    CHROMA_COLLECTION_EPISODES,
    CHROMA_COLLECTION_NODES,
    DEFAULT_CHROMA_PATH,
    DEFAULT_DB_PATH,
    connect_db,
    ensure_schema,
    ollama_embed,
)


def chroma_query(client, collection: str, query: str, top_k: int) -> Dict[str, Any]:
    col = client.get_or_create_collection(name=collection)
    vec = ollama_embed(query)
    return col.query(query_embeddings=[vec], n_results=top_k, include=["documents", "metadatas", "distances"])  # type: ignore[no-any-return]


def flatten_query(res: Dict[str, Any]) -> List[Tuple[str, float, Dict[str, Any], str]]:
    ids = (res.get("ids") or [[]])[0]
    docs = (res.get("documents") or [[]])[0]
    metas = (res.get("metadatas") or [[]])[0]
    dists = (res.get("distances") or [[]])[0]
    out: List[Tuple[str, float, Dict[str, Any], str]] = []
    for i, item_id in enumerate(ids):
        dist = float(dists[i]) if i < len(dists) and dists[i] is not None else 999.0
        doc = docs[i] if i < len(docs) else ""
        meta = metas[i] if i < len(metas) else {}
        out.append((item_id, dist, meta, doc))
    return out


def sql_seed_nodes(conn: sqlite3.Connection, query: str, top_k: int) -> List[str]:
    # Deterministic fallback seeding for tests and offline mode.
    # Uses LIKE matching over episodes/nodes/edges; returns node_ids.
    q = f"%{query}%"
    seeds: List[str] = []

    # Episodes -> episode node_id.
    e_rows = conn.execute(
        """
        SELECT episode_id
          FROM episodes
         WHERE COALESCE(summary,'') LIKE ? OR COALESCE(task_text,'') LIKE ?
         ORDER BY timestamp DESC
         LIMIT ?
        """,
        (q, q, max(1, int(top_k))),
    ).fetchall()
    for r in e_rows:
        row = conn.execute("SELECT node_id FROM nodes WHERE node_type='episode' AND key=?", (r["episode_id"],)).fetchone()
        if row:
            seeds.append(row["node_id"])

    # Nodes directly.
    n_rows = conn.execute(
        """
        SELECT node_id
          FROM nodes
         WHERE COALESCE(name,'') LIKE ? OR COALESCE(summary,'') LIKE ?
         ORDER BY updated_at DESC
         LIMIT ?
        """,
        (q, q, max(1, int(top_k))),
    ).fetchall()
    for r in n_rows:
        seeds.append(r["node_id"])

    # Edges -> endpoints.
    ed_rows = conn.execute(
        """
        SELECT src_id, dst_id
          FROM edges
         WHERE valid_to IS NULL AND (COALESCE(fact,'') LIKE ? OR COALESCE(rel_type,'') LIKE ?)
         ORDER BY last_seen_at DESC
         LIMIT ?
        """,
        (q, q, max(1, int(top_k))),
    ).fetchall()
    for r in ed_rows:
        if r["src_id"]:
            seeds.append(r["src_id"])
        if r["dst_id"]:
            seeds.append(r["dst_id"])

    # De-dup preserve order.
    return [s for s in dict.fromkeys(seeds) if s]

def bfs_from_seed_nodes(conn, seeds: List[str], depth: int, *, allowed_nodes: Optional[Set[str]] = None) -> Set[str]:
    visited: Set[str] = set(seeds)
    frontier: Set[str] = set(seeds)

    if allowed_nodes is not None:
        visited = {n for n in visited if n in allowed_nodes}
        frontier = {n for n in frontier if n in allowed_nodes}

    for _ in range(depth):
        if not frontier:
            break
        nxt: Set[str] = set()
        q_marks = ",".join(["?"] * len(frontier))
        # Undirected expansion: follow edges both directions.
        rows = conn.execute(
            f"SELECT e.src_id, e.dst_id FROM edges e WHERE e.valid_to IS NULL AND (e.src_id IN ({q_marks}) OR e.dst_id IN ({q_marks}))",
            tuple(frontier) + tuple(frontier),
        ).fetchall()
        for r in rows:
            a, b = r[0], r[1]
            if allowed_nodes is not None and a and a not in allowed_nodes:
                a = None
            if allowed_nodes is not None and b and b not in allowed_nodes:
                b = None
            if a and a not in visited:
                nxt.add(a)
            if b and b not in visited:
                nxt.add(b)
        nxt -= visited
        visited |= nxt
        frontier = nxt

    return visited


def expand_context_descendants(conn: sqlite3.Connection, roots: List[str], depth: int) -> Set[str]:
    """Return roots plus their descendants following Context -[CHILD_OF]-> Context."""
    allowed: Set[str] = set([r for r in roots if r])
    frontier: Set[str] = set(allowed)

    for _ in range(max(0, int(depth))):
        if not frontier:
            break
        q_marks = ",".join(["?"] * len(frontier))
        rows = conn.execute(
            f"SELECT e.src_id FROM edges e WHERE e.valid_to IS NULL AND e.rel_type='child_of' AND e.dst_id IN ({q_marks})",
            tuple(frontier),
        ).fetchall()
        nxt: Set[str] = set()
        for r in rows:
            cid = r[0]
            if cid and cid not in allowed:
                nxt.add(cid)
        allowed.update(nxt)
        frontier = nxt

    return allowed


def _parse_iso(ts: Optional[str]) -> Optional[datetime]:
    if not ts:
        return None
    try:
        # Python's fromisoformat doesn't like trailing Z.
        t = ts.replace("Z", "+00:00")
        dt = datetime.fromisoformat(t)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt
    except Exception:
        return None


def _recency_factor(ts: Optional[str], *, tau_days: float) -> float:
    if tau_days <= 0:
        return 1.0
    dt = _parse_iso(ts)
    if not dt:
        return 1.0
    age_days = max(0.0, (datetime.now(timezone.utc) - dt).total_seconds() / 86400.0)
    # exp(-age/tau)
    import math

    return float(math.exp(-age_days / float(tau_days)))


def _load_skill_defs(conn, skill_ids: List[str]) -> Dict[str, Dict[str, Any]]:
    if not skill_ids:
        return {}
    q_marks = ",".join(["?"] * len(skill_ids))
    rows = conn.execute(
        f"SELECT skill_id,title,description,tags_json,updated_at FROM skill_defs WHERE skill_id IN ({q_marks})",
        tuple(skill_ids),
    ).fetchall()
    out: Dict[str, Dict[str, Any]] = {}
    for r in rows:
        tags = None
        if r["tags_json"]:
            try:
                tags = json.loads(r["tags_json"]) or []
            except Exception:
                tags = []
        out[r["skill_id"]] = {
            "title": r["title"],
            "description": r["description"],
            "tags": tags or [],
            "updated_at": r["updated_at"],
        }
    return out


def _episode_brief(conn, episode_id: str) -> Optional[Dict[str, Any]]:
    row = conn.execute(
        "SELECT episode_id,timestamp,summary,task_text,outcome_status FROM episodes WHERE episode_id=?",
        (episode_id,),
    ).fetchone()
    if not row:
        return None
    return {
        "episode_id": row["episode_id"],
        "timestamp": row["timestamp"],
        "outcome": row["outcome_status"],
        "summary": row["summary"] or row["task_text"],
    }


def rank_skills(
    conn,
    neighborhood: Set[str],
    *,
    evidence_k: int,
    tau_days: float,
    allowed_nodes: Optional[Set[str]] = None,
) -> List[Dict[str, Any]]:
    if not neighborhood:
        return []

    q_marks = ",".join(["?"] * len(neighborhood))

    # Pull skills that are in the neighborhood.
    skill_rows = conn.execute(
        f"SELECT node_id, key AS skill_id, name FROM nodes WHERE node_type='skill' AND node_id IN ({q_marks})",
        tuple(neighborhood),
    ).fetchall()

    skills: List[Dict[str, Any]] = []
    for r in skill_rows:
        skills.append({"node_id": r["node_id"], "skill_id": r["skill_id"], "name": r["name"]})

    # Build a map from skill node_id -> incident edges within neighborhood.
    skill_node_ids = [s["node_id"] for s in skills]
    if not skill_node_ids:
        return []

    q2 = ",".join(["?"] * len(skill_node_ids))

    edge_rows = conn.execute(
        f"""
        SELECT e.edge_id, e.src_id, e.dst_id, e.rel_type, e.fact, e.weight, e.last_seen_at, e.evidence_json
          FROM edges e
         WHERE e.valid_to IS NULL
           AND (e.src_id IN ({q2}) OR e.dst_id IN ({q2}))
                """,
                tuple(skill_node_ids) + tuple(skill_node_ids),
    ).fetchall()

    by_skill: Dict[str, List[sqlite3.Row]] = {sid: [] for sid in skill_node_ids}
    for e in edge_rows:
        if e["src_id"] in by_skill:
            by_skill[e["src_id"]].append(e)
        if e["dst_id"] in by_skill and e["dst_id"] != e["src_id"]:
            by_skill[e["dst_id"]].append(e)

    # Score and attach evidence.
    skill_defs = _load_skill_defs(conn, [s["skill_id"] for s in skills])

    for s in skills:
        incident = by_skill.get(s["node_id"], [])
        score = 0.0
        scored_edges: List[Tuple[float, Dict[str, Any]]] = []
        for e in incident:
            if allowed_nodes is not None:
                a = e["src_id"]
                b = e["dst_id"]
                if (a and a not in allowed_nodes) or (b and b not in allowed_nodes):
                    continue
            w = float(e["weight"] or 0.0)
            rf = _recency_factor(e["last_seen_at"], tau_days=tau_days)
            contrib = w * rf
            score += contrib

            evidence_ids: List[str] = []
            if e["evidence_json"]:
                try:
                    evidence_ids = json.loads(e["evidence_json"]) or []
                except Exception:
                    evidence_ids = []

            scored_edges.append(
                (
                    abs(contrib),
                    {
                        "edge_id": e["edge_id"],
                        "rel_type": e["rel_type"],
                        "fact": e["fact"],
                        "weight": w,
                        "last_seen_at": e["last_seen_at"],
                        "recency_factor": rf,
                        "contrib": contrib,
                        "evidence": evidence_ids,
                    },
                )
            )

        scored_edges.sort(key=lambda t: t[0], reverse=True)
        top_edges = [e for _k, e in scored_edges[: max(0, evidence_k)]]

        # Flatten top episodes from top edges.
        ep_seen: Set[str] = set()
        top_eps: List[Dict[str, Any]] = []
        for e in top_edges:
            for eid in e.get("evidence") or []:
                if eid in ep_seen:
                    continue
                ep_seen.add(eid)
                br = _episode_brief(conn, eid)
                if br:
                    top_eps.append(br)
                if len(top_eps) >= max(0, evidence_k):
                    break
            if len(top_eps) >= max(0, evidence_k):
                break

        meta = skill_defs.get(s["skill_id"]) or {}
        s["score"] = float(score)
        s["title"] = meta.get("title")
        s["description"] = meta.get("description")
        s["tags"] = meta.get("tags") or []
        s["evidence"] = {"edges": top_edges, "episodes": top_eps}

    skills.sort(key=lambda x: float(x.get("score") or 0.0), reverse=True)
    return skills


def main() -> int:
    p = argparse.ArgumentParser(description="Hybrid search over AI Skills Memory (Chroma seed).")
    p.add_argument("--db", default=DEFAULT_DB_PATH)
    p.add_argument("--chroma", default=DEFAULT_CHROMA_PATH)
    p.add_argument("--query", required=True)
    p.add_argument("--top", type=int, default=10)
    p.add_argument("--depth", type=int, default=2, help="SQLite BFS depth from vector seeds")
    p.add_argument("--evidence", type=int, default=3, help="Top evidence items per skill")
    p.add_argument("--tau-days", type=float, default=30.0, help="Recency decay time constant (days)")
    p.add_argument("--seed-mode", choices=["chroma", "sql"], default="chroma", help="How to find initial seed nodes")
    p.add_argument("--context", action="append", default=[], help="Free-form context text snippet (repeatable)")
    p.add_argument("--context-text", action="append", default=[], help="Free-form context text snippet (repeatable)")
    p.add_argument("--context-top", type=int, default=3, help="How many context nodes to retrieve from vectors")
    p.add_argument("--context-depth", type=int, default=2, help="How far to expand from context-matched episodes")
    p.add_argument("--context-strict", action="store_true", help="If set, restrict retrieval to the inferred context neighborhood")
    p.add_argument("--json", action="store_true", help="Output JSON")
    args = p.parse_args()

    conn = connect_db(args.db)
    ensure_schema(conn)
    context_texts = [c for c in ((args.context or []) + (args.context_text or [])) if c]

    # Context resolution: retrieve nearest context nodes (node_type='context') via vectors
    # and build an allowed-node set from episodes linked to those contexts.
    context_node_ids: List[str] = []
    allowed_nodes: Optional[Set[str]] = None

    client = None
    if args.seed_mode != "sql" or context_texts:
        client = chromadb.PersistentClient(path=args.chroma)

    seeds: List[str]
    if args.seed_mode == "sql":
        seeds = sql_seed_nodes(conn, args.query, args.top)
    else:
        assert client is not None

        # Vector seeds across episodes, nodes, edges.
        seeds = []

        eps = flatten_query(chroma_query(client, CHROMA_COLLECTION_EPISODES, args.query, args.top))
        nodes = flatten_query(chroma_query(client, CHROMA_COLLECTION_NODES, args.query, args.top))
        edges = flatten_query(chroma_query(client, CHROMA_COLLECTION_EDGES, args.query, args.top))

        # Convert Chroma hits into SQLite node seeds:
        # - node hits: use node_id
        # - edge hits: include both endpoints
        # - episode hits: map to episode node (key = episode_id)
        for item_id, _dist, meta, _doc in nodes:
            if meta.get("kind") == "node":
                seeds.append(meta.get("id") or item_id)

        for item_id, _dist, meta, _doc in edges:
            edge_id = meta.get("id") or item_id
            row = conn.execute("SELECT src_id, dst_id FROM edges WHERE edge_id=?", (edge_id,)).fetchone()
            if row:
                seeds.append(row[0])
                seeds.append(row[1])

        for item_id, _dist, meta, _doc in eps:
            eid = meta.get("id") or item_id
            row = conn.execute("SELECT node_id FROM nodes WHERE node_type='episode' AND key=?", (eid,)).fetchone()
            if row:
                seeds.append(row[0])

        # De-dup preserve order.
        seeds = [s for s in dict.fromkeys(seeds) if s]

    if context_texts:
        # Ensure client exists for context vector search.
        if args.seed_mode == "sql":
            # Prefer deterministic exact match first; then prefer vector similarity (multilingual);
            # finally fall back to SQL LIKE/token matching.
            ctx_like_ids: List[str] = []
            for t in context_texts:
                # Prefer exact matches first so that parent-context queries (e.g. "біологія")
                # rely on CHILD_OF traversal, not substring luck.
                exact_rows = conn.execute(
                    "SELECT node_id FROM nodes WHERE node_type='context' AND (COALESCE(summary,'') = ? OR COALESCE(name,'') = ?) ORDER BY updated_at DESC LIMIT ?",
                    (t, t, max(1, int(args.context_top))),
                ).fetchall()
                if exact_rows:
                    for r in exact_rows:
                        ctx_like_ids.append(r[0])
                else:
                    # Vector-based context resolution (preferred): works across languages/terms.
                    vec_rows: List[str] = []
                    if client is not None:
                        hits = flatten_query(chroma_query(client, CHROMA_COLLECTION_NODES, t, max(1, int(args.context_top))))
                        for item_id, _dist, meta, _doc in hits:
                            if meta.get("kind") == "node" and meta.get("type") == "context":
                                vec_rows.append(meta.get("id") or item_id)
                    if vec_rows:
                        ctx_like_ids.extend(vec_rows)
                        continue

                    # SQL LIKE fallback (human-friendly but less robust than vectors).
                    q = f"%{t}%"
                    rows = conn.execute(
                        "SELECT node_id FROM nodes WHERE node_type='context' AND (COALESCE(summary,'') LIKE ? OR COALESCE(name,'') LIKE ?) ORDER BY updated_at DESC LIMIT ?",
                        (q, q, max(1, int(args.context_top))),
                    ).fetchall()
                    if rows:
                        for r in rows:
                            ctx_like_ids.append(r[0])
                        continue

                    # Last resort (still deterministic): token-based matching.
                    # Helps with minor format drift like:
                    # - "repo/alpha/ui" vs "repo alpha ui"
                    # - different separators or extra punctuation
                    toks = [x for x in re.findall(r"\w+", t, flags=re.UNICODE) if len(x) >= 2]
                    if len(toks) >= 2:
                        clauses = " AND ".join(["COALESCE(summary,'') LIKE ?"] * len(toks))
                        params = [f"%{tok}%" for tok in toks]
                        rows2 = conn.execute(
                            f"SELECT node_id FROM nodes WHERE node_type='context' AND {clauses} ORDER BY updated_at DESC LIMIT ?",
                            (*params, max(1, int(args.context_top))),
                        ).fetchall()
                        for r in rows2:
                            ctx_like_ids.append(r[0])
            context_node_ids = [x for x in dict.fromkeys(ctx_like_ids) if x]
        else:
            assert client is not None
            ctx_ids: List[str] = []
            for t in context_texts:
                hits = flatten_query(chroma_query(client, CHROMA_COLLECTION_NODES, t, max(1, int(args.context_top))))
                for item_id, _dist, meta, _doc in hits:
                    if meta.get("kind") == "node" and meta.get("type") == "context":
                        ctx_ids.append(meta.get("id") or item_id)
            context_node_ids = [x for x in dict.fromkeys(ctx_ids) if x]

        if context_node_ids:
            allowed_context_nodes = expand_context_descendants(conn, context_node_ids, depth=max(0, int(args.context_depth)))
            q_marks = ",".join(["?"] * len(allowed_context_nodes))
            ep_rows = conn.execute(
                f"""
                SELECT DISTINCT e.src_id
                  FROM edges e
                  JOIN nodes n ON n.node_id=e.src_id
                 WHERE e.valid_to IS NULL
                   AND e.rel_type='in_context'
                   AND e.dst_id IN ({q_marks})
                   AND n.node_type='episode'
                """,
                tuple(allowed_context_nodes),
            ).fetchall()
            ep_seed_nodes = [r[0] for r in ep_rows if r[0]]
            if ep_seed_nodes:
                # Expand a little from those episodes to include their neighbors (skills/outcomes/failure modes).
                allowed_nodes = bfs_from_seed_nodes(conn, ep_seed_nodes, depth=max(0, int(args.context_depth)))

    # If context text is provided, default to strict scoping (prototype usability).
    context_strict = bool(args.context_strict or bool(context_texts))
    neighborhood = bfs_from_seed_nodes(conn, seeds, depth=max(0, args.depth), allowed_nodes=(allowed_nodes if context_strict else None))
    skills = rank_skills(
        conn,
        neighborhood,
        evidence_k=max(0, args.evidence),
        tau_days=float(args.tau_days),
        allowed_nodes=(allowed_nodes if context_strict else None),
    )

    result = {
        "query": args.query,
        "seed_count": len(seeds),
        "neighborhood_size": len(neighborhood),
        "context_texts": context_texts,
        "context_node_count": len(context_node_ids),
        "context_strict": bool(context_strict),
        "context_allowed_nodes": len(allowed_nodes or set()),
        "skills": skills[: args.top],
    }

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0

    print(f"Seeds={len(seeds)} Neighborhood={len(neighborhood)}")
    for i, s in enumerate(skills[: args.top]):
        title = s.get("title") or ""
        suffix = f" — {title}" if title else ""
        print(f"{i+1}. {s['skill_id']} score={s['score']}{suffix}")
        eps = (s.get("evidence") or {}).get("episodes") or []
        for ep in eps[: max(0, args.evidence)]:
            ts = ep.get("timestamp") or ""
            out = ep.get("outcome") or ""
            summ = ep.get("summary") or ""
            print(f"    - {ep.get('episode_id')} {out} {ts} :: {summ}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
