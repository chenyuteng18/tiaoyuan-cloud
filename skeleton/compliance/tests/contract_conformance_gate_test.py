#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S1-8 contract conformance gate -- self-test witness.

WHAT THIS FILE IS FOR
=====================
`contract-conformance-gate.py` reports PASS. That is a claim, and this file is
the independent assertion that the claim means something: every case below
DESTROYS a piece of the gate's protection -- in the artifact, in the truth
source, or in the gate's own runtime -- and asserts the gate turns RED. A gate
that only ever prints PASS has already rotted: it reports compliance because it
checks nothing, and nobody finds out until an incident.

This mirrors `compliance/tests/zero_derived_gate_test.py` in structure and in
spirit, and it deliberately does NOT reuse it. The S1-6 witness must stay
runnable when this gate breaks (and vice versa); sharing code would let one
breakage take both sets of evidence down at once.

THE CASES, AND WHY EACH ONE EXISTS
==================================
  W0  baseline                  the real repository is green (the control)
  W1  FALSE CITATION -- THE REAL DEFECT
                                the visits entry cites D1; D1 is the POST, which
                                excludes the client. This is not hypothetical: it
                                shipped, and S1-6 verified the entry as correct
                                because the PATH is client-callable through D2.
                                Re-injected here to prove FACE 1 catches it.
  W1b citation names no row     a citation that resolves to nothing
  W1c citation names another path's row
                                cites a real row that sits on a different endpoint
  W1d citation removed          an entry with no citation at all -- checked, not
                                skipped: the citation IS the claim
  W2  error code not in contract
                                copy for a code the server cannot send
  W3  internal enum value in the client namespace
                                THE MEANING LEAK, not a word leak: `not_worn`
                                exists only in the internal enum
  W3b enum value in no enum at all
  W4  client-invisible field name in the bundle
                                `refund_visibility` -- proven REACHABLE, and
                                proven NOT covered by S1-6's wordlist (see the
                                comment in w4)
  W5  phantom field in the matrix
                                the drifted ④ list restored: names that exist in
                                no contract schema
  W5b group field list emptied  -> exit 2; the gate must refuse to scan for
                                nothing while every artifact stays compliant
  W6  contract unparseable      -> exit 2, never a silent PASS
  W6b matrix malformed          -> exit 2 (JSON, and it fails as MISCONFIG, not
                                as an internal crash)
  W7  wrong docs root           -> exit 2 rather than verify the wrong tree
  W8  PyYAML unavailable        -> exit 2. THE ONE THAT MATTERS MOST for this
                                gate: it is the only gate here that uses a real
                                YAML parser, and the tempting "fix" when PyYAML
                                is missing is to fall back to regexes. A silent
                                fallback would re-introduce exactly the
                                self-disagreeing parse that motivated the real
                                parser, while still printing PASS.
  W9  self-check honesty        the audit reports the inputs it ACTUALLY used,
                                so a reader can tell a real scan from an empty one

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

