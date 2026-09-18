#!/usr/bin/env python3
"""Emit `path:line:text` for source lines that are code, not commentary.

The invariant checks in check-invariants.sh assert that certain vendor types and
constructs are absent from the source. Those names legitimately appear in
documentation that explains why they are forbidden, so matching raw file text
produces a false positive for every rule that is well documented — exactly the
wrong incentive. This strips comments and doc blocks first so the checks see
only code.
"""

import re
import sys
from pathlib import Path

SKIP_DIRS = {"_build", "deps", "node_modules", ".git", "build", ".gradle"}
SUFFIXES = {".ex", ".exs", ".kt", ".kts", ".heex", ".js"}

# Elixir heredoc doc attributes, and ~S/~s sigil variants.
DOC_OPEN = re.compile(r'^\s*@(module|type|typed|)doc\s+(~[sS])?"""')
DOC_CLOSE = re.compile(r'^\s*"""')


def code_lines(path: Path):
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return

    in_doc = False
    in_block_comment = False

    for number, line in enumerate(text.splitlines(), start=1):
        if in_doc:
            if DOC_CLOSE.match(line):
                in_doc = False
            continue

        if DOC_OPEN.match(line):
            in_doc = True
            continue

        # JavaScript and Kotlin block comments.
        if in_block_comment:
            if "*/" in line:
                in_block_comment = False
                line = line.split("*/", 1)[1]
            else:
                continue
        while "/*" in line:
            before, _, rest = line.partition("/*")
            if "*/" in rest:
                line = before + rest.split("*/", 1)[1]
            else:
                line = before
                in_block_comment = True
                break

        stripped = line.strip()
        if stripped.startswith("#") or stripped.startswith("//"):
            continue

        # Trailing line comments. Naive about `#` inside strings, which is
        # acceptable here: dropping a suffix can only hide a match on that same
        # line, and these checks look for declarations, not string contents.
        line = re.split(r"\s+(?:#|//)\s", line, maxsplit=1)[0]

        if line.strip():
            yield f"{path}:{number}:{line}"


def main(roots):
    for root in roots:
        base = Path(root)
        if not base.exists():
            continue
        paths = [base] if base.is_file() else sorted(base.rglob("*"))
        for path in paths:
            if not path.is_file() or path.suffix not in SUFFIXES:
                continue
            if SKIP_DIRS & set(path.parts):
                continue
            for entry in code_lines(path):
                print(entry)


if __name__ == "__main__":
    main(sys.argv[1:] or ["."])
