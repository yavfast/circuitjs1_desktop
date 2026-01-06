#!/usr/bin/env python3

import json
import os
import subprocess
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parents[2]
MEM_PY = ROOT / "scripts" / "ai_skills_memory" / "mem.py"
SEED_PY = ROOT / "scripts" / "ai_skills_memory" / "seed_test_db.py"

TEST_DB = os.environ.get("AI_SKILLS_TEST_DB", str(ROOT / "ai_memory" / "ai_skills_memory_test.sqlite"))
TEST_CHROMA = os.environ.get("AI_SKILLS_TEST_CHROMA", str(ROOT / "ai_memory" / "ai_skills_chroma_test"))


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat()


def run_cmd(argv: List[str], env: Optional[Dict[str, str]] = None) -> Tuple[int, str, str]:
    p = subprocess.run(argv, env=env, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def mem_search(
    query: str,
    *,
    top: int = 10,
    depth: int = 2,
    evidence: int = 2,
    tau_days: float = 30.0,
    seed_mode: str = "chroma",
    contexts: Optional[List[str]] = None,
    context_strict: bool = False,
) -> Dict[str, Any]:
    argv = [
        sys.executable,
        str(MEM_PY),
        "search",
        "--db",
        TEST_DB,
        "--chroma",
        TEST_CHROMA,
        "--query",
        query,
        "--top",
        str(top),
        "--depth",
        str(depth),
        "--evidence",
        str(evidence),
        "--tau-days",
        str(tau_days),
        "--seed-mode",
        seed_mode,
    ]
    for c in contexts or []:
        argv.extend(["--context", c])
    if context_strict:
        argv.append("--context-strict")
    argv.append("--json")
    code, out, err = run_cmd(argv)
    if code != 0:
        raise RuntimeError(f"search failed: code={code} stderr={err.strip()}")
    return json.loads(out)


def mem_ingest(episode: Dict[str, Any]) -> str:
    proc = subprocess.run(
        [
            sys.executable,
            str(MEM_PY),
            "ingest",
            "--db",
            TEST_DB,
            "--chroma",
            TEST_CHROMA,
            "--stdin",
        ],
        input=json.dumps(episode, ensure_ascii=False),
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"ingest failed: code={proc.returncode} stderr={proc.stderr.strip()}")
    return proc.stdout.strip()


@dataclass
class Check:
    name: str
    ok: bool
    detail: str


def assert_true(name: str, cond: bool, detail: str) -> Check:
    return Check(name=name, ok=bool(cond), detail=detail)


def top_skill_ids(res: Dict[str, Any]) -> List[str]:
    return [x.get("skill_id") for x in (res.get("skills") or []) if x.get("skill_id")]


def find_skill(res: Dict[str, Any], skill_id: str) -> Optional[Dict[str, Any]]:
    for s in res.get("skills") or []:
        if s.get("skill_id") == skill_id:
            return s
    return None


def main() -> int:
    # Seed test DB fresh each run so corpus changes (e.g., context hierarchy) take effect.
    code, out, err = run_cmd([sys.executable, str(SEED_PY), "--reset", "--db", TEST_DB, "--chroma", TEST_CHROMA])
    if code != 0:
        print(err, file=sys.stderr)
        return 2

    checks: List[Check] = []

    run_id = uuid.uuid4().hex[:10]
    now = datetime.now(timezone.utc)

    # 1) New fact/skill + new links
    tok1 = f"TESTTOKEN:{run_id}:new_skill"
    ep_new = {
        "timestamp": _iso(now),
        "task_text": f"{tok1} Add deterministic SQL seed fallback for search",
        "summary": f"{tok1} Implemented fallback seed mode for stable testing.",
        "used_skills": ["test-harness", "sqlite-graph"],
        "touched_artifacts": ["scripts/ai_skills_memory/search.py"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    eid1 = mem_ingest(ep_new)

    q1 = tok1
    r1 = mem_search(q1, top=10, depth=2, evidence=2, seed_mode="sql")
    top1 = top_skill_ids(r1)
    checks.append(assert_true("new_skill_and_links_present", "test-harness" in top1, f"top={top1}"))

    # 2) Confirmation: re-ingest another success using existing skill should not remove it and should show evidence.
    tok2 = f"TESTTOKEN:{run_id}:confirm"
    ep_confirm = {
        "timestamp": _iso(now + timedelta(minutes=1)),
        "task_text": f"{tok2} Confirm CLI wrapper works",
        "summary": f"{tok2} Ran ai_mem.sh init and ingest successfully again.",
        "used_skills": ["python-cli"],
        "touched_artifacts": ["ai_mem.sh"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    eid2 = mem_ingest(ep_confirm)

    r2 = mem_search(tok2, top=10, depth=2, evidence=3, seed_mode="sql")
    s2 = find_skill(r2, "python-cli")
    has_evidence = bool(((s2 or {}).get("evidence") or {}).get("episodes"))
    checks.append(assert_true("confirmation_has_evidence", has_evidence, f"python-cli evidence={s2.get('evidence') if s2 else None}"))

    # 3) Refutation/weight decrease: add a failure episode for a skill; expect score decrease (relative).
    # We compare before/after for a query that targets the skill.
    tok3 = f"TESTTOKEN:{run_id}:refute"
    before = mem_search(tok3, top=10, depth=2, evidence=1, tau_days=365.0, seed_mode="sql")
    s_before = find_skill(before, "ollama-embeddings")
    score_before = float((s_before or {}).get("score") or 0.0)

    ep_fail = {
        "timestamp": _iso(now + timedelta(minutes=2)),
        "task_text": f"{tok3} Embeddings timeout regression",
        "summary": f"{tok3} Ollama embeddings started timing out after a change.",
        "used_skills": ["ollama-embeddings"],
        "touched_artifacts": ["scripts/ai_skills_memory/common.py"],
        "outcome": {"status": "failure"},
        "errors": ["Ollama /api/embeddings timeout"],
    }
    eid3 = mem_ingest(ep_fail)

    after = mem_search(tok3, top=10, depth=2, evidence=1, tau_days=365.0, seed_mode="sql")
    s_after = find_skill(after, "ollama-embeddings")
    score_after = float((s_after or {}).get("score") or 0.0)
    checks.append(assert_true("refutation_decreases_score", score_after <= score_before + 1e-9, f"before={score_before} after={score_after}"))

    # 4) Staleness/recency: with a very small tau_days, the most recent skill should dominate.
    tok4 = f"TESTTOKEN:{run_id}:recency"
    ep_recent = {
        "timestamp": _iso(now + timedelta(minutes=3)),
        "task_text": f"{tok4} Hotfix search evidence formatting",
        "summary": f"{tok4} Patched evidence output formatting for readability.",
        "used_skills": ["python-cli"],
        "touched_artifacts": ["scripts/ai_skills_memory/search.py"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    eid4 = mem_ingest(ep_recent)

    # Query matches older episodes too, but tau_days makes recency matter more.
    r4 = mem_search(tok4, top=5, depth=2, evidence=1, tau_days=0.01, seed_mode="sql")
    top4 = top_skill_ids(r4)
    checks.append(assert_true("recency_affects_ranking", len(top4) > 0 and top4[0] in ("python-cli", "sqlite-graph", "chromadb"), f"top={top4}"))

    # 5) New connection: add artifact-only link and verify neighborhood expansion can still surface skill via shared episode node.
    tok5 = f"TESTTOKEN:{run_id}:artifact"
    ep_art = {
        "timestamp": _iso(now + timedelta(minutes=4)),
        "task_text": f"{tok5} Touch new artifact without skill",
        "summary": f"{tok5} Updated documentation file for onboarding snapshot.",
        "used_skills": [],
        "touched_artifacts": ["ai_skills/skills_graph_concept.md"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    eid5 = mem_ingest(ep_art)

    # Second episode confirms a skill while touching the same artifact.
    ep_art2 = {
        "timestamp": _iso(now + timedelta(minutes=5)),
        "task_text": f"{tok5} Update same doc and run CLI",
        "summary": f"{tok5} Confirmed doc changes and re-ran CLI wrapper.",
        "used_skills": ["python-cli"],
        "touched_artifacts": ["ai_skills/skills_graph_concept.md"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    _eid6 = mem_ingest(ep_art2)

    r5 = mem_search(tok5, top=10, depth=2, evidence=1, seed_mode="sql")
    checks.append(assert_true("artifact_query_surfaces_skill", "python-cli" in top_skill_ids(r5), f"skills={top_skill_ids(r5)}"))

    # 6) Smoke: chroma mode should at least return something (non-deterministic ranking, so only check non-empty).
    smoke = mem_search(tok4, top=5, depth=2, evidence=1, seed_mode="chroma")
    checks.append(assert_true("chroma_smoke_non_empty", len(top_skill_ids(smoke)) > 0, f"skills={top_skill_ids(smoke)}"))

    # 7) Seeded corpus: ambiguous tokens should resolve differently per context.
    rg_nat = mem_search("AMBIG:GREEN", top=10, depth=2, evidence=1, seed_mode="sql", contexts=["природа"], context_strict=True)
    rg_pol = mem_search("AMBIG:GREEN", top=10, depth=2, evidence=1, seed_mode="sql", contexts=["політика"], context_strict=True)
    checks.append(assert_true("seed_ambig_green_nature", "domain-nature" in top_skill_ids(rg_nat) and "domain-politics" not in top_skill_ids(rg_nat), f"top={top_skill_ids(rg_nat)}"))
    checks.append(assert_true("seed_ambig_green_politics", "domain-politics" in top_skill_ids(rg_pol) and "domain-nature" not in top_skill_ids(rg_pol), f"top={top_skill_ids(rg_pol)}"))

    rv_bio = mem_search("AMBIG:VIRUS", top=10, depth=2, evidence=1, seed_mode="sql", contexts=["біологія"], context_strict=True)
    rv_cyb = mem_search("AMBIG:VIRUS", top=10, depth=2, evidence=1, seed_mode="sql", contexts=["кібербезпека"], context_strict=True)
    checks.append(assert_true("seed_ambig_virus_biology", "domain-biology" in top_skill_ids(rv_bio) and "domain-cyber" not in top_skill_ids(rv_bio), f"top={top_skill_ids(rv_bio)}"))
    checks.append(assert_true("seed_ambig_virus_cyber", "domain-cyber" in top_skill_ids(rv_cyb) and "domain-biology" not in top_skill_ids(rv_cyb), f"top={top_skill_ids(rv_cyb)}"))

    # 8) External context: same query token, different contexts => different skills.
    tok6 = f"TESTTOKEN:{run_id}:context"
    ep_ctx_a = {
        "timestamp": _iso(now + timedelta(minutes=6)),
        "contexts": ["repo/alpha"],
        "task_text": f"{tok6} Context alpha: CLI work",
        "summary": f"{tok6} Updated CLI in alpha repo.",
        "used_skills": ["python-cli"],
        "touched_artifacts": ["scripts/ai_skills_memory/mem.py"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    _eid_ctx_a = mem_ingest(ep_ctx_a)

    ep_ctx_b = {
        "timestamp": _iso(now + timedelta(minutes=7)),
        "contexts": ["repo/beta"],
        "task_text": f"{tok6} Context beta: GWT build",
        "summary": f"{tok6} Fixed GWT build in beta repo.",
        "used_skills": ["gwt-build"],
        "touched_artifacts": ["pom.xml"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    _eid_ctx_b = mem_ingest(ep_ctx_b)

    r6a = mem_search(tok6, top=10, depth=2, evidence=1, seed_mode="sql", contexts=["repo/alpha"], context_strict=True)
    top6a = top_skill_ids(r6a)
    checks.append(assert_true("context_alpha_scopes_results", "python-cli" in top6a and "gwt-build" not in top6a, f"top={top6a}"))

    r6b = mem_search(tok6, top=10, depth=2, evidence=1, seed_mode="sql", contexts=["repo/beta"], context_strict=True)
    top6b = top_skill_ids(r6b)
    checks.append(assert_true("context_beta_scopes_results", "gwt-build" in top6b and "python-cli" not in top6b, f"top={top6b}"))

    # 9) Similar-but-not-identical contexts:
    # - hierarchical scope: "repo/alpha" should include descendants like "repo/alpha/ui"
    # - separator drift: "repo alpha ui" should still resolve to "repo/alpha/ui" in SQL mode (token fallback)
    tok7 = f"TESTTOKEN:{run_id}:ctxsimilar"
    ep_ctx_sim = {
        "timestamp": _iso(now + timedelta(minutes=8)),
        "contexts": ["repo/alpha/ui"],
        "task_text": f"{tok7} Context alpha/ui: CLI work",
        "summary": f"{tok7} Implemented CLI changes in alpha/ui area.",
        "used_skills": ["python-cli"],
        "touched_artifacts": ["scripts/ai_skills_memory/mem.py"],
        "outcome": {"status": "success"},
        "errors": [],
    }
    _eid_ctx_sim = mem_ingest(ep_ctx_sim)

    r7_parent = mem_search(tok7, top=10, depth=2, evidence=1, seed_mode="sql", contexts=["repo/alpha"], context_strict=True)
    top7_parent = top_skill_ids(r7_parent)
    checks.append(assert_true("context_parent_includes_descendants", "python-cli" in top7_parent, f"top={top7_parent}"))

    r7_drift = mem_search(tok7, top=10, depth=2, evidence=1, seed_mode="sql", contexts=["repo alpha ui"], context_strict=True)
    top7_drift = top_skill_ids(r7_drift)
    checks.append(assert_true("context_separator_drift_matches", "python-cli" in top7_drift, f"top={top7_drift}"))

    # Report
    failed = [c for c in checks if not c.ok]
    print("Functional test run:")
    print(f"  test_db={TEST_DB}")
    print(f"  test_chroma={TEST_CHROMA}")
    print(f"  run_id={run_id}")
    print(f"  ingested=[{eid1},{eid2},{eid3},{eid4},{eid5},...]")
    for c in checks:
        status = "OK" if c.ok else "FAIL"
        print(f"- {status}: {c.name} :: {c.detail}")

    if failed:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