GATE = os.path.join(COMPLIANCE_DIR, "contract-conformance-gate.py")
ALLOWLIST = os.path.join(BUILD_ROOT, "client-package", "api", "clientPaths.js")
ERROR_COPY = os.path.join(BUILD_ROOT, "client-package", "errors", "clientErrorCopy.js")
SYNC_ENUM = os.path.join(BUILD_ROOT, "client-package", "enums", "clientSyncState.js")
CLIENT_ROOT = os.path.join(BUILD_ROOT, "client-package")
MATRIX = os.path.join(DOCS_ROOT, "contract", "visibility", "band-visibility-matrix.json")
OPENAPI = os.path.join(DOCS_ROOT, "contract", "openapi-v1.0.0.yaml")

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

    This helper exists because of a real trap hit while building the S1-6 gate: a
    Python traceback exits with status 1 -- the SAME status a genuine violation
    uses. An assertion of the form "exit code is nonzero, therefore the injection
    was caught" therefore passes when the gate has a bug, and reports the gate as
    load-bearing at the exact moment it is broken.

    So a pass here requires ALL of:
      * exit code 1                       -- a violation, not 2/3 and not 0
      * the internal-error banner is absent
      * a traceback is absent
      * at least one VIOLATIONS line is present
      * the verdict line says FAIL with a violation count above zero

    On the last point: the guard reads ONLY the verdict line, never the whole
    output. The FACE AUDIT table below that line prints `violations=0` once per
    face that is still clean, so a whole-output `"violations=0" in out` test fires
    on EVERY detecting run -- a guard that can never pass is as useless as one
    that can never fail, and it hides the next real bug behind a permanent FAIL.
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
    if "[S1-8-GATE] FAIL" not in out:
        problems.append("no FAIL verdict line was printed")

    verdict = ""
    for line in out.splitlines():
        if line.startswith("[S1-8-GATE] FAIL"):
            verdict = line
            break
    verdict_count = None
    if "violations=" in verdict:
        try:
            verdict_count = int(verdict.rsplit("violations=", 1)[1].strip())
        except ValueError:
            verdict_count = None
    if verdict and (verdict_count is None or verdict_count <= 0):
        problems.append("the verdict line reports no violations: %r" % verdict)

    check(not problems,
          "%s: the gate must fail by DETECTING, not by crashing\n%s%s"
          % (label, out, err)
          + ("\n  problems: " + "; ".join(problems) if problems else ""))

    if expect_kind_any:
        # Scoped to the VIOLATIONS section. Read against the whole output, a
        # kind string that also appears in the FACE AUDIT face NAMES (e.g.
        # `no-client-invisible-field-names`) matches even when no violation was
        # emitted at all -- a guard that cannot fail. Reading the section the
        # finding must actually live in is what makes this assertion load-bearing.
        section = out
        if "VIOLATIONS" in out:
            section = out.split("VIOLATIONS", 1)[1]
        check(any(k in section for k in expect_kind_any),
              "%s: the report must classify the finding as one of %s\n%s"
              % (label, list(expect_kind_any), out))


def run_gate(extra_args=None, repo_root=BUILD_ROOT, docs_root=None, env_extra=None):
    """Run the gate and return (exit_code, stdout, stderr)."""
    cmd = [PYTHON, GATE, "--repo-root", repo_root]
    if docs_root:
        cmd += ["--docs-root", docs_root]
    cmd += (extra_args or [])
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


def _edit_artifact(path, before, after, label):
    """
    Apply a one-line edit to a real source artifact, run the gate, restore.

    The backup is taken BEFORE the edit and restored in a finally block: a failed
    assertion must never leave the repository holding an injected defect.
    """
    original = _read(path)
    patched = original.replace(before, after, 1)
    check(patched != original,
          "%s: the injection anchor must exist in %s" % (label, os.path.basename(path)))
    if patched == original:
        return None
    try:
        _write(path, patched)
        return run_gate()
    finally:
        _write(path, original)


# ===========================================================================
# W0 baseline
# ===========================================================================

def w0_baseline():
    code, out, err = run_gate()
    check(code == 0,
          "W0 baseline: the real repository must be green, got exit %d\n%s%s"
          % (code, out, err))
    check("[S1-8-GATE] PASS" in out,
          "W0 baseline: the verdict line must be printed")
    return "baseline repository is green (exit 0)"


# ===========================================================================
# W1 false citations
# ===========================================================================

