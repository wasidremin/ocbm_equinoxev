#!/usr/bin/env python3
"""Check every OCBM client against the canonical constant table.

`crates/ocbm-proto/src/lib.rs` is the protocol. The GM app's Kotlin client restates those
constants by hand, and nothing else verifies that the restatement agrees. When this repo still
carried the macOS Swift client and the CarlinkAndroid Kotlin client (both removed 2026-10-27), they
did not: CT_PROJ_MODE, CT_BOX_HEALTH, the BH_* health bits, F_REPLAY and the whole CH_FILE opcode
set reached the box and the gm_ccpa client while the other two never learned them (found
2026-08-31).

A value that disagrees is a wire bug: two endpoints of one link mean different things by the same
byte. That is an ERROR here. A constant a client simply does not define yet is drift, not a bug
today -- reported as a gap, and an error only for the CORE set (channel ids, CT_* opcodes, frame
flags), which every client must be able to name even where it does not act on the opcode.

Usage:  tools/proto_check.py [--strict]
        --strict  treat every gap as an error, not just the core set.

The client lives in this repo and is always checked. A positional path is rejected rather than
ignored: the only thing it could name is a second checkout, i.e. a stale copy.
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CANON = REPO / "crates/ocbm-proto/src/lib.rs"
CLIENTS = {
    "kotlin (gm_ccpa)": REPO / "host/gm_ccpa/netprobe_app/app/src/main/java/wasidremin/gmccpa/ocbm/OcbmProto.kt",
}

# Prefixes every client must be able to name: the channel map, the control opcodes, the frame flags.
CORE = ("CH_", "CT_", "F_", "MAGIC", "HDR_LEN", "MAX_PAYLOAD")
# Constants that are box-internal or language-specific and are not part of any client's contract.
EXEMPT = {"CRC32_INIT", "KEYFRAME_MIN_INTERVAL_MS"}


def _int(v: str):
    v = v.split("//")[0].strip().rstrip(",")
    v = re.sub(r"\.to(Byte|Int|Short|UInt8|UInt16|UInt32)\(\)", "", v)
    v = re.sub(r"\b(u8|u16|u32|usize|UInt8|UInt16|UInt32|Int|Byte)\b", "", v)
    v = v.replace("_", "").strip()
    try:
        return int(v, 0)
    except ValueError:
        return None


def canonical():
    t = CANON.read_text()
    out = {}
    for m in re.finditer(r"pub const ([A-Z][A-Z0-9_]*)\s*:[^=]+=\s*([^;]+);", t):
        v = _int(m.group(2))
        if v is not None:
            out[m.group(1)] = v
    return out


def _container(text: str, opener: str) -> str:
    """The body of the protocol container only.

    The client defines a SECOND object beside the protocol one -- `object Mfi` -- and
    `Mfi.HDR_LEN` is 3 while the frame header is 16. Reading the file flat
    reports that as a wire divergence, which is exactly the kind of false alarm that gets a checker
    switched off. Cut at the next top-level declaration instead.
    """
    i = text.index(opener)
    rest = text[i + len(opener):]
    end = re.search(r"\n(?:object|struct|final class|enum|extension|class) ", rest)
    return rest[: end.start()] if end else rest


def kotlin(p: Path):
    t = _container(p.read_text(), "object Ocbm {")
    return {m.group(1): _int(m.group(2)) for m in re.finditer(r"const val ([A-Z][A-Za-z0-9_]*)\s*:[^=]+=\s*([^\n]+)", t)}


def kotlin_container(p: Path, opener: str):
    """Constants from a named Kotlin object (used for `object Mfi`)."""
    txt = p.read_text()
    if opener not in txt:
        return {}
    body = _container(txt, opener)
    return {m.group(1): _int(m.group(2)) for m in re.finditer(r"const val ([A-Z][A-Za-z0-9_]*)\s*:[^=]+=\s*([^\n]+)", body)}


def main() -> int:
    argv = [a for a in sys.argv[1:] if a != "--strict"]
    strict = "--strict" in sys.argv
    if argv:
        print(f"unexpected argument {argv[0]!r}: every client is in-tree and checked by default "
              "(the gm_ccpa root argument was retired 2026-09-11)", file=sys.stderr)
        return 2
    clients = dict(CLIENTS)

    canon = canonical()
    # The MFi sub-protocol lives in its own container in the clients (`object Mfi`), with the `MFI_`
    # prefix dropped. Check it separately rather than letting `Mfi.HDR_LEN` (3) collide with the
    # frame header (16) -- the collision that made the first version of this checker cry wolf.
    mfi_canon = {k[len("MFI_"):]: v for k, v in canon.items() if k.startswith("MFI_")}
    canon = {k: v for k, v in canon.items() if not k.startswith("MFI_")}
    errors, gaps = [], []
    for label, path in clients.items():
        if not path.is_file():
            errors.append(f"{label}: missing file {path}")
            continue
        got = kotlin(path)
        for name, want in sorted(canon.items()):
            if name in EXEMPT:
                continue
            if name not in got:
                (errors if (strict or name.startswith(CORE)) else gaps).append(
                    f"{label}: does not define {name} (canonical {want:#04x})")
            elif got[name] != want:
                errors.append(
                    f"{label}: {name} = {got[name]:#04x}, canonical says {want:#04x}")
        if mfi_canon:
            mfi_got = kotlin_container(path, "object Mfi {")
            for name, want in sorted(mfi_canon.items()):
                if name not in mfi_got:
                    gaps.append(f"{label}: Mfi.{name} not defined (canonical MFI_{name} = {want:#04x})")
                elif mfi_got[name] != want:
                    errors.append(f"{label}: Mfi.{name} = {mfi_got[name]:#04x}, canonical MFI_{name} says {want:#04x}")
        extra = sorted(n for n in got if n not in canon and n.startswith(CORE))
        for n in extra:
            gaps.append(f"{label}: defines {n} = {got[n]:#04x}, which is not in ocbm-proto")
        print(f"{label}: {len(got)} constants checked against {len(canon)} canonical")

    for g in gaps:
        print(f"  gap: {g}")
    if errors:
        print(f"\n{len(errors)} protocol divergence(s):", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        return 1
    print("OK — every client agrees with crates/ocbm-proto" + (f" ({len(gaps)} non-core gaps)" if gaps else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
