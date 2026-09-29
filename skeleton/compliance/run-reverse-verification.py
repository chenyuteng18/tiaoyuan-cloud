#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADR-12 reverse verification runner.

Produces the three-column evidence record the task requires:

    injection content  |  expected failure point  |  actual failure assertion text

It injects one sample per ADR-12 scan face into the REAL client package, runs
the REAL gate exactly as CI runs it, captures the gate's own output verbatim,
restores the tree, and writes a markdown report.

This is deliberately not self-certifying: it never asserts a value this file
declares. It asserts only that the gate's observable behaviour changed from PASS
to FAIL when a real hit was introduced, and it records the gate's own words as
the proof.

Usage
-----
  python compliance/run-reverse-verification.py [--out <report.md>]
Exit code 0 = every injection was caught AND the tree was restored to green.
"""

from __future__ import annotations

import datetime
import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
GATE = os.path.join(HERE, "scan_compliance.py")

sys.path.insert(0, os.path.join(HERE, "tests"))
from compliance_injection_test import INJECTIONS, ADR12_FACES  # noqa: E402

EXPECTED_FAILURE_POINT = {
    "scan1_refund": (
        "scan face 1 must report the injected analytics event name as a "
        "violation of the refund-wording rule and exit 1"
    ),
    "scan2_negative": (
        "scan face 2 must report the injected negative-counting copy as a "
        "violation and exit 1"
    ),
    "scan3_derived": (
        "scan face 3 must report the injected derived field name as a "
        "violation and exit 1"
    ),
}


def run_gate():
    env = dict(os.environ)
    env.pop("DY_COMPLIANCE_ALLOW_MISSING_ROOT", None)
    proc = subprocess.run(
        [sys.executable, GATE, "--repo-root", REPO_ROOT],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, cwd=REPO_ROOT,
    )
    return (proc.returncode,
            proc.stdout.decode("utf-8", errors="replace"),
            proc.stderr.decode("utf-8", errors="replace"))


def extract_assertion_text(stdout, stderr, face, rel_path, term):
    """Pull the gate's own words that constitute the failure assertion."""
    lines = []
    in_violations = False
    for line in stdout.splitlines():
        if line.startswith("VIOLATIONS"):
            in_violations = True
            continue
        if in_violations:
            if line.strip() == "":
                continue
            lines.append(line.rstrip())
    block = "\n".join(lines).strip()
    if block:
        return block
    return ("(gate produced no violation block)\nSTDOUT:\n%s\nSTDERR:\n%s"
            % (stdout.strip(), stderr.strip()))


def main(argv):
    out_path = None
    if "--out" in argv:
        out_path = argv[argv.index("--out") + 1]
    if out_path is None:
        out_path = os.path.join(HERE, "reports",
                                "REVERSE-VERIFICATION-adr12-%s.md"
                                % datetime.date.today().isoformat())

    rows = []
    failures = []

    for face in ADR12_FACES:
        sample = INJECTIONS[face]
        target = os.path.join(REPO_ROOT, sample["path"])
        if os.path.exists(target):
            failures.append("injection target already exists: %s" % target)
            continue
        try:
            parent = os.path.dirname(target)
            if parent and not os.path.isdir(parent):
                os.makedirs(parent)
            with io.open(target, "w", encoding="utf-8", newline="") as handle:
                handle.write(sample["content"])

            code, stdout, stderr = run_gate()
            if code != 1:
                failures.append(
                    "%s: gate exited %d, expected 1" % (face, code))
            assertion = extract_assertion_text(
                stdout, stderr, face, sample["path"], sample["expect_term"])
            rows.append({
                "face": face,
                "injection_path": sample["path"],
                "injection_content": sample["content"].rstrip(),
                "expected": EXPECTED_FAILURE_POINT[face],
                "actual": assertion,
                "exit_code": code,
                "verdict": "CAUGHT" if code == 1 else "MISSED",
            })
        finally:
            if os.path.exists(target):
                os.remove(target)

    # restore: the tree must be green again, proving the injections were the
    # only cause of the failures above.
    code_after, stdout_after, stderr_after = run_gate()
    if code_after != 0:
        failures.append(
            "tree did not return to green after cleanup (exit %d)\n%s%s"
            % (code_after, stdout_after, stderr_after))

    stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    buf = io.StringIO()
    buf.write("# ADR-12 build-time compliance scan -- reverse verification\n\n")
    buf.write("- run at: %s\n" % stamp)
    buf.write("- gate: `compliance/scan_compliance.py`\n")
    buf.write("- scan faces under test (ADR-12, immutable): %s\n"
              % ", ".join("`%s`" % f for f in ADR12_FACES))
    buf.write("- driving command: `python compliance/scan_compliance.py "
              "--repo-root .`\n")
    buf.write("- assertions executed by the injection harness: "
              "`compliance/tests/compliance_injection_test.py`\n\n")
    buf.write("## Verdict\n\n")
    buf.write("| injection | expected failure point | actual failure "
              "assertion | exit | result |\n")
    buf.write("|---|---|---|---|---|\n")
    for row in rows:
        buf.write("| `%s` -> `%s` | %s | see block below | %d | **%s** |\n"
                  % (row["face"], row["injection_path"], row["expected"],
                     row["exit_code"], row["verdict"]))
    buf.write("\n")
    buf.write("## Injection detail\n\n")
    for row in rows:
        buf.write("### %s\n\n" % row["face"])
        buf.write("**Injected content** (`%s`)\n\n" % row["injection_path"])
        buf.write("```javascript\n%s\n```\n\n" % row["injection_content"])
        buf.write("**Expected failure point**\n\n%s\n\n" % row["expected"])
        buf.write("**Actual failure assertion text (verbatim from the gate)**"
                  "\n\n```text\n%s\n```\n\n" % row["actual"])
        buf.write("**Exit code**: %d -- **%s**\n\n"
                  % (row["exit_code"], row["verdict"]))
    buf.write("## Restoration\n\n")
    buf.write("After the injections were removed, the gate was re-run:\n\n")
    buf.write("```text\nexit code: %d\n%s\n```\n\n"
              % (code_after, stdout_after.strip().splitlines()[-1]
                 if stdout_after.strip() else "(no output)"))
    buf.write("## Harness self-test\n\n")
    buf.write("The full assertion set (including owner discipline, scan-surface "
              "mutation, channel parity and fail-closed encoding) lives in "
              "`compliance/tests/compliance_injection_test.py`; run it with\n\n")
    buf.write("```bash\npython compliance/tests/compliance_injection_test.py\n"
              "```\n")

    report_dir = os.path.dirname(os.path.abspath(out_path))
    if report_dir and not os.path.isdir(report_dir):
        os.makedirs(report_dir)
    with io.open(out_path, "w", encoding="utf-8", newline="") as handle:
        handle.write(buf.getvalue())

    print("report written: %s" % out_path)
    for row in rows:
        print("  %-16s exit=%d %s" % (row["face"], row["exit_code"],
                                      row["verdict"]))
    print("tree restored to green: %s" % ("yes" if code_after == 0 else "NO"))
    if failures:
        print("")
        for item in failures:
            print("  [FAIL] %s" % item)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))