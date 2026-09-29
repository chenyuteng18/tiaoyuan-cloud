#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S1-9 SDK surface gate -- self-test witness.

WHAT THIS FILE IS FOR
=====================
`sdk-surface-gate.py` prints PASS. That is a claim, and this file is the
independent assertion that the claim means something: every case below DESTROYS
a piece of the gate's protection and asserts the gate turns RED. A gate that
only ever prints PASS has already rotted -- it reports compliance because it
checks nothing, and nobody finds out until an incident.

This mirrors `contract_conformance_gate_test.py` in structure and in spirit,
and deliberately does NOT reuse it: the S1-8 witness must stay runnable when
this gate breaks, and vice versa.

THE CASES, AND WHY EACH ONE EXISTS
==================================
  X0  baseline                     the real repository is green (the control)
  X1  generated-outside-role-scope the client bundle is given an operation the
                                   client may not call. THE SAFETY DIRECTION:
                                   if this does not turn red, a capability leak
                                   can ship inside the client SDK.
  X2  dead-exclusion restored      B-4's fix is reverted (therapist-app gets
                                   `exclude-contract-rows: [H1]` back). Proves
                                   FACE 4 holds the line instead of being a
                                   one-off cleanup someone can undo silently.
  X3  one end's SDK removed        -> exit 2. An absent product must not read
                                   as "this end checked nothing wrong".
  X4  contract unparseable         -> exit 2, never a silent PASS
  X5  generated shape broken       a method literal is deleted, so paths and
                                   methods no longer pair 1:1. -> exit 2. THIS
                                   IS THE CASE THAT MATTERS MOST: the tempting
                                   implementation is `zip(paths, methods)`,
                                   which truncates silently and would report
                                   PASS on a surface it only half read.
  X6  self-check sees zero inputs  an emptied end -> exit 2 rather than a face
                                   that watches nothing

WHY EVERY INJECTION RUNS ON A SCRATCH COPY
==========================================
The gate's inputs (`contract/sdk/**`) are generated artifacts. Rewriting them
in place, even with a restore in a finally block, would leave the repository
holding an injected defect the moment a case crashes between edit and restore.
Each case therefore copies the inputs to a temporary docs root first, and the
real tree is never written to.