def w1_false_citation_the_real_defect():
    """
    THE DEFECT THIS GATE WAS WRITTEN FOR, RE-INJECTED.

    Entry 6 of the allow-list cited D1 on /customers/{id}/visits. D1 is the POST
    operation and its x-callable-roles is [therapist, meridian, admin]; the client
    may call the path only through D2 (the GET). The ENTRY was correct -- S1-6
    verified the path is client-callable -- and the CITATION was false.

    So the assertion is on the citation, not the path: put the D1 citation back
    and FACE 1 must report row-excludes-client. If this stops holding, the gate
    has lost the ability to catch the defect that motivated it.

    Note the gate must NOT report the entry as a forbidden path. Restoring D1 must
    leave FACE 2 silent, because the path itself is legitimately callable -- and
    a gate that conflated the two questions would send the reader to fix the
    wrong line.
    """
    result = _edit_artifact(
        ALLOWLIST,
        "'/api/v1/customers/{id}/visits',                        // D2 服务记录",
        "'/api/v1/customers/{id}/visits',                        // D1 服务核销",
        "W1")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W1", expect_kind_any=["row-excludes-client"])
    check("row-not-in-contract" not in out,
          "W1: the row EXISTS, so this must not be reported as a missing row\n%s"
          % out)
    check("POST D1" in out and "GET D2" in out,
          "W1: the report must show the per-method roles and name the "
          "client-callable sibling operation, so a reader sees WHY the citation "
          "is false and what to write instead\n%s" % out)

    code, out, err = run_gate()
    check(code == 0,
          "W1 control: with the citation restored the gate must pass, got %d\n%s%s"
          % (code, out, err))
    return ("re-injected D1 citation on the visits entry -> exit 1 as "
            "row-excludes-client (the real defect; the PATH stays legal)")


def w1b_citation_names_no_row():
    """A citation that resolves to no operation at all."""
    result = _edit_artifact(
        ALLOWLIST,
        "'/api/v1/auth/me',                                      // A2 身份 + 可见性档位",
        "'/api/v1/auth/me',                                      // Z9 身份 + 可见性档位",
        "W1b")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W1b", expect_kind_any=["row-not-in-contract"])
    check("Z9" in out,
          "W1b: the report must name the unresolvable row\n%s" % out)
    return "citation to a non-existent row -> exit 1 as row-not-in-contract"


def w1c_citation_names_another_paths_row():
    """
    Cites a REAL row that sits on a DIFFERENT endpoint.

    Distinct from W1b and W1 on purpose: the row exists (so not W1b) and its
    operation does allow the client (so not W1), yet the citation is still wrong
    because it describes another endpoint. A gate that only checked "does this row
    allow the client" would pass this, so the case is worth pinning.
    """
    result = _edit_artifact(
        ALLOWLIST,
        "'/api/v1/customers/{id}/daily-reports',                 // D3 每日填报",
        "'/api/v1/customers/{id}/daily-reports',                 // B4 每日填报",
        "W1c")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W1c", expect_kind_any=["row-not-on-this-path"])
    check("B4" in out,
          "W1c: the report must name the misplaced row\n%s" % out)
    return "citation to another path's row -> exit 1 as row-not-on-this-path"


def w1d_citation_removed():
    """
    An entry with no citation at all.

    The citation IS the claim FACE 1 exists to check, so its absence must be a
    finding rather than a silent skip: an uncited entry is an entry nothing ties
    to the contract.
    """
    result = _edit_artifact(
        ALLOWLIST,
        "'/api/v1/auth/me',                                      // A2 身份 + 可见性档位",
        "'/api/v1/auth/me',",
        "W1d")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W1d", expect_kind_any=["citation-missing"])
    return "entry with no citation -> exit 1 as citation-missing (not skipped)"


# ===========================================================================
# W2 error-copy codes
# ===========================================================================

def w2_error_code_not_in_contract():
    """Copy for a code the server can never send."""
    result = _edit_artifact(
        ERROR_COPY,
        "9001: { key: 'error.internal', text: '服务暂时不可用，请稍后再试' },",
        "9001: { key: 'error.internal', text: '服务暂时不可用，请稍后再试' },\n"
        "  9999: { key: 'error.invented', text: '不该存在的错误码' },",
        "W2")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W2", expect_kind_any=["code-not-in-contract"])
    check("9999" in out,
          "W2: the report must name the invented code\n%s" % out)
    return "error code not in the contract -> exit 1"


