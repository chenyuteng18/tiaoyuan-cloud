#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S1-6 client zero-derived gate -- self-test witness.

WHAT THIS FILE IS FOR
=====================
`client-zero-derived-gate.py` reports PASS. That is a claim, and this file is the
independent assertion that the claim means something: every check below DESTROYS
a piece of the gate's protection and asserts the gate turns RED. A gate that only
ever prints PASS is a gate that has already rotted -- it reports compliance
because it checks nothing, and nobody finds out until an incident.

This mirrors `compliance/tests/compliance_injection_test.py` in structure and in
spirit. That file witnesses the ADR-12 scan faces; this file witnesses the S1-6
gate. They are separate on purpose: the ADR-12 witness must not grow a dependency
on the structural gate, or a break in one would take the other's evidence down
with it.

THE ASSERTIONS, AND WHY EACH ONE EXISTS
=======================================
  W0  baseline                  the real repository is green (the control)
  W1  derived name injected     a derived field name lands in the bundle
                                -> must exit 1, naming term, file and line
  W2  allow-list permits a denied endpoint
                                THE DENIED-ENDPOINT HALF OF THE S1-6 DEFECT,
                                re-injected: the real contract row E4 path
                                /customers/{id}/band/derived lands in the
                                allow-list -> must exit 1 as client-not-permitted
  W3  allow-list cites a non-existent path
                                a route that exists nowhere -> path-not-in-contract
  W3b the S1-6 defect in its EXACT original shape
                                what actually shipped was /api/v1/band/derived
                                -- note the missing /customers/{id} segment. It
                                was BOTH an invented route AND a spelling of the
                                denied E4 concept, and the two halves are
                                reported differently, so both are asserted.
  W4  client-forbidden path in the bundle
                                a refund-domain path lands in the client package
  W5  truth source unreadable   the visibility matrix is made unparseable
                                -> must exit 2, never a silent PASS
  W6  truth source emptied      every derived field removed FROM THE SOURCE
                                -> the gate must refuse, not scan for nothing
  W7  wrong docs root           --docs-root pointed somewhere without a contract
                                -> must refuse rather than verify the wrong repo
  W8  self-check honesty        the gate's own input audit is reported and
                                non-zero (derived fields, contract paths, files)

W6 is the one worth reading twice. It does NOT inject anything into the bundle;
it empties the CONTRACT's field list. The bundle is untouched and perfectly
compliant, so a gate that merely "scans the bundle for a list" would report PASS
-- correctly, for a bundle with nothing wrong -- while having stopped protecting
anything. That is the "gate that rotted" failure mode, and it is invisible from
the outside unless asserted directly.

