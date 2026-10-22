#!/usr/bin/env bash
set -euo pipefail

# Always operate relative to this script's directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

usage() {
  echo "Usage: $0 <1|2> [acp-tck options...]"
  echo "Example: $0 2 --report-json reports/v2.json"
}

if [ $# -lt 1 ]; then
  usage
  exit 2
fi

case "$1" in
  1|2) PROTOCOL_VERSION="$1" ;;
  *) usage; exit 2 ;;
esac
AGENT_PROJECT="agent-v${PROTOCOL_VERSION}"
AGENT="acp-tck-agent-v${PROTOCOL_VERSION}"
shift

# acp-tck starts a fresh agent process per test, so build a launcher script once instead of going through Gradle
echo "[tck] Building ${AGENT}..."
"${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" -q ":e2e:tck:${AGENT_PROJECT}:installDist"
AGENT_BIN="${SCRIPT_DIR}/${AGENT_PROJECT}/build/install/${AGENT}/bin/${AGENT}"

# Relative paths in the delegated options (e.g. --report-json) resolve against this directory
cd "${SCRIPT_DIR}"
mkdir -p reports

# The agents keep credentials in memory only, so the authentication and logout flows are always safe to exercise.
# All remaining CLI parameters are delegated to acp-tck.
exec uv run --locked --project "${SCRIPT_DIR}/runner" acp-tck \
  --protocol-version "${PROTOCOL_VERSION}" \
  --auth-method tck \
  --allow-logout \
  "$@" \
  -- "${AGENT_BIN}"
