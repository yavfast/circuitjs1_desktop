#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$ROOT_DIR/.venv"
PY="$VENV_DIR/bin/python"
PIP="$VENV_DIR/bin/pip"

DB_DEFAULT="$ROOT_DIR/ai_memory/ai_skills_memory.sqlite"
CHROMA_DEFAULT="$ROOT_DIR/ai_memory/ai_skills_chroma"

export AI_SKILLS_DB="${AI_SKILLS_DB:-$DB_DEFAULT}"
export AI_SKILLS_CHROMA="${AI_SKILLS_CHROMA:-$CHROMA_DEFAULT}"
export OLLAMA_URL="${OLLAMA_URL:-http://127.0.0.1:11434}"
export AI_EMBED_MODEL="${AI_EMBED_MODEL:-nomic-embed-text}"

usage() {
  cat <<EOF
Usage:
  ./ai_mem.sh <mem.py args...>

Examples:
  ./ai_mem.sh init
  ./ai_mem.sh ingest --json /path/to/episode.json
  ./ai_mem.sh search --query "fix gwt compilation"
  ./ai_mem.sh export-onboarding --out ./ai_skills/onboarding_snapshot.yaml

Notes:
  - Uses local venv at ./.venv
  - Persists DB at $AI_SKILLS_DB
  - Persists Chroma at $AI_SKILLS_CHROMA
  - Uses Ollama at $OLLAMA_URL with model $AI_EMBED_MODEL
EOF
}

ensure_venv() {
  local need_create=0

  if [[ ! -x "$PY" ]]; then
    need_create=1
  else
    if ! "$PY" -c 'import sys; print(sys.executable)' >/dev/null 2>&1; then
      need_create=1
    fi
  fi

  if [[ "$need_create" -eq 0 ]]; then
    if [[ ! -x "$PIP" ]] || ! "$PIP" --version >/dev/null 2>&1; then
      need_create=1
    fi
  fi

  if [[ "$need_create" -eq 1 ]]; then
    echo "[ai_mem] (Re)creating venv: $VENV_DIR" >&2
    rm -rf "$VENV_DIR"
    python3 -m venv "$VENV_DIR"
  fi

  local req_file="$ROOT_DIR/scripts/ai_skills_memory/requirements.txt"
  local cache_dir="$ROOT_DIR/ai_memory"
  local stamp_file="$cache_dir/.ai_mem_deps.stamp"
  mkdir -p "$cache_dir"

  local req_hash
  req_hash="$($PY -c 'import hashlib,sys; p=sys.argv[1]; print(hashlib.sha256(open(p,"rb").read()).hexdigest())' "$req_file")"
  local py_ver
  py_ver="$($PY -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
  local want_stamp="py=$py_ver req=$req_hash"

  if [[ "${AI_MEM_FORCE_PIP:-0}" == "1" ]]; then
    rm -f "$stamp_file"
  fi

  if [[ -f "$stamp_file" ]] && grep -qxF "$want_stamp" "$stamp_file"; then
    return 0
  fi

  echo "[ai_mem] Installing/updating dependencies in venv" >&2
  "$PIP" -q install --upgrade pip >/dev/null
  "$PIP" -q install -r "$req_file" >/dev/null
  printf '%s\n' "$want_stamp" > "$stamp_file"
}

ensure_ollama() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "[ai_mem] curl not found; skipping Ollama health check" >&2
    return 0
  fi

  if curl -fsS "$OLLAMA_URL/api/version" >/dev/null 2>&1; then
    return 0
  fi

  if command -v ollama >/dev/null 2>&1; then
    echo "[ai_mem] Ollama not reachable; trying to start 'ollama serve'" >&2
    nohup ollama serve >/tmp/ollama-serve.log 2>&1 &
    sleep 1
  fi

  if ! curl -fsS "$OLLAMA_URL/api/version" >/dev/null 2>&1; then
    echo "[ai_mem] ERROR: Ollama is not reachable at $OLLAMA_URL" >&2
    echo "[ai_mem] Start it with: ollama serve" >&2
    return 2
  fi

  if command -v ollama >/dev/null 2>&1; then
    local models
    models="$(ollama list 2>/dev/null | awk 'NR>1 {print $1}')"

    if ! printf '%s\n' "$models" | grep -qx "$AI_EMBED_MODEL"; then
      # Prefer an already-installed embedding model to avoid heavy downloads.
      for candidate in "bge-m3:latest" "dengcao/Qwen3-Embedding-0.6B:Q8_0"; do
        if printf '%s\n' "$models" | grep -qx "$candidate"; then
          echo "[ai_mem] NOTE: AI_EMBED_MODEL '$AI_EMBED_MODEL' not found; using '$candidate'" >&2
          export AI_EMBED_MODEL="$candidate"
          return 0
        fi
      done

      echo "[ai_mem] Pulling embedding model: $AI_EMBED_MODEL" >&2
      ollama pull "$AI_EMBED_MODEL"
    fi
  fi
}

maybe_init_storage() {
  mkdir -p "$(dirname "$AI_SKILLS_DB")" "$AI_SKILLS_CHROMA"

  if [[ ! -f "$AI_SKILLS_DB" ]]; then
    echo "[ai_mem] Initializing storage (first run)" >&2
    "$PY" "$ROOT_DIR/scripts/ai_skills_memory/mem.py" init --db "$AI_SKILLS_DB" --chroma "$AI_SKILLS_CHROMA"
  fi
}

main() {
  if [[ $# -eq 0 ]]; then
    usage
    exit 1
  fi

  ensure_venv
  ensure_ollama

  cmd="$1"
  if [[ "$cmd" != "init" ]]; then
    maybe_init_storage
  fi

  case "$cmd" in
    init|ingest|search)
      exec "$PY" "$ROOT_DIR/scripts/ai_skills_memory/mem.py" "$@" --db "$AI_SKILLS_DB" --chroma "$AI_SKILLS_CHROMA"
      ;;
    export-onboarding|skill)
      exec "$PY" "$ROOT_DIR/scripts/ai_skills_memory/mem.py" "$@" --db "$AI_SKILLS_DB"
      ;;
    -h|--help|help)
      exec "$PY" "$ROOT_DIR/scripts/ai_skills_memory/mem.py" "$@"
      ;;
    *)
      # Unknown subcommand; run as-is (lets argparse print the proper error).
      exec "$PY" "$ROOT_DIR/scripts/ai_skills_memory/mem.py" "$@"
      ;;
  esac
}

main "$@"
