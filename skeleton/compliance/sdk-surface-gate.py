#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S1-9 SDK surface gate -- the three generated SDKs against the frozen contract.

WHAT THIS FILE IS FOR
=====================
`contract-conformance-gate.py` (S1-8) guards the CLIENT PACKAGE: the hand-
maintained allowlist, the error copy, the enum namespace, the matrix field
lists. It never looks at `contract/sdk/<end>/`, so the generated SDKs have
until now been **unchecked artifacts**: nobody asserted that the client bundle
does not contain a path the client may not call, or that the admin bundle still
covers what the contract says admin can call.

That hole is not theoretical. It is the same hole that hid defect #45: the
client allowlist was missing B5 for months because every gate around it checked
only one direction. A generated SDK drifts the same way -- regenerate after a
contract edit and the product silently keeps the old surface.

SO THIS GATE CLOSES THE OTHER SIDE, PER END:

  FACE 2  generated-surface-within-role-scope
          every (method, path) the SDK exposes must be callable by that end's
          token-roles. This is the SAFETY direction and it is a VIOLATION:
          a client bundle carrying an admin path is a shipped capability leak,
          not a style question.

  FACE 3  scope-covered-by-surface
          what the contract says the end MAY call but the SDK does not expose.
          Reported as INFORMATION, not a violation -- deliberately, and for the
          same reason S1-6 FACE 2 and S1-8 FACE 1 chose subset over equality
          (see the comment in `face_3` below). It is still counted and printed
          in full, because an unprinted subset check is indistinguishable from
          no check at all.

  FACE 4  exclude-rows-are-effective
          every row in the matrix's `exclude-contract-rows` must be a row that
          WOULD otherwise be in that end's scope. An exclusion naming a row the
          end could never reach anyway is a DECLARATION THAT DOES NOTHING: it
          makes the matrix read as if the end were protected, while the
          protection actually comes from the contract's own roles. Those are
          exactly the registered "DEAD rows" open question (client-mp 21 /
          therapist-app 15 / admin-web 6), and this face turns them into a
          build-time fact instead of a standing question.

REUSE, NOT REIMPLEMENTATION
===========================
The contract loader, the operation flattener and the docs-root resolution are
imported from `contract-conformance-gate.py`. There is one truth source for
"what the contract says" in this repository; a second parser would eventually
disagree with the first, and the disagreement would show up as a gate that
passes on one reading and fails on the other.

