#!/usr/bin/env python3
"""
apply-fixes.py

Applies the fixes produced by Claude (see schemas/claude-fix-schema.json)
atomically: either every fix matches and applies cleanly, or nothing is
written to disk at all.

Usage: python3 apply-fixes.py <fix-plan.json>

Exit 0: fixes applied, files written.
Exit 1: validation or matching failed, nothing was written. The error
        message on stderr says which fix failed and why.
"""

import json
import os
import sys

REQUIRED_TOP_LEVEL = ["fixes", "explanation", "confidence"]
REQUIRED_FIX_FIELDS = ["file", "line", "original", "replacement", "description"]
VALID_CONFIDENCE = ["high", "medium", "low"]


def fail(message):
    print(f"apply-fixes: {message}", file=sys.stderr)
    sys.exit(1)


def validate_plan(plan):
    if not isinstance(plan, dict):
        fail("fix plan is not a JSON object")
    for key in REQUIRED_TOP_LEVEL:
        if key not in plan:
            fail(f'fix plan is missing required field "{key}"')
    if plan["confidence"] not in VALID_CONFIDENCE:
        fail(f'confidence must be one of {", ".join(VALID_CONFIDENCE)}, got {plan["confidence"]!r}')
    if not isinstance(plan["fixes"], list):
        fail("fixes must be an array")
    for i, fix in enumerate(plan["fixes"]):
        for key in REQUIRED_FIX_FIELDS:
            if key not in fix:
                fail(f'fixes[{i}] is missing required field "{key}"')
        line = fix["line"]
        if not isinstance(line, int) or isinstance(line, bool) or line < 1:
            fail(f"fixes[{i}].line must be a positive integer, got {line!r}")


def line_start_index(content, line_number):
    """Character index where 1-indexed line_number starts, or -1 if the
    file has fewer lines than that."""
    index = 0
    current_line = 1
    while current_line < line_number:
        next_newline = content.find("\n", index)
        if next_newline == -1:
            return -1
        index = next_newline + 1
        current_line += 1
    return index


def apply_fix(content, fix):
    """Finds fix['original'] anchored near fix['line'], rather than
    anywhere in the file, so an ambiguous string elsewhere in the file
    can't be matched by mistake."""
    start = line_start_index(content, fix["line"])
    if start == -1:
        return None, f'line {fix["line"]} does not exist in the file'

    window_end = min(len(content), start + len(fix["original"]) + 500)
    window = content[start:window_end]
    offset = window.find(fix["original"])
    if offset == -1:
        preview = window[:120].replace("\n", "\\n")
        suffix = "..." if len(window) > 120 else ""
        return None, (
            f'expected to find the given "original" text at or after line {fix["line"]}, '
            f'but it was not there. Found instead: "{preview}{suffix}"'
        )

    absolute_start = start + offset
    absolute_end = absolute_start + len(fix["original"])
    new_content = content[:absolute_start] + fix["replacement"] + content[absolute_end:]
    return new_content, None


def main():
    if len(sys.argv) != 2:
        fail("usage: python3 apply-fixes.py <fix-plan.json>")

    plan_path = sys.argv[1]
    try:
        with open(plan_path, "r", encoding="utf-8") as f:
            plan = json.load(f)
    except (OSError, json.JSONDecodeError) as err:
        fail(f"could not read or parse {plan_path}: {err}")

    validate_plan(plan)

    print(f'apply-fixes: {len(plan["fixes"])} fix(es) at confidence "{plan["confidence"]}"')
    print(f'apply-fixes: {plan["explanation"]}')

    if not plan["fixes"]:
        print("apply-fixes: no fixes to apply.")
        return

    # Group by file. Within a file, apply bottom-to-top (highest line
    # number first). Line numbers refer to the file as Claude read it,
    # before any fixes were applied, so applying bottom-to-top means a fix
    # never shifts the line numbers of fixes still pending in that same
    # file. Across different files, order doesn't affect correctness.
    by_file = {}
    for fix in plan["fixes"]:
        by_file.setdefault(fix["file"], []).append(fix)

    pending_writes = {}

    for file_path, fixes in by_file.items():
        resolved = os.path.abspath(file_path)
        if not os.path.exists(resolved):
            fail(f'fix references file "{file_path}", which does not exist')

        with open(resolved, "r", encoding="utf-8") as f:
            content = f.read()

        for fix in sorted(fixes, key=lambda f: f["line"], reverse=True):
            new_content, error = apply_fix(content, fix)
            if error:
                fail(f'fix for {file_path}:{fix["line"]} ("{fix["description"]}") did not apply: {error}')
            content = new_content

        pending_writes[resolved] = content

    # Every fix in every file matched. Only now do we touch disk.
    for resolved, content in pending_writes.items():
        with open(resolved, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"apply-fixes: wrote {os.path.relpath(resolved)}")


if __name__ == "__main__":
    main()