EXIT CODES: 0 all assertions hold; 1 an assertion failed; 2 a setup error.
"""

import io
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
COMPLIANCE_DIR = os.path.dirname(HERE)
BUILD_ROOT = os.path.dirname(COMPLIANCE_DIR)
DOCS_ROOT = os.path.dirname(BUILD_ROOT)

GATE = os.path.join(COMPLIANCE_DIR, "sdk-surface-gate.py")
OPENAPI_REL = os.path.join("contract", "openapi-v1.0.0.yaml")
SDK_REL = os.path.join("contract", "sdk")
GEN_MATRIX_REL = os.path.join("contract", "sdk-generator", "generator-matrix.yaml")
# The shared docs-root resolver requires the visibility matrix too.
VIS_MATRIX_REL = os.path.join("contract", "visibility", "band-visibility-matrix.json")

PYTHON = sys.executable or "python"

FAILURES = []


def check(condition, message):
    if condition:
        print("  [ok] %s" % message)
    else:
        print("  [FAIL] %s" % message)
        FAILURES.append(message)


def run_gate(docs_root, env_extra=None):
    cmd = [PYTHON, GATE, "--docs-root", docs_root]
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    if env_extra:
        env.update(env_extra)
    proc = subprocess.run(cmd, capture_output=True, env=env)
    return (proc.returncode,
            proc.stdout.decode("utf-8", errors="replace"),
            proc.stderr.decode("utf-8", errors="replace"))


def _read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def _write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


def scratch_docs_root():
    """A disposable copy of everything the gate reads."""
    tmp = tempfile.mkdtemp(prefix="sdk-surface-gate-")
    for rel in (OPENAPI_REL, GEN_MATRIX_REL, VIS_MATRIX_REL):
        dst = os.path.join(tmp, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(os.path.join(DOCS_ROOT, rel), dst)
    shutil.copytree(os.path.join(DOCS_ROOT, SDK_REL),
                    os.path.join(tmp, SDK_REL),
                    ignore=shutil.ignore_patterns("node_modules", ".git"))
    return tmp


class scratch(object):
    """`with scratch() as root:` gives a disposable docs root and cleans up."""

    def __enter__(self):
        self.root = scratch_docs_root()
        return self.root

    def __exit__(self, *_exc):
        shutil.rmtree(self.root, ignore_errors=True)
        return False


def check_caught(code, out, err, label, expect_kind_any=None):
    """
    Assert the gate FAILED BY DETECTING SOMETHING, not by crashing.

    A Python traceback exits 1 -- the same status a real violation uses -- so a
    bare "exit code is nonzero" assertion passes when the gate itself is broken
    and reports it as load-bearing at the exact moment it is not.
    """
    problems = []
    if code != 1:
        problems.append("expected exit 1, got %d" % code)
    if "internal error" in err or "internal error" in out:
        problems.append("the gate reported an INTERNAL ERROR -- a crash being "
                        "counted as detection")
    if "Traceback (most recent call last)" in err or \
            "Traceback (most recent call last)" in out:
        problems.append("a traceback was printed")
    if "VIOLATIONS" not in out:
        problems.append("no VIOLATIONS section was printed")
    if "[SDK-SURFACE-GATE] FAIL" not in out:
        problems.append("no FAIL verdict line was printed")

    verdict = ""
    for line in out.splitlines():
        if line.startswith("[SDK-SURFACE-GATE] FAIL"):
            verdict = line
            break
    count = None
    if "violations=" in verdict:
        try:
            count = int(verdict.rsplit("violations=", 1)[1].strip())
        except ValueError:
            count = None
    if verdict and (count is None or count <= 0):
        problems.append("the verdict line reports no violations: %r" % verdict)

    check(not problems,
          "%s: the gate must fail by DETECTING, not by crashing\n%s%s"
          % (label, out, err)
          + ("\n  problems: " + "; ".join(problems) if problems else ""))

    if expect_kind_any:
        section = out.split("VIOLATIONS", 1)[1] if "VIOLATIONS" in out else out
        check(any(k in section for k in expect_kind_any),
              "%s: the report must classify the finding as one of %s\n%s"
              % (label, list(expect_kind_any), out))


def check_misconfigured(code, out, err, label):
    """The gate must REFUSE (exit 2), and must never print PASS."""
    problems = []
    if code != 2:
        problems.append("expected exit 2, got %d" % code)
    if "[SDK-SURFACE-GATE] PASS" in out:
        problems.append("the gate printed PASS on an input set it could not read")
    if "Traceback (most recent call last)" in err:
        problems.append("a traceback was printed (a crash, not a refusal)")
    check(not problems,
          "%s: the gate must refuse loudly\n%s%s" % (label, out, err)
          + ("\n  problems: " + "; ".join(problems) if problems else ""))


# ===========================================================================
# X0 baseline
# ===========================================================================

def x0_baseline():
    code, out, err = run_gate(DOCS_ROOT)
    check(code == 0,
          "X0 baseline: the real repository must be green, got exit %d\n%s%s"
          % (code, out, err))
    if code == 0:
        check("[SDK-SURFACE-GATE] PASS  faces=5  violations=0" in out,
              "X0 baseline: the verdict must report all five faces\n%s" % out)
        # A green run must still be a run: every end must have contributed.
        for end_id in ("client-mp", "therapist-app", "admin-web"):
            check(("operations_generated" in out) and end_id in out,
                  "X0 baseline: %s must appear in the report" % end_id)


# ===========================================================================
# X1 -- generated outside role scope (the safety direction)
# ===========================================================================

def x1_generated_outside_role_scope():
    anchor_file = os.path.join(SDK_REL, "client-mp", "src", "api", "AApi.js")
    with scratch() as root:
        target = os.path.join(root, anchor_file)
        original = _read(target)
        before = "'/auth/login', 'POST'"
        after = "'/refunds', 'POST'"
        check(before in original,
              "X1: the injection anchor %s must exist" % before)
        if before not in original:
            return
        _write(target, original.replace(before, after, 1))
        code, out, err = run_gate(root)
    check_caught(code, out, err, "X1 client bundle given an admin-only operation",
                 expect_kind_any=["generated-outside-role-scope"])


# ===========================================================================
# X2 -- B-4's fix reverted
# ===========================================================================

def x2_dead_exclusion_restored():
    rel = GEN_MATRIX_REL
    with scratch() as root:
        target = os.path.join(root, rel)
        original = _read(target)
        before = "exclude-contract-rows: []\n    output: ../sdk/therapist-app"
        after = "exclude-contract-rows: [H1]\n    output: ../sdk/therapist-app"
        check(before in original,
              "X2: therapist-app must declare an empty exclude list (B-4's fix)")
        if before not in original:
            return
        _write(target, original.replace(before, after, 1))
        code, out, err = run_gate(root)
    check_caught(code, out, err, "X2 a no-op exclusion restored to the matrix",
                 expect_kind_any=["dead-exclusion"])


# ===========================================================================
# X3 -- one end's product missing
# ===========================================================================

def x3_end_absent():
    with scratch() as root:
        shutil.rmtree(os.path.join(root, SDK_REL, "admin-web"))
        code, out, err = run_gate(root)
    check_misconfigured(code, out, err,
                        "X3 admin-web's generated product removed")


# ===========================================================================
# X4 -- contract unparseable
# ===========================================================================

def x4_contract_unparseable():
    with scratch() as root:
        target = os.path.join(root, OPENAPI_REL)
        text = _read(target)
        _write(target, text.replace("openapi:", "openapi: [", 1))
        code, out, err = run_gate(root)
    check_misconfigured(code, out, err, "X4 contract made unparseable")


# ===========================================================================
# X5 -- generated shape broken (paths and methods no longer pair 1:1)
# ===========================================================================

def x5_shape_broken():
    """
    THE CASE THAT MATTERS MOST.

    Delete one method literal from a therapist API file and the counts no
    longer line up. A `zip(paths, methods)` implementation would silently drop
    the tail and report PASS on a surface it only half read -- the exact
    "silently checked less" failure this repository keeps having to repair.
    The gate must refuse instead.
    """
    rel = os.path.join(SDK_REL, "therapist-app", "src", "apis", "AApi.ts")
    with scratch() as root:
        target = os.path.join(root, rel)
        original = _read(target)
        anchor = "method: 'GET',"
        check(anchor in original,
              "X5: the injection anchor %r must exist" % anchor)
        if anchor not in original:
            return
        _write(target, original.replace(anchor, "", 1))
        code, out, err = run_gate(root)
    check_misconfigured(code, out, err,
                        "X5 a method literal deleted (paths != methods)")


# ===========================================================================
# X6 -- an end contributes nothing
# ===========================================================================

def x6_end_empty():
    with scratch() as root:
        end_dir = os.path.join(root, SDK_REL, "client-mp")
        for name in os.listdir(end_dir):
            p = os.path.join(end_dir, name)
            if os.path.isdir(p):
                shutil.rmtree(p)
            else:
                os.remove(p)
        code, out, err = run_gate(root)
    check_misconfigured(code, out, err,
                        "X6 client-mp's product emptied of source files")


# ===========================================================================
CASES = [
    ("X0 baseline", x0_baseline),
    ("X1 generated-outside-role-scope", x1_generated_outside_role_scope),
    ("X2 dead-exclusion-restored", x2_dead_exclusion_restored),
    ("X3 end-absent", x3_end_absent),
    ("X4 contract-unparseable", x4_contract_unparseable),
    ("X5 shape-broken", x5_shape_broken),
    ("X6 end-empty", x6_end_empty),
]


def main():
    print("=" * 79)
    print("S1-9 SDK SURFACE GATE -- WITNESS")
    print("=" * 79)
    for label, fn in CASES:
        print("\n-- %s" % label)
        fn()
    print("")
    if FAILURES:
        print("[WITNESS] FAIL  %d assertion(s) failed" % len(FAILURES))
        for f in FAILURES:
            print("  - %s" % f.splitlines()[0])
        return 1
    print("[WITNESS] PASS  all %d cases hold "
          "-- the gate's protections are load-bearing" % len(CASES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