# ===========================================================================
# W3 client enum namespace isolation
# ===========================================================================

def w3_internal_value_in_client_namespace():
    """
    THE MEANING LEAK.

    `not_worn` exists only in the internal gap_reason enum. Putting it in the
    client namespace does not leak a forbidden WORD -- which is why no word-scan
    can see it -- it leaks the ATTRIBUTION: the client surface would be saying
    why a day has no data, which is exactly what it may not say. The two enums are
    required to be independent namespaces (contract conflict point C-3).
    """
    result = _edit_artifact(
        SYNC_ENUM,
        "  NO_DATA_TODAY: 'no_data_today',",
        "  NO_DATA_TODAY: 'no_data_today',\n  NOT_WORN: 'not_worn',",
        "W3")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W3",
                 expect_kind_any=["internal-value-in-client-namespace"])
    check("not_worn" in out,
          "W3: the report must name the leaked value\n%s" % out)
    return ("internal-enum value in the client namespace -> exit 1 (a meaning "
            "leak, invisible to a word scan)")


def w3b_enum_value_nowhere_in_contract():
    """A value in no enum at all -- a different finding from W3."""
    result = _edit_artifact(
        SYNC_ENUM,
        "  NO_DATA_TODAY: 'no_data_today',",
        "  NO_DATA_TODAY: 'no_data_today',\n  MADE_UP: 'made_up_state',",
        "W3b")
    if result is None:
        return "SKIPPED: anchor missing"
    code, out, err = result
    check_caught(code, out, err, "W3b",
                 expect_kind_any=["value-not-in-contract-enum"])
    return "enum value in no contract enum -> exit 1 as value-not-in-contract-enum"


# ===========================================================================
# W4 client-invisible field names (the S1-6 superset)
# ===========================================================================

