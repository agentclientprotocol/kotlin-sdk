#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  echo "Usage: $0 <1|2>" >&2
  echo "Builds the pinned Rust SDK testy agent for the given protocol version and prints its absolute path on the last stdout line." >&2
}

if [ $# -ne 1 ]; then
  usage
  exit 2
fi

case "$1" in
  1|2) PROTOCOL_VERSION="$1" ;;
  *) usage; exit 2 ;;
esac

RUST_SDK_URL="https://github.com/agentclientprotocol/rust-sdk"
RUST_SDK_REV="$(tr -d '[:space:]' < "${SCRIPT_DIR}/rust-sdk.rev")"

# One checkout per protocol version, so parallel runs never share a working tree or a cargo target directory
CHECKOUT="${SCRIPT_DIR}/.sut/rust-sdk-v${PROTOCOL_VERSION}"

# Stdout is reserved for the final binary path, so all tool output goes to stderr
if [ "$(git -C "${CHECKOUT}" rev-parse HEAD 2>/dev/null || true)" != "${RUST_SDK_REV}" ]; then
  echo "[rust-agent] Fetching rust-sdk ${RUST_SDK_REV}..." >&2
  mkdir -p "${CHECKOUT}"
  git -C "${CHECKOUT}" init --quiet >&2
  # Fetch by SHA: a shallow fetch avoids cloning the whole history
  git -C "${CHECKOUT}" fetch --quiet --depth 1 "${RUST_SDK_URL}" "${RUST_SDK_REV}" >&2
  git -C "${CHECKOUT}" checkout --quiet --detach FETCH_HEAD >&2
fi

# --locked keeps the build on the dependency versions pinned by the SDK's Cargo.lock
CARGO_ARGS=(build --locked -p agent-client-protocol-test --bin testy --no-default-features)
if [ "${PROTOCOL_VERSION}" = "2" ]; then
  CARGO_ARGS+=(--features unstable_protocol_v2 --target-dir "${CHECKOUT}/target-v2")
  TARGET_DIR="${CHECKOUT}/target-v2"
else
  TARGET_DIR="${CHECKOUT}/target"
fi

echo "[rust-agent] Building testy for protocol v${PROTOCOL_VERSION}..." >&2
(cd "${CHECKOUT}" && cargo "${CARGO_ARGS[@]}" >&2)

AGENT_BIN="${TARGET_DIR}/debug/testy"
if [ ! -x "${AGENT_BIN}" ]; then
  echo "[rust-agent] Expected executable not found: ${AGENT_BIN}" >&2
  exit 1
fi

echo "${AGENT_BIN}"
