#!/bin/bash
# Tier-0 verification gate (docs/11_HARDENING_PLAN.md): host-side cargo tests for the vendored
# protocol crates. No hardware needed. Run before packaging any APK.
#
# The vendored crates are path deps, not workspace members, so they must be tested from their own
# directories. receiver must be built with --no-default-features --features mic-uplink
# to dodge the eld-codec / fdk-aac feature-unification trap (docs/06 §5d).
# Repo-relative roots. This project lives at ccpa_custom/host/gm_ccpa, so the tools resolve both
# roots from their own location rather than hard-coding an absolute path — moving the checkout, or
# having a second one, must not silently build against the wrong tree.
GM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
CCPA_ROOT="$(cd "$GM_ROOT/../.." && pwd)"

set -euo pipefail
export PATH="$HOME/.cargo/bin:$PATH"

# Some minimal server images have no host `cc`, but Rust build scripts and procedural macros still
# need a Linux host linker. Use a user-local Zig fallback without changing the Android linker.
if ! command -v cc >/dev/null 2>&1; then
    ZIG="${ZIG:-$HOME/.local/opt/zig/zig}"
    [ -x "$ZIG" ] || { echo "FATAL: host cc is missing and Zig was not found at $ZIG" >&2; exit 1; }
    HOST_LINKER="$(mktemp -t gmccpa-zig-cc.XXXXXX)"
    printf '#!/bin/sh\nexec "%s" cc "$@"\n' "$ZIG" > "$HOST_LINKER"
    chmod 700 "$HOST_LINKER"
    export CC="$HOST_LINKER"
    export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="$HOST_LINKER"
fi

CCPA="$CCPA_ROOT/crates/vendor"

echo "=== Tier-0: receiver ==="
( cd "$CCPA/receiver" && cargo test --no-default-features --features mic-uplink )

echo "=== Tier-0: pairing ==="
( cd "$CCPA/pairing" && cargo test )

# ---- documentation budget -----------------------------------------------------------------------
# A hard cap, enforced rather than merely stated. Doc directories rot by accretion: each session adds
# a dated write-up, none are ever retired, and a later reader cannot tell which of five documents
# describing the same thing is current. The cap forces the choice — update in place, or retire
# something — at the moment the new file is written, which is the only moment anyone has the context
# to decide. Raw per-session captures belong in evidence/, not docs/.
DOCS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../docs" && pwd)"
DOC_COUNT=$(find "$DOCS_DIR" -maxdepth 1 -name '*.md' | wc -l | tr -d ' ')
DOC_MAX=10
echo "=== Tier-0: documentation budget ($DOC_COUNT/$DOC_MAX) ==="
if [ "$DOC_COUNT" -gt "$DOC_MAX" ]; then
    echo "[Tier-0] FAIL: docs/ holds $DOC_COUNT files, budget is $DOC_MAX."
    echo "  Fold the new material into an existing document, or retire one that is superseded."
    echo "  See 'Document policy' in docs/00_HANDOFF.md."
    exit 1
fi

# --- OCBM protocol conformance -------------------------------------------------------------------
#
# This app's Kotlin client and the box's `crates/ocbm-proto` are two ends of one wire. Nothing
# structural stops them drifting: the constants are hand-maintained on both sides, and a value that
# disagrees is not a compile error anywhere — it is a frame the other end misreads at runtime.
#
# ccpa_custom's checker parses ocbm-proto as canonical and diffs every client against it — this app's
# OcbmProto.kt is an app-owned fork (2026-09-11, no longer a symlink into CarlinkAndroid), so it is a
# real third client and the checker covers it with no arguments. A value disagreement is an error; a
# constant this app has not defined is a GAP and only fails for the core set (channel ids, CT_*
# opcodes, frame flags). The 2 gaps reported for THIS client today are correct and expected:
# CMD_VIEW_AREA (0x11) is a box-side opcode this app never drives — its view areas are requested by
# iOS directly over AirPlay (CarPlayActivity "requestViewArea"), never through the box's CH_INPUT —
# and F_BOTH is a client-side convenience alias for F_SOM|F_EOM that ocbm-proto has no need to name.
# (The pre-fork count quoted here was 57, from back when this path resolved through CarlinkAndroid's
# copy and the checker's totals covered every client at once.)
#
# SKIPPED, not failed, when the checker is absent (a gm_ccpa subtree exported on its own must stay
# buildable). No root argument: the checker rejects one since 2026-09-11.
PROTO_CHECK="$CCPA_ROOT/tools/proto_check.py"
if [ -x "$PROTO_CHECK" ]; then
    echo "=== Tier-0: OCBM protocol conformance ==="
    if ! python3 "$PROTO_CHECK" > /tmp/proto_check.out 2>&1; then
        echo "[Tier-0] FAIL: OCBM client disagrees with crates/ocbm-proto."
        grep -E "^(error|ERROR)" /tmp/proto_check.out | head -20
        echo "  Full output: /tmp/proto_check.out"
        echo "  ocbm-proto is canonical. Fix the client, not the spec."
        exit 1
    fi
    tail -1 /tmp/proto_check.out
else
    echo "=== Tier-0: OCBM protocol conformance (SKIPPED — no ccpa_custom checkout) ==="
fi

echo "[Tier-0] all green"