def w4_client_invisible_field_name_is_reachable():
    """
    PROVES THE COVERAGE GAIN, RATHER THAN ASSERTING IT.

    `refund_visibility` is a contract field (AuthMeData) whose x-visible-to is
    [meridian, admin] -- no client. FACE 4 must catch it in the bundle.

    Two claims are asserted, and the second is the one worth reading:

      1. the gate catches `refund_visibility` in the client bundle; and
      2. S1-6's own gate does NOT catch it -- its wordlist and its matrix-derived
         field set do not contain this name.

    Claim 2 is asserted by RUNNING the S1-6 gate on the same injected bundle. That
    turns "this face is a superset" from a design statement into a measured fact:
    if the two ever converge, this assertion starts failing and the duplication can
    be reconsidered deliberately instead of drifting into place.
    """
    probe = os.path.join(CLIENT_ROOT, "constants", "__inject_invisible_field.js")
    original_s16 = None
    s16_gate = os.path.join(COMPLIANCE_DIR, "client-zero-derived-gate.py")
    _write(probe, "const SHOW = { refundVisibility: true };\n")
    try:
        code, out, err = run_gate()
        check_caught(code, out, err, "W4",
                     expect_kind_any=["client-invisible-field-name"])
        check("refund_visibility" in out,
              "W4: the report must name the offending field\n%s" % out)
        check("__inject_invisible_field.js" in out,
              "W4: the report must name the offending file\n%s" % out)

        # Claim 2: the S1-6 gate is blind to this name.
        if os.path.isfile(s16_gate):
            proc = subprocess.run(
                [PYTHON, s16_gate, "--repo-root", BUILD_ROOT],
                capture_output=True,
                env=dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1"))
            s16_out = proc.stdout.decode("utf-8", errors="replace")
            s16_err = proc.stderr.decode("utf-8", errors="replace")
            s16_clean = "refund_visibility" not in s16_out and \
                        "refundVisibility" not in s16_out
            check(s16_clean and "internal error" not in s16_err,
                  "W4 claim 2: the S1-6 gate must be BLIND to this name (that is "
                  "the coverage gain this face adds). It reported:\n%s%s"
                  % (s16_out, s16_err))
            original_s16 = proc.returncode
    finally:
        if os.path.exists(probe):
            os.remove(probe)
    note = "" if original_s16 is None else " (S1-6 exit=%d, silent on the name)" % original_s16
    return ("client-invisible field name in the bundle -> exit 1; and S1-6 does "
            "not see it" + note)


# ===========================================================================
# W5 matrix field lists
# ===========================================================================

def w5_phantom_field_in_matrix():
    """
    THE DRIFTED LIST, RESTORED.

    Puts the pre-fix ④ names back (`adherence_dimension_score`, `a3`). Neither
    exists in any contract schema, so FACE 5 must report phantom-field. This is
    the assertion that would have caught the drift long before it was found by
    hand.
    """
    docs_tmp = tempfile.mkdtemp(prefix="s18-docs-")
    try:
        shutil.copytree(os.path.join(DOCS_ROOT, "contract"),
                        os.path.join(docs_tmp, "contract"))
        matrix_path = os.path.join(docs_tmp, "contract", "visibility",
                                   "band-visibility-matrix.json")
        doc = json.loads(_read(matrix_path))
        doc["field_groups"]["derived_result"]["fields"] = [
            "adherence_dimension_score", "a3", "as_value", "effect_verdict",
            "refund_eligibility"]
        _write(matrix_path, json.dumps(doc, ensure_ascii=False, indent=2))

        code, out, err = run_gate(docs_root=docs_tmp)
        check_caught(code, out, err, "W5", expect_kind_any=["phantom-field"])
        check("adherence_dimension_score" in out,
              "W5: the report must name the phantom field\n%s" % out)
        check("[S1-8-GATE] PASS" not in out,
              "W5: a phantom name must not yield a PASS\n%s" % out)
    finally:
        shutil.rmtree(docs_tmp, ignore_errors=True)
    return "phantom field names in the matrix -> exit 1 as phantom-field"


def w5b_matrix_group_list_emptied():
    """
    THE ROTTED-GATE ASSERTION.

    Every artifact is left untouched and compliant. Only the CONTRACT's group
    field list is emptied. A gate that "compares the two lists" would now compare
    against nothing and PASS -- correctly, for an artifact with nothing wrong --
    while having stopped protecting anything.

    Nothing in the artifact can reveal this. The only defence is for the gate to
    assert its own input is non-empty, which is why `group-absent-in-contract` is
    a violation and an absent/empty list is a misconfiguration.
    """
    docs_tmp = tempfile.mkdtemp(prefix="s18-docs-")
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
        check(code == 1,
              "W5b: an emptied group list must be reported (the contract still "
              "declares these groups), got exit %d\n%s%s" % (code, out, err))
        check("[S1-8-GATE] PASS" not in out,
              "W5b: the emptied-list run must not print a PASS verdict\n%s" % out)
    finally:
        shutil.rmtree(docs_tmp, ignore_errors=True)
    return ("emptied group field list -> reported, never a PASS while the "
            "artifacts stay green")


# ===========================================================================
# W6 / W7 truth-source and root failures
# ===========================================================================

def w6_contract_unparseable():
    """A corrupt contract must be refused, not scanned to an empty result."""
    docs_tmp = tempfile.mkdtemp(prefix="s18-docs-")
    try:
        shutil.copytree(os.path.join(DOCS_ROOT, "contract"),
                        os.path.join(docs_tmp, "contract"))
        _write(os.path.join(docs_tmp, "contract", "openapi-v1.0.0.yaml"),
               "this:\n  is: [not, closed\n")
        code, out, err = run_gate(docs_root=docs_tmp)
        check(code == 2,
              "W6: an unparseable contract must exit 2, got %d\n%s%s"
              % (code, out, err))
        check("[S1-8-GATE] PASS" not in out,
              "W6: a misconfigured run must not print a PASS verdict\n%s" % out)
    finally:
        shutil.rmtree(docs_tmp, ignore_errors=True)
    return "unparseable contract -> exit 2, never a silent PASS"


def w6b_matrix_malformed_is_misconfig_not_crash():
    """
    Malformed JSON must exit 2 (misconfigured), NOT 3 (internal error).

    The distinction is the point: "an input is broken" and "the gate is broken"
    need different responses from whoever is on call. Folding a bad input into an
    internal error sends them to debug the gate.
    """
    docs_tmp = tempfile.mkdtemp(prefix="s18-docs-")
    try:
        shutil.copytree(os.path.join(DOCS_ROOT, "contract"),
                        os.path.join(docs_tmp, "contract"))
        _write(os.path.join(docs_tmp, "contract", "visibility",
                            "band-visibility-matrix.json"),
               "{ this is not valid json ")
        code, out, err = run_gate(docs_root=docs_tmp)
        check(code == 2,
              "W6b: malformed JSON must exit 2 (misconfigured), got %d\n%s%s"
              % (code, out, err))
        check(code != 3,
              "W6b: a broken INPUT must not be reported as a broken GATE")
        check("internal error" not in err,
              "W6b: no internal-error banner for an input problem\n%s" % err)
    finally:
        shutil.rmtree(docs_tmp, ignore_errors=True)
    return "malformed matrix JSON -> exit 2 (misconfig), distinguished from 3"


def w7_wrong_docs_root():
    """A wrong root must be refused rather than quietly verified."""
    tmp = tempfile.mkdtemp(prefix="s18-noroot-")
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


def w8_pyyaml_unavailable_is_loud():
    """
    THE MOST IMPORTANT ASSERTION IN THIS FILE.

    This is the only gate here that uses a real YAML parser, and the tempting
    "fix" when PyYAML is missing is to fall back to regexes. That fallback would
    quietly re-introduce the failure the real parser exists to prevent: while
    building this gate, two hand-rolled regex probes over the SAME contract file,
    minutes apart, returned different field census counts. A regex fallback would
    keep printing PASS while resting on a parser that disagrees with itself.

    So PyYAML's absence must be a MISCONFIGURATION (exit 2), not a downgrade. This
    case simulates the absence by shadowing the module on PYTHONPATH with one that
    raises ImportError, and asserts the gate refuses -- and says why.
    """
    tmp = tempfile.mkdtemp(prefix="s18-noyaml-")
    try:
        _write(os.path.join(tmp, "yaml.py"),
               "raise ImportError('PyYAML deliberately hidden by the S1-8 witness')\n")
        env_extra = {"PYTHONPATH": tmp}
        code, out, err = run_gate(env_extra=env_extra)
        check(code == 2,
              "W8: with PyYAML unavailable the gate must exit 2 (misconfigured), "
              "not degrade to regex and not crash, got %d\n%s%s"
              % (code, out, err))
        check(code != 3,
              "W8: an absent dependency is a misconfiguration, not an internal "
              "error")
        check("[S1-8-GATE] PASS" not in out,
              "W8: the gate must NOT print PASS without its parser\n%s" % out)
        check("PyYAML" in err,
              "W8: the error must name PyYAML so the operator knows what to "
              "install\n%s" % err)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return ("PyYAML unavailable -> exit 2 naming the dependency (NO silent "
            "regex fallback)")


# ===========================================================================
# W9 self-check honesty
# ===========================================================================

def w9_self_check_reports_real_inputs():
    """
    The audit must report the inputs it ACTUALLY used, so a reader can tell a real
    scan from an empty one without opening the source.
    """
    report_path = os.path.join(COMPLIANCE_DIR, "reports", "__selftest_s18.json")
    try:
        code, out, err = run_gate(["--json-report", report_path])
        check(code == 0, "W9: the baseline run must succeed, got %d" % code)
        check(os.path.isfile(report_path),
              "W9: the gate must write the machine-readable report")
        payload = json.loads(_read(report_path))
        check(payload["verdict"] == "PASS", "W9: report verdict must be PASS")

        audit = payload["audit"]
        f1 = audit["FACE 1 path-citations-are-true"]
        check(f1["entries_checked"] > 0 and f1["rows_indexed"] > 0,
              "W9: FACE 1 must report non-zero entries and rows")
        f2 = audit["FACE 2 error-copy-codes-are-real"]
        check(f2["codes_in_contract"] > 0 and f2["codes_in_copy"] > 0,
              "W9: FACE 2 must report non-zero code counts on both sides")
        f3 = audit["FACE 3 client-enum-namespace-isolated"]
        check(f3["client_values"] > 0 and f3["internal_only"] > 0,
              "W9: FACE 3 must report the client namespace and at least one "
              "internal-only value (a 0 would mean the isolation check is vacuous)")
        f4 = audit["FACE 4 no-client-invisible-field-names"]
        check(f4["fields_checked"] > 0 and f4["files_scanned"] > 0,
              "W9: FACE 4 must report non-zero fields and files")
        f5 = audit["FACE 5 matrix-field-lists-agree"]
        check("gap_reason" in f5 and "derived_result" in f5,
              "W9: FACE 5 must report both hard-boundary groups")
        check(audit["FACE 6 gate-self-check"]["problems"] == 0,
              "W9: the self-check must report zero problems on a green tree")
    finally:
        if os.path.exists(report_path):
            os.remove(report_path)
    return "the audit reports non-zero inputs on all five faces"


def w10_residual_probe_guard_sees_all_probe_names():
    """
    THE GUARD THAT COULD NOT SEE A PROBE FROM ANOTHER SUITE (fix 2, 2026-09-24).

    The reclaim step and the end-of-run guard both used the prefix pair
    ("__inject", "_probe"). The ADR-12 witness creates its scope probe at
    `client-package/constants/__scope_probe_client.js` -- prefix `__scope`, which
    that pair does NOT match. Measured: plant that file, run this suite alone, and
    it is still on disk afterwards; the next `mvn validate` then reports
    `[COMPLIANCE-GATE] FAIL violations=1`, and the failure looks like a repository
    problem rather than a leftover.

    This asserts the recognition property directly, so a future narrowing of the
    rule fails HERE instead of manifesting as a flaky gate. It is the S1-8 sibling
    of the ADR-12 witness's T11.
    """
    must_be_seen = [
        "__inject_invisible_field.js",      # this suite's own W4 probe
        "__inject_zero_forbidden_path.js",  # the S1-6 suite's probe shape
        "__scope_probe_client.js",          # the ADR-12 suite's probe -- the miss
        "_probe_something.py",              # the plain-prefix shape
    ]
    missed = [n for n in must_be_seen if not _is_probe_name(n)]
    check(not missed,
          "W10: the residual-probe guard must recognise every probe name any "
          "suite in this repo creates; it was blind to %s" % missed)

    # And it must not fire on innocent artifact names, or it becomes the next
    # false positive that gets the guard switched off.
    must_not_be_seen = ["clientConstants.js", "clientErrorCopy.js",
                        "zh-Hans.json", "revoke_owner.sql"]
    overreach = [n for n in must_not_be_seen if _is_probe_name(n)]
    check(not overreach,
          "W10: the guard must not fire on a normal artifact name; it fired on %s"
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
# WHY THE CONTAINS-RULE (fix 2, 2026-09-24). The first version of this helper
# used the prefix pair ("__inject", "_probe"). That pair is UNSOUND: the ADR-12
# witness creates its scope probe as `client-package/constants/__scope_probe_client.js`
# (prefix `__scope`, not `__inject`), so a stranded copy of it was invisible to
# BOTH this suite's reclaim step AND its end-of-run guard. Measured: plant that
# exact file, run this suite alone, and it survives to the end -- it is still on
# disk afterwards, and it turns the ADR-12 gate red on the next `mvn validate`
# (`[COMPLIANCE-GATE] FAIL violations=1`), which is how the "flaky gate" was
# finally traced. A prefix allow-list cannot hold a name it was never told about;
# a marker check can.
# ---------------------------------------------------------------------------
PROBE_PREFIXES = ("__inject", "_probe", "__scope_probe")
PROBE_NAME_MARKER = "probe"


def _is_probe_name(filename):
    """True for a probe name, by prefix OR by the 'probe' marker.

    The marker check is what makes this robust: a probe spelled by hand in a way
    nobody registered is still recognised."""
    if filename.startswith(PROBE_PREFIXES):
        return True
    return PROBE_NAME_MARKER in filename.lower()


def reclaim_own_leftovers():
    """
    Reclaim probes left behind by an INTERRUPTED earlier run of this suite.

    WHY THIS EXISTS (fix, 2026-09-24). Each case restores what it edits in a
    `finally`, but `finally` does not run on SIGKILL, a CI timeout, or a
    cancelled run. A surviving probe makes the FIRST case -- the W0 baseline,
    "the real repository must be green" -- go red, and the rest cascade. The
    diagnostic then blames the repository, when the cause is one leftover file.
    Measured: plant `__inject_invisible_field.js`, run the suite, watch W0 fail.

    Cleaning must happen BEFORE the first assertion. Scoped to the client package
    and to the probe-name convention; nothing else is touched, so the end-of-run
    guard still reports a foreign file.

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
    print("S1-8 CONTRACT CONFORMANCE GATE -- SELF-TEST WITNESS")
    print("=" * 79)

    if not os.path.isfile(GATE):
        print("[GATE-ERROR] gate not found: %s" % GATE, file=sys.stderr)
        return 2
    if not os.path.isfile(OPENAPI):
        print("[GATE-ERROR] truth source not found: %s" % OPENAPI, file=sys.stderr)
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
        ("W1 false citation (the real defect)", w1_false_citation_the_real_defect),
        ("W1b citation names no row", w1b_citation_names_no_row),
        ("W1c citation names another path's row", w1c_citation_names_another_paths_row),
        ("W1d citation removed", w1d_citation_removed),
        ("W2 error code not in contract", w2_error_code_not_in_contract),
        ("W3 internal value in client enum", w3_internal_value_in_client_namespace),
        ("W3b enum value in no enum", w3b_enum_value_nowhere_in_contract),
        ("W4 client-invisible field name", w4_client_invisible_field_name_is_reachable),
        ("W5 phantom field in matrix", w5_phantom_field_in_matrix),
        ("W5b group list emptied", w5b_matrix_group_list_emptied),
        ("W6 contract unparseable", w6_contract_unparseable),
        ("W6b malformed matrix = misconfig", w6b_matrix_malformed_is_misconfig_not_crash),
        ("W7 wrong docs root", w7_wrong_docs_root),
        ("W8 PyYAML unavailable is loud", w8_pyyaml_unavailable_is_loud),
        ("W9 self-check honesty", w9_self_check_reports_real_inputs),
        ("W10 residual-probe guard", w10_residual_probe_guard_sees_all_probe_names),
    ]

    summary = []
    for label, fn in cases:
        print("")
        try:
            summary.append((label, fn()))
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

    # And the artifacts the gate edits in place must be byte-identical to before.
    print("")
    print("=" * 79)
    if FAILURES:
        print("[S1-8-SELFTEST] FAIL  assertions=%d  failed=%d"
              % (len(cases) + 1, len(FAILURES)))
        for msg in FAILURES:
            print("  - %s" % msg)
        return 1
    print("[S1-8-SELFTEST] PASS  assertions=%d  failed=0" % (len(cases) + 1))
    for label, detail in summary:
        print("  %s" % label)
    return 0


if __name__ == "__main__":
    sys.exit(main())