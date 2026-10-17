#!/usr/bin/env bash
set -euo pipefail

# Always operate relative to this script's directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${SCRIPT_DIR}/acp-tck"
REPO_URL="https://github.com/agentclientprotocol/acp-tck.git"
COMMIT_HASH="fa00ccdc13dfaa4b288a91877c1f3e468af4aa7d"

# 1) Clone the ACP TCK at the pinned commit unless it is already there
if [ -d "${REPO_DIR}" ]; then
  echo "[setup_tck] 'acp-tck' directory already exists at: ${REPO_DIR}"
  echo "[setup_tck] Will skip cloning but still run 'uv sync'."
else
  echo "[setup_tck] Cloning ${REPO_URL} into ${REPO_DIR} at commit ${COMMIT_HASH}"
  git init -q "${REPO_DIR}"
  git -C "${REPO_DIR}" remote add origin "${REPO_URL}"
  git -C "${REPO_DIR}" fetch -q --depth=1 origin "${COMMIT_HASH}"
  git -C "${REPO_DIR}" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi

# 2) Always sync the TCK's Python environment
echo "[setup_tck] Running 'uv sync' in ${REPO_DIR}..."
uv sync --project "${REPO_DIR}" --locked
echo "[setup_tck] Done."