EXIT CODES: 0 all assertions hold; 1 an assertion failed; 2 a setup error.
"""

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

# ---------------------------------------------------------------------------
# Paths. This file lives in compliance/tests/, so the build root is two levels up
# and the docs root (holding contract/) is three.
# ---------------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
COMPLIANCE_DIR = os.path.dirname(HERE)
BUILD_ROOT = os.path.dirname(COMPLIANCE_DIR)
DOCS_ROOT = os.path.dirname(BUILD_ROOT)

GATE = os.path.join(COMPLIANCE_DIR, "client-zero-derived-gate.py")
ALLOWLIST = os.path.join(BUILD_ROOT, "client-package", "api", "clientPaths.js")
CLIENT_ROOT = os.path.join(BUILD_ROOT, "client-package")
MATRIX = os.path.join(DOCS_ROOT, "contract", "visibility", "band-visibility-matrix.json")

PYTHON = sys.executable or "python"

FAILURES = []


def check(condition, message):
    if condition:
        print("  [ok] %s" % message)
    else:
        print("  [FAIL] %s" % message)
        FAILURES.append(message)


def check_caught(code, out, err, label, expect_kind_any=None):
    """
    Assert the gate FAILED BY DETECTING SOMETHING, not by crashing.

    This helper exists because of a real trap hit while writing this file. A
    Python traceback exits with status 1 -- the SAME status a genuine violation
    uses. An assertion of the form "exit code is nonzero, therefore the
    injection was caught" therefore passes when the gate has a bug, and reports
    the gate as load-bearing at the exact moment it is broken. It was hit for
    real: a NameError inside FACE 2 produced exit 1 and looked like detection.

    So a pass here requires ALL of:
      * exit code 1                       -- a violation, not 2/3 (misconfigured /
                                             internal) and not 0
      * the internal-error banner is absent
      * a traceback is absent
      * at least one VIOLATIONS line is present
      * the verdict line says FAIL with a violation count above zero
    """
    problems = []
    if code != 1:
        problems.append("expected exit 1, got %d" % code)
    if "internal error" in err or "internal error" in out:
        problems.append("the gate reported an INTERNAL ERROR -- this is a crash "
                        "being counted as detection")
    if "Traceback (most recent call last)" in err or \
            "Traceback (most recent call last)" in out:
        problems.append("a traceback was printed")
    if "VIOLATIONS" not in out:
        problems.append("no VIOLATIONS section was printed")
    if "[S1-6-GATE] FAIL" not in out:
        problems.append("no FAIL verdict line was printed")
    if "faces=3  violations=0" in out:
        problems.append("the verdict reports zero violations")

    check(not problems,
          "%s: the gate must fail by DETECTING, not by crashing\n%s%s"
          % (label, out, err)
          + ("\n  problems: " + "; ".join(problems) if problems else ""))

    if expect_kind_any:
        check(any(k in out for k in expect_kind_any),
              "%s: the report must classify the finding as one of %s\n%s"
              % (label, list(expect_kind_any), out))


def run_gate(extra_args=None, repo_root=BUILD_ROOT, docs_root=None):
    """Run the gate and return (exit_code, stdout, stderr)."""
    cmd = [PYTHON, GATE, "--repo-root", repo_root]
    if docs_root:
        cmd += ["--docs-root", docs_root]
    cmd += (extra_args or [])
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    proc = subprocess.run(cmd, capture_output=True, env=env)
    out = proc.stdout.decode("utf-8", errors="replace")
    err = proc.stderr.decode("utf-8", errors="replace")
    return proc.returncode, out, err


def _read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def _write(path, text):
    with io.open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)


# ===========================================================================
# W0 baseline
# ===========================================================================

def w0_baseline():
    code, out, err = run_gate()
    check(code == 0,
          "W0 baseline: the real repository must be green, got exit %d\n%s%s"
          % (code, out, err))
    check("[S1-6-GATE] PASS" in out,
          "W0 baseline: the verdict line must be printed")
    return "baseline repository is green (exit 0)"


# ===========================================================================
# W1 a derived name in the bundle
# ===========================================================================

def w1_derived_name_injected():
    probe = os.path.join(CLIENT_ROOT, "enums", "__inject_zero_derived.js")
    # effect_verdict is a ④ derived_result field, ruled client-invisible by the
    # contract visibility matrix. Written as a real identifier so the match rules
    # are exercised, not a contrived string.
    _write(probe, "const CLIENT_EFFECT_VERDICT_CACHE = {};\n")
    try:
        code, out, err = run_gate()
        check_caught(code, out, err, "W1")
        check("effect_verdict" in out,
              "W1: the report must name the offending term\n%s" % out)
        check("__inject_zero_derived.js" in out,
              "W1: the report must name the offending file\n%s" % out)
    finally:
        if os.path.exists(probe):
            os.remove(probe)
    return "derived field name -> exit 1, names term + file"


def w1b_camel_case_spelling_is_not_a_way_past():
    """
    THE DIALECT BLIND SPOT, PINNED (fix, 2026-09-24).

    W1 writes the term in SCREAMING_SNAKE (`CLIENT_EFFECT_VERDICT_CACHE`). That
    passed even before the fix, because the matcher split on underscores. The
    bundle is JavaScript, though, where the same fact arrives as camelCase -- and
    `identifier_segments` did NOT split camel-case humps:

        effect_verdict   -> caught
        effectVerdict    -> [S1-6-GATE] PASS   <-- measured, before the fix

    So the gate was blind in the dialect most likely to ship. This case injects
    the camelCase form, which no other case covers; without it the regression can
    silently return the next time the matcher is touched.
    """
    probe = os.path.join(CLIENT_ROOT, "enums", "__inject_zero_derived_camel.js")
    _write(probe, "const clientEffectVerdictCache = {};\n")
    try:
        code, out, err = run_gate()
        check_caught(code, out, err, "W1b")
        check("effect_verdict" in out or "effectVerdict" in out,
              "W1b: the report must name the offending term\n%s" % out)
        check("__inject_zero_derived_camel.js" in out,
              "W1b: the report must name the offending file\n%s" % out)
    finally:
        if os.path.exists(probe):
            os.remove(probe)
    return "camelCase spelling -> exit 1 (was a PASS before the fix)"


# ===========================================================================
# W2 / W3 allow-list defects
# ===========================================================================

def _run_allowlist_injection(injected_entry, label, expected_kind):
    """
    Append an entry to the real allow-list, run the gate, restore.

    The allow-list is a source file, so the backup must be taken BEFORE the edit
    and restored in a finally block -- a failed assertion must not leave the
    repository holding an injected permission.
    """
    original = _read(ALLOWLIST)
    try:
        patched = original.replace(
            "'/api/v1/customers/{id}/band/sync-status',",
            "'/api/v1/customers/{id}/band/sync-status',\n  '%s'," % injected_entry)
        check(patched != original,
              "%s: the injection anchor must exist in the allow-list" % label)
        _write(ALLOWLIST, patched)

        code, out, err = run_gate()
        check_caught(code, out, err, label, expect_kind_any=[expected_kind])
        check(injected_entry in out,
              "%s: the report must name the injected path\n%s" % (label, out))
    finally:
        _write(ALLOWLIST, original)
    return code


def w2_allowlist_permits_denied_endpoint():
    """
    THE DENIED-ENDPOINT HALF OF THE S1-6 DEFECT, RE-INJECTED.

    The contract path is /customers/{id}/band/derived -- row E4, whose
    x-callable-roles is [therapist, meridian, admin] and which carries
    x-client-explicitly-denied. Every word in the path is innocuous to a
    word-scan, and at the time the concept word was not even in the ADR-12
    wordlist, so the only thing that can catch it is a gate that checks the
    allow-list against the contract.

    If this assertion stops holding, the gate has lost the ability to catch the
    exact defect it was written for.
    """
    _run_allowlist_injection(
        "/api/v1/customers/{id}/band/derived",
        "W2 allow-list permits a client-denied endpoint",
        "client-not-permitted")

    # And the control: restoring the file must put the gate back to green.
    code, out, err = run_gate()
    check(code == 0,
          "W2 control: with the allow-list restored the gate must pass, got %d\n%s%s"
          % (code, out, err))
    return "client-denied endpoint in the allow-list -> exit 1 (the S1-6 defect)"


def w3b_original_defect_shape():
    """
    THE S1-6 DEFECT IN ITS EXACT ORIGINAL SHAPE.

    What actually shipped was `/api/v1/band/derived`. Read carefully, it is not
    the contract's E4 path: the `/customers/{id}` segment is missing, so it is an
    INVENTED ROUTE that also happens to spell a denied concept. The gate reports
    the two halves under different kinds -- `path-not-in-contract` here,
    `client-not-permitted` in W2 -- so both are asserted rather than assuming the
    one covers the other.

    This matters for a reader of the report: "the contract denies this endpoint"
    and "this endpoint does not exist" are different findings with different
    fixes, and a gate that collapsed them would send the reader to the wrong one.
    """
    _run_allowlist_injection(
        "/api/v1/band/derived",
        "W3b original defect shape",
        "path-not-in-contract")
    code, out, err = run_gate()
    check(code == 0,
          "W3b control: with the allow-list restored the gate must pass, got "
          "%d\n%s%s" % (code, out, err))
    return ("the originally shipped /api/v1/band/derived -> exit 1 as "
            "path-not-in-contract (a route that never existed, spelling a denied "
            "concept)")


def w3_allowlist_invents_a_path():
    """The other half of the same defect: a route that exists nowhere."""
    _run_allowlist_injection(
        "/api/v1/nonexistent/endpoint",
        "W3 allow-list invents a path",
        "path-not-in-contract")
    code, out, err = run_gate()
    check(code == 0,
          "W3 control: with the allow-list restored the gate must pass, got %d\n%s%s"
          % (code, out, err))
    return "invented path in the allow-list -> exit 1"


# ===========================================================================
# W4 a client-forbidden path in the bundle
# ===========================================================================

def w4_client_forbidden_path_in_bundle():
    probe = os.path.join(CLIENT_ROOT, "api", "__inject_zero_forbidden_path.js")
    # A refund-domain path (contract section 2.7: the bundle must carry no path
    # of this domain). Written as a literal so the segment-run match is what
    # catches it, not a substring coincidence.
    _write(probe, "const P = '/api/v1/refunds/x/receipts';\n")
    try:
        code, out, err = run_gate()
        check_caught(code, out, err, "W4")
        check("__inject_zero_forbidden_path.js" in out,
              "W4: the report must name the offending file\n%s" % out)
    finally:
        if os.path.exists(probe):
            os.remove(probe)
    return "client-forbidden path in the bundle -> exit 1"


# ===========================================================================
# W5 / W6 / W7 truth-source failures
# ===========================================================================

def w5_truth_source_unreadable():
    """
    The truth source is corrupted. The gate must refuse, not fall back to an
    empty set -- an unreadable input that yields "no violations" is the most
    dangerous possible behaviour.
    """
    docs_tmp = tempfile.mkdtemp(prefix="s16-docs-")
    try:
        shutil.copytree(os.path.join(DOCS_ROOT, "contract"),
                        os.path.join(docs_tmp, "contract"))
        _write(os.path.join(docs_tmp, "contract", "visibility",
                            "band-visibility-matrix.json"),
               "{ this is not valid json ")
        code, out, err = run_gate(docs_root=docs_tmp)
        check(code == 2,
              "W5: an unparseable truth source must exit 2, got %d\n%s%s"
              % (code, out, err))
        check("[S1-6-GATE] PASS" not in out,
              "W5: a misconfigured run must not print a PASS verdict\n%s" % out)
    finally:
        shutil.rmtree(docs_tmp, ignore_errors=True)
    return "unparseable truth source -> exit 2, never a silent PASS"


def w6_truth_source_emptied():
    """
    THE ROTTED-GATE ASSERTION.

    The BUNDLE is left completely untouched and compliant. Only the CONTRACT's
    derived-field list is emptied. A gate that scans the bundle for a list will
    now scan for nothing and PASS -- correctly, for a bundle with no violation --
    while having stopped protecting anything at all.

    Nothing about the bundle's contents can reveal this. The only defence is for
    the gate to assert its own input is non-empty. That is FACE 4, and this is
    the assertion that proves FACE 4 has teeth.
    """
    docs_tmp = tempfile.mkdtemp(prefix="s16-docs-")
    try:
        shutil.copytree(os.path.join(DOCS_ROOT, "contract"),
                        os.path.join(docs_tmp, "contract"))
        matrix_path = os.path.join(docs_tmp, "contract", "visibility",
                                   "band-visibility-matrix.json")
        doc = json.loads(_read(matrix_path))
        for group in ("gap_reason", "derived_result"):
            doc["field_groups"][group]["fields"] = []
        _write(matrix_path, json.dumps(doc, ensure_ascii=False, indent=2))

        code, out, err = run_gate(docs_root=docs_tmp)
        check(code == 2,
              "W6: an emptied derived-field list must exit 2 (refuse to scan for "
              "nothing), got %d\n%s%s" % (code, out, err))
        check("[S1-6-GATE] PASS" not in out,
              "W6: the emptied-source run must not print a PASS verdict\n%s" % out)
    finally:
        shutil.rmtree(docs_tmp, ignore_errors=True)
    return ("emptied derived-field list -> exit 2 (the gate refuses to scan for "
            "nothing while the bundle stays untouched and green)")


def w7_wrong_docs_root():
    """
    A wrong root must be refused rather than quietly verified. This is the
    "verified the wrong repository" failure mode; auto-detection walks up, so
    pointing it at a directory with no contract must fail loudly.
    """
    tmp = tempfile.mkdtemp(prefix="s16-noroot-")
    try:
        code, out, err = run_gate(docs_root=tmp)
        check(code == 2,
              "W7: a docs root with no contract must exit 2, got %d\n%s%s"
              % (code, out, err))
        check("contract" in err.lower(),
              "W7: the error must explain that contract/ was not found\n%s" % err)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return "docs root without a contract -> exit 2"


# ===========================================================================
# W8 self-check honesty
# ===========================================================================

def w8_self_check_reports_real_inputs():
    """
    The audit line must report the inputs it ACTUALLY used, so that a reader can
    tell a real scan from an empty one without opening the source.
    """
    report_path = os.path.join(COMPLIANCE_DIR, "reports", "__selftest_report.json")
    try:
        code, out, err = run_gate(["--json-report", report_path])
        check(code == 0, "W8: the baseline run must succeed, got %d" % code)
        check(os.path.isfile(report_path),
              "W8: the gate must write the machine-readable report")
        payload = json.loads(_read(report_path))
        check(payload["verdict"] == "PASS", "W8: report verdict must be PASS")

        audit = payload["audit"]
        face1 = audit["FACE 1 no-zero-derived-names"]
        check(face1["terms_checked"] > 0,
              "W8: terms_checked must be > 0 (a 0 would mean scanning nothing)")
        check(face1["files_scanned"] > 0,
              "W8: files_scanned must be > 0")
        face2 = audit["FACE 2 allowlist-within-contract"]
        check(face2["contract_client_paths"] > 0,
              "W8: contract_client_paths must be > 0")
        check(face2["allowlist_entries"] > 0,
              "W8: allowlist_entries must be > 0")
        face3 = audit["FACE 3 no-client-forbidden-paths"]
        check(face3["forbidden_paths_checked"] > 0,
              "W8: forbidden_paths_checked must be > 0")
    finally:
        if os.path.exists(report_path):
            os.remove(report_path)
    return "the audit reports non-zero inputs on all three faces"


def w10_both_client_roots_are_really_scanned():
    """
    B-3 (2026-09-28): the gate must scan BOTH client roots, and a missing one
    must be a refusal -- not a quiet scan of whatever is left.

    Why this needs its own case. `client-package` is the SAMPLE package; the
    real mini-program project lives at `frontends/client-mp/miniprogram`. For
    months the discipline covered only the sample, and the gate printed PASS
    while the surface that actually ships was unscanned. Nothing about that
    state looks wrong in the output -- which is precisely why a witness has to
    assert it rather than a human noticing.

    Two directions, both load-bearing:
      * positive: the real project's files are inside files_scanned;
      * negative: deleting a root from the gate's list makes it exit 2.
    """
    real_root = os.path.join(BUILD_ROOT, "frontends", "client-mp", "miniprogram")
    check(os.path.isdir(real_root),
          "W10: the real mini-program project must exist at %s" % real_root)

    report_path = os.path.join(COMPLIANCE_DIR, "reports", "__selftest_report.json")
    try:
        code, out, err = run_gate(["--json-report", report_path])
        check(code == 0, "W10: the baseline run must succeed, got %d" % code)
        payload = json.loads(_read(report_path))
        scanned = payload["audit"]["FACE 1 no-zero-derived-names"]["files_scanned"]

        sample = 0
        real = 0
        for dirpath, dirnames, filenames in os.walk(
                os.path.join(BUILD_ROOT, "client-package")):
            dirnames[:] = [d for d in dirnames if d != "node_modules"]
            sample += len(filenames)
        for dirpath, dirnames, filenames in os.walk(real_root):
            dirnames[:] = [d for d in dirnames if d != "node_modules"]
            real += len(filenames)
        check(real > 0,
              "W10: the real project must contain source files to scan")
        # files_scanned filters by extension, so it is <= the walk count; what
        # must hold is that adding the second root made it strictly larger.
        check(scanned > sample,
              "W10: files_scanned (%d) must exceed the sample package's file "
              "count (%d) -- otherwise the second root is not being scanned"
              % (scanned, sample))
    finally:
        if os.path.exists(report_path):
            os.remove(report_path)

    # The negative direction: shrink the root list and the gate must refuse.
    gate_path = os.path.join(COMPLIANCE_DIR, "client-zero-derived-gate.py")
    original = _read(gate_path)
    patched = original.replace(
        'CLIENT_ROOTS_REL = ("client-package", "frontends/client-mp/miniprogram")',
        'CLIENT_ROOTS_REL = ("client-package",)', 1)
    check(patched != original,
          "W10: the injection anchor (CLIENT_ROOTS_REL) must exist")
    if patched == original:
        return "both directions hold"
    try:
        _write(gate_path, patched)
        code, out, err = run_gate()
        check(code == 2,
              "W10: dropping a scan root must make the gate REFUSE (exit 2), "
              "got %d -- a silent scan of the remaining root is the failure "
              "this case exists to prevent" % code)
        check("[S1-6-GATE] PASS" not in out,
              "W10: the gate must not print PASS after losing a scan root")
    finally:
        _write(gate_path, original)
    return "both client roots are scanned, and losing one is a refusal"


def w9_residual_probe_guard_sees_all_probe_names():
    """
    THE GUARD THAT COULD NOT SEE A PROBE FROM ANOTHER SUITE (fix 2, 2026-09-24).

    The reclaim step and the end-of-run guard both used the prefix pair
    ("__inject", "_probe"). The ADR-12 witness creates its scope probe at
    `client-package/constants/__scope_probe_client.js` -- prefix `__scope`, which
    that pair does NOT match. Measured: plant that file, run this suite alone, and
    it is still on disk afterwards; the next `mvn validate` then reports
    `[COMPLIANCE-GATE] FAIL violations=1`, which reads as a repository problem
    rather than a leftover.

    This asserts the recognition property directly, so a future narrowing of the
    rule fails HERE instead of showing up as a flaky gate. It is the S1-6 sibling
    of the ADR-12 witness's T11 and the S1-8 witness's W10.
    """
    must_be_seen = [
        "__inject_zero_derived.js",         # this suite's own W1 probe
        "__inject_zero_forbidden_path.js",  # this suite's own W4 probe
        "__inject_invisible_field.js",      # the S1-8 suite's probe shape
        "__scope_probe_client.js",          # the ADR-12 suite's probe -- the miss
        "_probe_something.py",              # the plain-prefix shape
    ]
    missed = [n for n in must_be_seen if not _is_probe_name(n)]
    check(not missed,
          "W9: the residual-probe guard must recognise every probe name any "
          "suite in this repo creates; it was blind to %s" % missed)

    must_not_be_seen = ["clientConstants.js", "clientErrorCopy.js",
                        "zh-Hans.json", "revoke_owner.sql"]
    overreach = [n for n in must_not_be_seen if _is_probe_name(n)]
    check(not overreach,
          "W9: the guard must not fire on a normal artifact name; it fired on %s"
          % overreach)
    return ("residual-probe guard recognises all %d real probe names and no "
            "innocent ones" % len(must_be_seen))


# ===========================================================================
# Runner
# ===========================================================================

# ---------------------------------------------------------------------------
# Probe-name recognition. A name is a probe if it carries a known prefix OR
# simply CONTAINS 'probe'.
#
# WHY THE CONTAINS-RULE (fix 2, 2026-09-24). The first version used the prefix
# pair ("__inject", "_probe"), which is UNSOUND: the ADR-12 witness creates its
# scope probe at `client-package/constants/__scope_probe_client.js` (prefix
# `__scope`), so a stranded copy was invisible to this suite's reclaim step AND
# its end-of-run guard. A prefix allow-list cannot recognise a name it was never
# told about; a marker check can. See the ADR-12 witness's T11 and the S1-8
# witness's W10, which pin the same property in their own suites.
# ---------------------------------------------------------------------------
PROBE_PREFIXES = ("__inject", "_probe", "__scope_probe")
PROBE_NAME_MARKER = "probe"


def _is_probe_name(filename):
    """True for a probe name, by prefix OR by the 'probe' marker."""
    if filename.startswith(PROBE_PREFIXES):
        return True
    return PROBE_NAME_MARKER in filename.lower()


def reclaim_own_leftovers():
    """
    Reclaim probes left behind by an INTERRUPTED earlier run of this suite.

    WHY THIS EXISTS (fix, 2026-09-24). Every case below restores the file it
    edits in a `finally`, but `finally` does NOT run on SIGKILL, on a CI job
    timeout, or on a cancelled run. When a probe survives, the very FIRST case --
    the W0 baseline, "the real repository must be green" -- goes red, and every
    case after it cascades. The diagnostic then blames the repository ("the real
    repository must be green, got exit 1") when the actual cause is one leftover
    file from a run somebody interrupted. Measured: plant
    `__inject_zero_derived.js`, run the suite, watch W0 fail.

    The same defect was found and fixed in the ADR-12 witness on the same day;
    this gate had it too. Cleaning must happen BEFORE the first assertion,
    because the first assertion is the one that goes red.

    SCOPED AND CONSERVATIVE: only files matching the probe-name convention are
    touched, and only inside the scanned client package. A file that is not a
    probe is never removed -- the end-of-run guard reports those instead.

    Returns the reclaimed relative paths.
    """
    reclaimed = []
    if not os.path.isdir(CLIENT_ROOT):
        return reclaimed
    for dirpath, _dirnames, filenames in os.walk(CLIENT_ROOT):
        for name in sorted(filenames):
            if not _is_probe_name(name):
                continue
            path = os.path.join(dirpath, name)
            os.remove(path)
            reclaimed.append(os.path.relpath(path, BUILD_ROOT))
    return reclaimed


def main():
    print("=" * 79)
    print("S1-6 CLIENT ZERO-DERIVED GATE -- SELF-TEST WITNESS")
    print("=" * 79)

    if not os.path.isfile(GATE):
        print("[GATE-ERROR] gate not found: %s" % GATE, file=sys.stderr)
        return 2
    if not os.path.isfile(MATRIX):
        print("[GATE-ERROR] truth source not found: %s" % MATRIX, file=sys.stderr)
        return 2

    # Before the first assertion, because a leftover makes the W0 baseline red.
    reclaimed = reclaim_own_leftovers()
    if reclaimed:
        print("  [note] reclaimed %d leftover(s) from an interrupted earlier run:"
              % len(reclaimed))
        for rel in reclaimed:
            print("         %s" % rel)

    cases = [
        ("W0 baseline", w0_baseline),
        ("W1 derived name injected", w1_derived_name_injected),
        ("W1b camelCase spelling is not a way past", w1b_camel_case_spelling_is_not_a_way_past),
        ("W2 allow-list permits a denied endpoint", w2_allowlist_permits_denied_endpoint),
        ("W3 allow-list invents a path", w3_allowlist_invents_a_path),
        ("W3b original defect shape", w3b_original_defect_shape),
        ("W4 client-forbidden path in bundle", w4_client_forbidden_path_in_bundle),
        ("W5 truth source unreadable", w5_truth_source_unreadable),
        ("W6 truth source emptied", w6_truth_source_emptied),
        ("W7 wrong docs root", w7_wrong_docs_root),
        ("W8 self-check honesty", w8_self_check_reports_real_inputs),
        ("W9 residual-probe guard", w9_residual_probe_guard_sees_all_probe_names),
        ("W10 both client roots really scanned", w10_both_client_roots_are_really_scanned),
    ]

    summary = []
    for label, fn in cases:
        print("")
        try:
            summary.append(("%s" % label, fn()))
        except Exception as exc:  # noqa: BLE001 -- a crashing witness is a FAIL
            check(False, "%s: raised %r" % (label, exc))
            summary.append((label, "raised %r" % (exc,)))

    # Cleanup assertion: no probe may survive, however it was created. A leftover
    # probe turns the gate permanently red, so this is the cheaper failure.
    print("")
    leftovers = []
    for dirpath, _dirnames, filenames in os.walk(CLIENT_ROOT):
        for name in filenames:
            if _is_probe_name(name):
                leftovers.append(os.path.join(dirpath, name))
    check(not leftovers,
          "cleanup: no injected probe may survive the witness run; found %s"
          % leftovers)

    print("")
    print("=" * 79)
    if FAILURES:
        print("[S1-6-SELFTEST] FAIL  assertions=%d  failed=%d"
              % (len(cases) + 1, len(FAILURES)))
        for msg in FAILURES:
            print("  - %s" % msg)
        return 1
    print("[S1-6-SELFTEST] PASS  assertions=%d  failed=0" % (len(cases) + 1))
    for label, detail in summary:
        print("  %s" % label)
    return 0


if __name__ == "__main__":
    sys.exit(main())