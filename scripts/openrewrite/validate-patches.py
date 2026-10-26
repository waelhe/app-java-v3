#!/usr/bin/env python3
"""Import-only patch validator for the OpenRewrite publication artifact.

The approved baseline recipe (``org.openrewrite.java.RemoveUnusedImports``) may
only add or remove complete Java import declarations. The write-enabled
``publish`` job of ``.github/workflows/openrewrite-apply.yml`` runs this
validator over every patch artifact produced by the read-only ``apply`` job,
whose Maven execution of external recipes is untrusted output by contract
(CodeRabbit review on PR #732: the patch must be treated as untrusted input).

Security invariants, each of which fails closed:

* **Line splitting matches ``git apply`` exactly.** Patch bytes are decoded
  and split on ``\\n`` only — the sole line terminator ``git apply``
  recognises. Python's ``str.splitlines()`` additionally splits on ``\\r``,
  ``\\x0b``, ``\\x0c``, ``\\x1c``-``\\x1e``, ``\\x85``, ``\\u2028`` and
  ``\\u2029``, which would let a crafted ``+import a.B;\\r static { ... }``
  line reach ``git apply`` as a single line while this validator judged it as
  two benign ones. The Java compiler treats ``\\r`` as a line terminator
  (JLS 3.4), so such a line injects code.
* **A stateful diff machine, not prefix filtering.** Only ``diff --git``,
  ``index ``, ``--- a/`` and ``+++ b/`` headers are accepted; binary hunks
  (``GIT binary patch``), mode changes, renames and any content outside a
  hunk are rejected.
* **Whole-line import matching.** Added or removed content must be a single
  complete Java import declaration over the entire line: horizontal
  whitespace (spaces/tabs) only, with an optional trailing carriage return
  for CRLF files — nothing may follow the declaration.

Usage:
    python3 scripts/openrewrite/validate-patches.py <publication-dir>

The publication directory is the artifact uploaded by the ``apply`` job:
``batches.json``, ``automatic-rewrite.patch`` and ``patches/batch-N.patch``.
Exit status 0 means every patch artifact contains import-only changes;
any other outcome is a refusal with a reason on stdout/stderr.
"""

import json
import re
import sys
from pathlib import Path

# A complete Java import declaration, matched over the FULL line. Horizontal
# whitespace only — \s would also accept \r/\f/\v mid-line, re-opening the
# git-apply line-splitting bypass. One optional trailing \r keeps CRLF
# patches (git's default on Windows checkouts) working.
IMPORT = re.compile(
    r"[ \t]*import[ \t]+(?:static[ \t]+)?"
    r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?"
    r"[ \t]*;[ \t]*\r?"
)


def validate_publication(pub: Path) -> int:
    """Validate every patch artifact in the publication directory."""
    plan = pub / "batches.json"
    if not plan.is_file():
        raise SystemExit(f"Missing batch plan: {plan}")
    batches = json.loads(plan.read_text(encoding="utf-8"))
    if not isinstance(batches, list) or not batches:
        raise SystemExit("Publication batch plan is empty.")
    for number, batch in enumerate(batches, 1):
        if not isinstance(batch, dict) or batch.get("number") != number:
            raise SystemExit(f"Malformed batch plan entry {number}.")

    patches = [pub / "automatic-rewrite.patch"] + [
        pub / "patches" / f"batch-{b['number']}.patch" for b in batches
    ]
    for patch in patches:
        if not patch.is_file() or patch.stat().st_size == 0:
            raise SystemExit(f"Missing or empty patch artifact: {patch}")
        state = None
        # git apply splits lines on \n only — the validator must do exactly
        # the same, never str.splitlines() (see module docstring).
        raw = patch.read_bytes().decode("utf-8", errors="strict")
        for number, line in enumerate(raw.split("\n"), 1):
            if line.startswith("diff --git "):
                state = "header"
                continue
            if state == "header":
                if line.startswith(("index ", "--- a/", "+++ b/")):
                    continue
                if line.startswith("@@ "):
                    state = "hunk"
                    continue
                raise SystemExit(f"Disallowed patch header in {patch}:{number}; refusing publication.")
            if state != "hunk":
                raise SystemExit(f"Content outside a diff in {patch}:{number}; refusing publication.")
            if line.startswith("@@ ") or line.startswith(" ") or line == "" or line.startswith("\\ No newline"):
                continue
            if line.startswith(("+", "-")):
                content = line[1:]
                if content.strip() and not IMPORT.fullmatch(content):
                    raise SystemExit(f"Non-import source change in {patch}:{number}; refusing publication.")
                continue
            raise SystemExit(f"Unexpected patch line in {patch}:{number}; refusing publication.")
    print(f"Validated import-only content for {len(patches)} patch artifact(s).")
    return 0


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: validate-patches.py <publication-dir>")
    raise SystemExit(validate_publication(Path(sys.argv[1])))


if __name__ == "__main__":
    main()
