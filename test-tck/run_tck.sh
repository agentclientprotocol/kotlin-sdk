#!/usr/bin/env bash
set -euo pipefail

# Always operate relative to this script's directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_DIR="${SCRIPT_DIR}/acp-tck"

usage() {
  echo "Usage: $0 <v1|v2> [acp-tck options...]"
  echo "Example: $0 v2 --report-json reports/v2.json"
}

if [ $# -lt 1 ]; then
  usage
  exit 2
fi

case "$1" in
  v1) PROTOCOL_VERSION=1 ;;
  v2) PROTOCOL_VERSION=2 ;;
  *) usage; exit 2 ;;
esac
AGENT="acp-tck-agent-$1"
shift

if [ ! -d "${REPO_DIR}" ]; then
  echo "[run_tck] Expected directory not found: ${REPO_DIR}"
  echo "[run_tck] Please run setup_tck.sh first to clone and prepare the ACP TCK."
  exit 1
fi

# acp-tck starts a fresh agent process per test, so build a launcher script once instead of going through Gradle
echo "[run_tck] Building ${AGENT}..."
"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" -q ":test-tck:${AGENT}:installDist"
AGENT_BIN="${SCRIPT_DIR}/${AGENT}/build/install/${AGENT}/bin/${AGENT}"

# Relative paths in the delegated options (e.g. --report-json) resolve against this directory
cd "${SCRIPT_DIR}"
mkdir -p reports

# The agents keep credentials in memory only, so the authentication and logout flows are always safe to exercise.
# All remaining CLI parameters are delegated to acp-tck.
exec uv run --project "${REPO_DIR}" acp-tck \
  --protocol-version "${PROTOCOL_VERSION}" \
  --auth-method tck \
  --allow-logout \
  "$@" \
  -- "${AGENT_BIN}"