EXIT CODES (the compliance legend): 0 PASS / 1 VIOLATION / 2 MISCONFIGURED /
3 INTERNAL ERROR.
"""

import argparse
import importlib.util
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SIBLING = "contract-conformance-gate.py"


def _load_sibling():
    """Import the S1-8 gate as a module so both gates share one contract reader."""
    path = os.path.join(HERE, SIBLING)
    if not os.path.exists(path):
        print("[GATE-ERROR] %s is absent; this gate has no contract reader "
              "to reuse." % SIBLING, file=sys.stderr)
        sys.exit(2)
    spec = importlib.util.spec_from_file_location("s18_gate", path)
    if spec is None or spec.loader is None:
        print("[GATE-ERROR] cannot import %s" % SIBLING, file=sys.stderr)
        sys.exit(2)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


s18 = _load_sibling()

EXIT_PASS = s18.EXIT_PASS
EXIT_VIOLATION = s18.EXIT_VIOLATION
EXIT_MISCONFIGURED = s18.EXIT_MISCONFIGURED
EXIT_INTERNAL = s18.EXIT_INTERNAL
GateConfigError = s18.GateConfigError

MATRIX_REL = "contract/sdk-generator/generator-matrix.yaml"
SDK_ROOT_REL = "contract/sdk"

END_IDS = ("client-mp", "therapist-app", "admin-web")

# Generated-code shapes, one per generator. Each entry says how to read
# (path, method) pairs out of the product. `paired` means a single regex
# yields both; otherwise paths and methods are collected in file order and
# zipped -- and a length mismatch is a LOUD error, because zipping unequal
# lists truncates silently and truncation is exactly the "silently checked
# less" failure this repository keeps having to fix.
EXTRACTORS = {
    "client-mp": {
        "generator": "javascript",
        "paired": True,
        "pattern": r"callApi\(\s*'([^']+)'\s*,\s*'([A-Z]+)'",
        "files": r"\.(js)$",
        "skip": {"ApiClient.js", "index.js"},
    },
    "therapist-app": {
        "generator": "typescript-fetch",
        # NOT paired: a parameterised path is emitted as
        #     path: `/x/{id}`.replace(`{${"id"}}`, encodeURIComponent(...)),
        #     method: 'GET',
        # so a single regex over `path: `X`,` would match only the 9 calls with
        # no path parameters and silently miss the other 20. Pairing by
        # position, and failing loudly on an unequal count, is what keeps that
        # from becoming a gate that quietly checks less.
        "paired": False,
        "pattern": r"path:\s*`([^`]*)`",
        "method_pattern": r"method:\s*'([A-Z]+)'",
        "files": r"\.(ts)$",
        "skip": {"index.ts", "runtime.ts"},
    },
    "admin-web": {
        "generator": "typescript-axios",
        "paired": False,
        "pattern": r"const localVarPath = `([^`]*)`",
        "method_pattern": r"\{\s*method:\s*'([A-Z]+)'",
        "files": r"\.(ts)$",
        "skip": {"index.ts", "base.ts", "common.ts", "configuration.ts", "api.ts"},
    },
}


def pair_paths_methods(text, path_re, method_re, where):
    """
    Pair each path literal with the method literal that follows it.

    Zip-in-file-order would be simpler and would be wrong the moment the two
    counts disagree: zip truncates, and a truncated surface is a surface the
    gate silently stopped watching. Hence the position walk and the loud
    failure on anything other than exactly one method per path.
    """
    pm = [(m.start(), m.group(1)) for m in path_re.finditer(text)]
    mm = [(m.start(), m.group(1)) for m in method_re.finditer(text)]
    if len(pm) != len(mm):
        raise GateConfigError(
            "%s: %d path literals but %d method literals -- the generated "
            "shape changed; refusing to report PASS on a surface it could "
            "not read." % (where, len(pm), len(mm)))
    pairs = []
    for i, (pos, path) in enumerate(pm):
        nxt = pm[i + 1][0] if i + 1 < len(pm) else float("inf")
        cand = [m for (mpos, m) in mm if pos < mpos < nxt]
        if len(cand) != 1:
            raise GateConfigError(
                "%s: the path `%s` has %d candidate methods (expected 1); "
                "refusing to guess." % (where, path, len(cand)))
        pairs.append((cand[0].lower(), path.strip()))
    return pairs


# ---------------------------------------------------------------------------
# inputs
# ---------------------------------------------------------------------------

def load_matrix(docs_root):
    matrix = s18.load_yaml(os.path.join(docs_root, MATRIX_REL))
    targets = matrix.get("targets")
    if not isinstance(targets, list) or not targets:
        raise GateConfigError("%s declares no targets" % MATRIX_REL)
    by_id = {}
    for t in targets:
        tid = t.get("id")
        if tid not in END_IDS:
            continue
        roles = t.get("token-roles") or [t.get("token-role")]
        roles = [str(r).strip().lower() for r in roles if r]
        if not roles:
            raise GateConfigError(
                "target %s declares no token-role(s); this gate cannot decide "
                "what that end may call" % tid)
        by_id[tid] = {
            "roles": set(roles),
            "exclude_rows": set(str(r).strip() for r in (t.get("exclude-contract-rows") or [])),
        }
    missing = [e for e in END_IDS if e not in by_id]
    if missing:
        raise GateConfigError(
            "%s does not declare: %s -- FACE 1 would check fewer ends than "
            "the repository ships" % (MATRIX_REL, ", ".join(missing)))
    return by_id


def iter_sdk_files(sdk_dir, cfg):
    name_re = re.compile(cfg["files"], re.IGNORECASE)
    out = []
    for dirpath, dirnames, filenames in os.walk(sdk_dir):
        dirnames[:] = [d for d in dirnames if d not in ("node_modules", ".git")]
        for fn in filenames:
            if fn in cfg["skip"] or not name_re.search(fn):
                continue
            out.append(os.path.join(dirpath, fn))
    return sorted(out)


def read_surface(sdk_dir, end_id):
    """Return {(method, path): [files]} found in the generated product."""
    cfg = EXTRACTORS[end_id]
    path_re = re.compile(cfg["pattern"])
    method_re = re.compile(cfg["method_pattern"]) if not cfg["paired"] else None
    surface = {}
    for fp in iter_sdk_files(sdk_dir, cfg):
        text = s18._read_text(fp)
        pairs = []
        if cfg["paired"]:
            for path, method in path_re.findall(text):
                pairs.append((method.lower(), path.strip()))
        else:
            pairs.extend(pair_paths_methods(text, path_re, method_re,
                                            os.path.relpath(fp, sdk_dir)))
        for key in pairs:
            surface.setdefault(key, []).append(os.path.relpath(fp, sdk_dir))
    return surface


def end_scope(ops, roles, end_id):
    """The (method, path) pairs this end may call, per the contract itself."""
    out = {}
    for op in ops:
        if not (set(op["roles"]) & roles):
            continue
        if end_id == "client-mp" and op["client_forbidden"]:
            continue
        out.setdefault((op["method"], op["path"]), set()).add(op["row"])
    return out


# ---------------------------------------------------------------------------
# faces
# ---------------------------------------------------------------------------

def face_1_ends_present(docs_root, matrix):
    """Every declared end must have a non-empty generated product."""
    audit = {"ends_declared": len(matrix), "ends_present": 0, "files_scanned": 0}
    violations = []
    for end_id in END_IDS:
        sdk_dir = os.path.join(docs_root, SDK_ROOT_REL, end_id)
        if not os.path.isdir(sdk_dir):
            violations.append({
                "kind": "sdk-absent",
                "path": end_id,
                "file": SDK_ROOT_REL + "/" + end_id,
                "detail": "declared in the matrix but no generated product is "
                          "present; FACE 2 would check nothing for this end",
            })
            continue
        files = iter_sdk_files(sdk_dir, EXTRACTORS[end_id])
        if not files:
            violations.append({
                "kind": "sdk-empty",
                "path": end_id,
                "file": SDK_ROOT_REL + "/" + end_id,
                "detail": "the generated product carries no source files; the "
                          "generator did not run",
            })
            continue
        audit["ends_present"] += 1
        audit["files_scanned"] += len(files)
    audit["violations"] = len(violations)
    return violations, audit


def face_2_surface_within_scope(docs_root, matrix, ops, surfaces):
    """SAFETY DIRECTION: nothing may be generated that this end cannot call."""
    violations = []
    audit = {"ends_checked": 0, "operations_generated": 0, "violations": 0}
    for end_id in END_IDS:
        surface = surfaces.get(end_id) or {}
        scope = end_scope(ops, matrix[end_id]["roles"], end_id)
        audit["ends_checked"] += 1
        audit["operations_generated"] += len(surface)
        for (method, path), files in sorted(surface.items()):
            if (method, path) in scope:
                continue
            violations.append({
                "kind": "generated-outside-role-scope",
                "path": "%s %s" % (method.upper(), path),
                "file": SDK_ROOT_REL + "/" + end_id + "/" + files[0],
                "detail": "%s exposes an operation its token-roles (%s) cannot "
                          "call. The contract says this end must not reach it; "
                          "shipping it in the bundle is a capability leak."
                          % (end_id, "/".join(sorted(matrix[end_id]["roles"]))),
            })
    audit["violations"] = len(violations)
    return violations, audit


def face_3_scope_covered(docs_root, matrix, ops, surfaces):
    """
    COVERAGE DIRECTION: what the end may call but the SDK does not expose.

    INFORMATION, not a violation -- on purpose. S1-6 FACE 2 and S1-8 FACE 1
    both chose subset over equality, and the reason is recorded verbatim in
    `client-zero-derived-gate.py`: an equality gate that "cries wolf on correct
    content gets switched off". A missing generated operation can be legitimate
    (a row the generator is told to skip); turning that into a hard failure
    would buy one finding at the cost of the gate's credibility.

    What is NOT legitimate is leaving it unprinted. The count goes into the
    audit and every missing (method, path) is listed, so "nothing to report"
    can never be confused with "did not look".
    """
    missing_by_end = {}
    audit = {"ends_checked": 0, "operations_in_scope": 0, "operations_missing": 0,
             "violations": 0}
    for end_id in END_IDS:
        surface = surfaces.get(end_id) or {}
        scope = end_scope(ops, matrix[end_id]["roles"], end_id)
        audit["ends_checked"] += 1
        audit["operations_in_scope"] += len(scope)
        gaps = sorted(set(scope) - set(surface))
        if gaps:
            missing_by_end[end_id] = gaps
            audit["operations_missing"] += len(gaps)
    audit["violations"] = 0
    audit["_missing_by_end"] = {e: ["%s %s" % (m.upper(), p) for m, p in v]
                                for e, v in missing_by_end.items()}
    return [], audit


def face_4_exclude_rows_are_effective(docs_root, matrix, ops):
    """
    DEAD DECLARATIONS: an `exclude-contract-rows` entry must bite.

    If a row is already unreachable for that end -- no operation of that row is
    callable by the end's token-roles -- then listing it under
    `exclude-contract-rows` declares a protection the contract already
    provides. It reads like a control and is not one: delete the line and
    nothing changes. That is the registered DEAD-rows open question, and it
    matters because a matrix full of no-op declarations makes the real ones
    impossible to audit.

    🛑 THE ONE EXEMPTION, AND WHY IT IS NOT A LOOPHOLE
    A row the contract marks `x-client-forbidden` is a different case. The
    SDK pipeline's own `check_matrix_consistency()` fails the build when such a
    row is NOT listed in client-mp's `exclude-contract-rows` -- so that entry
    is consumed by a live check in the other direction (completeness, not
    effect). It is a MIRROR of the contract, not a dead declaration: removing
    it turns a different gate red.

    Those are counted as `mirror_exclusions` and printed, never silently
    skipped. Everything else that does not bite is a violation.
    """
    violations = []
    audit = {"exclusions_checked": 0, "effective_exclusions": 0,
             "mirror_exclusions": 0, "dead_exclusions": 0, "violations": 0}
    client_forbidden_rows = {op["row"] for op in ops
                             if op["client_forbidden"] and op["row"]}
    for end_id in END_IDS:
        roles = matrix[end_id]["roles"]
        reachable_rows = set()
        for op in ops:
            if set(op["roles"]) & roles:
                reachable_rows.add(op["row"])
        for row in sorted(matrix[end_id]["exclude_rows"]):
            audit["exclusions_checked"] += 1
            if row in reachable_rows:
                audit["effective_exclusions"] += 1
                continue
            if end_id == "client-mp" and row in client_forbidden_rows:
                audit["mirror_exclusions"] += 1
                continue
            audit["dead_exclusions"] += 1
            violations.append({
                "kind": "dead-exclusion",
                "path": row,
                "file": MATRIX_REL,
                "detail": "%s excludes row %s, but no operation of that row is "
                          "callable by this end's token-roles (%s), and no "
                          "other check consumes the declaration -- it changes "
                          "nothing. Either drop it, or the contract's roles "
                          "for that row are wrong."
                          % (end_id, row, "/".join(sorted(roles))),
            })
    audit["violations"] = len(violations)
    return violations, audit


def dead_row_report(ops, matrix):
    """
    B-4 data: for each end, the rows that can never take effect, WITH the
    reason. Not a face -- this is the register the open question asked for.
    """
    all_rows = sorted({op["row"] for op in ops if op["row"]})
    client_forbidden_rows = {op["row"] for op in ops
                             if op["client_forbidden"] and op["row"]}
    report = {}
    for end_id in END_IDS:
        roles = matrix[end_id]["roles"]
        excluded = matrix[end_id]["exclude_rows"]
        role_ok = {op["row"] for op in ops if set(op["roles"]) & roles}
        by_reason = {"role-disjoint": [], "mirror-of-client-forbidden": [],
                     "dead-declaration": []}
        for row in all_rows:
            if row in role_ok:
                continue
            if row not in excluded:
                by_reason["role-disjoint"].append(row)
            elif end_id == "client-mp" and row in client_forbidden_rows:
                # Excluded AND mirrored from the contract: consumed by the
                # pipeline's completeness check, so it is not dead -- see
                # face_4 for the full reasoning.
                by_reason["mirror-of-client-forbidden"].append(row)
            else:
                by_reason["dead-declaration"].append(row)
        report[end_id] = by_reason
    return report


def face_5_self_check(ops, matrix, surfaces):
    """A face with zero inputs is not a check."""
    problems = []
    if len(ops) < 40:
        problems.append({
            "kind": "self-check",
            "detail": "the contract yielded %d operations; it freezes 45. A "
                      "reader that finds far fewer has stopped matching the "
                      "file's shape and every face above would pass on "
                      "nothing." % len(ops),
        })
    for end_id in END_IDS:
        if not surfaces.get(end_id):
            problems.append({
                "kind": "self-check",
                "detail": "%s contributed 0 generated operations; FACE 2 and "
                          "FACE 3 cannot speak for this end." % end_id,
            })
        if not matrix[end_id]["roles"]:
            problems.append({
                "kind": "self-check",
                "detail": "%s declares no token-roles." % end_id,
            })
    return problems


# ---------------------------------------------------------------------------
def write_report(path, docs_root, violations, audit, dead):
    """
    Write the machine-readable report.

    🛑 Why this is implemented rather than left as an accepted-but-unused flag:
    Maven's `validate` binding passes `--json-report`, and a gate that accepts
    the flag and ignores it produces no artifact at all -- the build looks
    instrumented while nothing is recorded, which is the same "looks checked,
    isn't" shape as every other silent gap in this repository.
    """
    payload = {
        "gate": "s1-9-sdk-surface",
        "docs_root": docs_root,
        "verdict": "FAIL" if violations else "PASS",
        "violation_count": len(violations),
        "violations": violations,
        "audit": audit,
        "dead_rows": dead,
    }
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory, exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main(argv=None):
    s18.harden_console_encoding()
    parser = argparse.ArgumentParser(description="S1-9 SDK surface gate")
    parser.add_argument("--docs-root", default=None,
                        help="docs root: the directory containing contract/. "
                             "Auto-detected when omitted.")
    parser.add_argument("--repo-root", default=os.getcwd(),
                        help="build root used only to auto-detect --docs-root")
    parser.add_argument("--json-report", default=None)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args(argv)

    def emit(msg):
        if not args.quiet:
            print(msg)

    emit("=" * 79)
    emit("S1-9 SDK SURFACE GATE (generated SDKs vs the frozen contract)")

    try:
        docs_root = s18.resolve_docs_root(os.path.abspath(args.repo_root),
                                          args.docs_root)
    except GateConfigError as exc:
        print("[GATE-ERROR] %s" % exc, file=sys.stderr)
        print("[SDK-SURFACE-GATE] FAIL (misconfigured, no PASS printed)",
              file=sys.stderr)
        return EXIT_MISCONFIGURED

    emit("docs-root : %s" % docs_root)
    emit("")

    try:
        doc = s18.load_contract(docs_root)
        ops = s18.contract_operations(doc)
        matrix = load_matrix(docs_root)
        surfaces = {}
        for end_id in END_IDS:
            sdk_dir = os.path.join(docs_root, SDK_ROOT_REL, end_id)
            surfaces[end_id] = (read_surface(sdk_dir, end_id)
                                if os.path.isdir(sdk_dir) else {})
    except GateConfigError as exc:
        print("[GATE-ERROR] %s" % exc, file=sys.stderr)
        print("[SDK-SURFACE-GATE] FAIL (misconfigured, no PASS printed)",
              file=sys.stderr)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        print("[GATE-ERROR] internal error: %r" % exc, file=sys.stderr)
        return EXIT_INTERNAL

    violations, audit = [], {}
    for name, fn in (
        ("FACE 1 ends-declared-and-present",
         lambda: face_1_ends_present(docs_root, matrix)),
        ("FACE 2 generated-surface-within-role-scope",
         lambda: face_2_surface_within_scope(docs_root, matrix, ops, surfaces)),
        ("FACE 3 scope-covered-by-surface",
         lambda: face_3_scope_covered(docs_root, matrix, ops, surfaces)),
        ("FACE 4 exclude-rows-are-effective",
         lambda: face_4_exclude_rows_are_effective(docs_root, matrix, ops)),
    ):
        got, got_audit = fn()
        violations.extend(got)
        audit[name] = got_audit

    problems = face_5_self_check(ops, matrix, surfaces)
    audit["FACE 5 gate-self-check"] = {"problems": len(problems)}

    emit("FACE AUDIT (a face with 0 inputs is not a check)")
    emit("-" * 79)
    for key in sorted(audit):
        stats = ", ".join("%s=%s" % (k, v) for k, v in sorted(audit[key].items())
                          if not k.startswith("_"))
        emit("  %-42s %s" % (key, stats))
    emit("")

    dead = dead_row_report(ops, matrix)
    if args.json_report:
        write_report(args.json_report, docs_root, violations, audit, dead)

    emit("DEAD-ROW REGISTER (B-4: rows that can never take effect, with reason)")
    emit("-" * 79)
    for end_id in END_IDS:
        r = dead[end_id]
        emit("  %-14s role-disjoint=%d  mirror-of-client-forbidden=%d  "
             "dead-declaration=%d"
             % (end_id, len(r["role-disjoint"]),
                len(r["mirror-of-client-forbidden"]),
                len(r["dead-declaration"])))
        emit("      role-disjoint              : %s" % (", ".join(r["role-disjoint"]) or "-"))
        emit("      mirror-of-client-forbidden : %s" % (", ".join(r["mirror-of-client-forbidden"]) or "-"))
        emit("      dead-declaration           : %s" % (", ".join(r["dead-declaration"]) or "-"))
    emit("")

    if problems:
        print("[GATE-ERROR] the gate's own inputs failed validation:", file=sys.stderr)
        for p in problems:
            print("  - %s" % p["detail"], file=sys.stderr)
        print("[SDK-SURFACE-GATE] FAIL (misconfigured, no PASS printed)",
              file=sys.stderr)
        return EXIT_MISCONFIGURED

    missing = audit["FACE 3 scope-covered-by-surface"].get("_missing_by_end") or {}
    if missing:
        emit("NOTE (information, not a violation):")
        emit("  FACE 3 checks subset, not equality -- deliberately, and for the")
        emit("  reason recorded verbatim in client-zero-derived-gate.py: an")
        emit("  equality gate that cries wolf on correct content gets switched")
        emit("  off. These are printed so an empty report can never be mistaken")
        emit("  for a check that was not run:")
        for end_id in END_IDS:
            gaps = missing.get(end_id) or []
            if gaps:
                emit("    %-14s in scope but not generated (%d):"
                     % (end_id, len(gaps)))
                for g in gaps:
                    emit("      %s" % g)
        emit("")

    if violations:
        emit("VIOLATIONS")
        emit("-" * 79)
        for v in violations:
            where = v.get("file")
            subject = v.get("path") or where or "?"
            emit("  [%s] %s" % (v.get("kind", "unclassified"), subject))
            emit("        %s" % v.get("detail", ""))
        emit("")
        emit("[SDK-SURFACE-GATE] FAIL  faces=5  violations=%d" % len(violations))
        return EXIT_VIOLATION

    emit("no violations found on any face")
    emit("")
    emit("[SDK-SURFACE-GATE] PASS  faces=5  violations=0  operations=%d  "
         "ends=%d" % (len(ops), len(END_IDS)))
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())
