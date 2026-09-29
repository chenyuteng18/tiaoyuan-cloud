#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S1-6 client zero-derived regression gate.

THE DEFECT THIS EXISTS FOR
==========================
This gate was written because a green build was lying. On 2026-09-24 the
ADR-12 compliance scan reported PASS for the client package while that package
carried, in its own outbound allow-list:

    /api/v1/band/derived

an endpoint the frozen contract denies to the client outright (row E4,
x-callable-roles [therapist, meridian, admin], x-client-explicitly-denied: true).
A second endpoint list entry set did not exist in the contract at all, and three
more were plausible-but-wrong names. Eight entries, two correct.

No ADR-12 scan face could have caught the first one, and that is the whole point:

  * a WORD scan fires on vocabulary, and the forbidden path contained no word
    the wordlist held -- a path can be the wrong path without containing a
    forbidden word;
  * the allow-list was itself the artefact that was wrong. Scanning the client
    package for forbidden words cannot detect that the allow-list permits a
    forbidden thing, because the allow-list is not a word, it is a claim.
  * The claim was checkable all along, against the contract, and nothing checked
    it. Two hand-maintained lists of the same fact -- the bundle's allow-list and
    the contract's x-callable-roles -- drifted apart, silently, in the direction
    that opens a hole.

So this gate checks the CLAIMS, not the vocabulary:

  FACE 1  no-zero-derived-names
           Every derived field name the contract rules client-invisible is
           absent from the client package. The forbidden set is DERIVED from
           contract/visibility/band-visibility-matrix.json (every field of every
           field group whose `client` value is false) -- it is not a second
           hand-maintained list, because a second hand-maintained list is exactly
           the defect being fixed.

  FACE 2  allowlist-is-a-subset-of-the-contract
           Every client allow-list entry must be a path the contract permits the
           client to call. This is a SUBSET check, one direction only, and the
           direction is the whole point: a bundle may legitimately use fewer
           paths than it is permitted, but it must never be able to reach one it
           is forbidden. An equality check was tried first and rejected -- it
           fails the build for every permitted-but-unused endpoint, which is not
           a defect, and a gate that cries wolf on correct content gets switched
           off. The permitted-but-undeclared delta is still REPORTED, as
           information, because it is how an intended path goes missing.

  FACE 3  no-client-forbidden-paths
           No path carrying x-client-forbidden: true appears in the client
           package (contract section 2.7, the refund domain: "the bundle must
           not contain any path of this domain").

  FACE 4  gate-self-check
           The gate's own inputs are non-empty and were actually read. A face
           with zero derived fields, zero contract paths, or zero scanned files
           reports success while scanning nothing. This is asserted, not assumed:
           it is the failure mode the ADR-12 README calls "the gate that rotted".

HONEST SCOPE -- WHAT THIS GATE DOES NOT DO
==========================================
It does not replace the ADR-12 scan faces, and it does not scan for wording. It
checks structural claims about the client package against the contract. The two
are complementary: ADR-12 catches a forbidden WORD reaching the bundle; this gate
catches a forbidden THING being permitted by a bundle artefact, even when its
name is innocuous. Neither subsumes the other, and this gate deliberately does
NOT add, remove or rename an ADR-12 scan face -- the manifest asserts that list
is fixed at three, and adding a fourth from here would be surface drift.

It also does not decide contested questions. Where the contract is silent or the
ruling is open, this gate registers the question rather than resolving it -- see
the OPEN QUESTIONS section of compliance/README.md.

EXIT CODES
==========
  0  pass
  1  a violation (a derived name in the bundle, an allow-list mismatch, a
     client-forbidden path in the bundle)
  2  the gate is misconfigured -- a truth source is absent, unparseable, empty,
     or a scanned root is missing. Refusing to pass is the correct behaviour:
     a gate that cannot read its inputs must look like a red build, not a clean
     bill of health.
  3  internal error

USAGE
=====
    python compliance/client-zero-derived-gate.py --repo-root .
    python compliance/client-zero-derived-gate.py --repo-root . \
        --json-report compliance/reports/s16-zero-derived.json

TWO ROOTS, BECAUSE THE CONTRACT AND THE BUNDLE DO NOT LIVE TOGETHER
===================================================================
`client-package/` and `compliance/` sit under the build root (this directory),
while `contract/` sits one level ABOVE it, beside the PRD and the upstream
freeze document. The ADR-12 scan only needs the build root, which is why this
asymmetry did not show up until a gate had to read both.

So this gate takes two roots:

  --repo-root   the BUILD root: contains client-package/ and compliance/
                (default: the current working directory, matching scan_compliance)
  --docs-root   the DOCS root: contains contract/
                (default: auto-detected -- --repo-root, then its parents)

Auto-detection walks up to five levels and REFUSES TO RUN if no root holds both
contract files. It does not silently fall back to the build root, because the
failure mode this whole gate exists for is a scan that quietly checked the wrong
place and reported PASS. Use --docs-root to override when the layout differs.
"""

import argparse
import io
import json
import os
import re
import sys

# ---------------------------------------------------------------------------
# Exit codes. Mirror scan_compliance.py deliberately: every non-zero means "the
# build must fail", and 2 specifically means "the gate itself is broken".
# ---------------------------------------------------------------------------
EXIT_PASS = 0
EXIT_VIOLATION = 1
EXIT_MISCONFIGURED = 2
EXIT_INTERNAL = 3

# ---------------------------------------------------------------------------
# Inputs. These are the SAME truth sources the Java-side ContractFreezeGateTest
# reads, resolved the same way, so that the Python and Java gates cannot disagree
# about which file is authoritative.
#
# Two roots are needed. The CONTRACT_* paths are relative to the DOCS root (the
# parent of the build root); the CLIENT_* paths are relative to the BUILD root.
# ---------------------------------------------------------------------------
MATRIX_REL = "contract/visibility/band-visibility-matrix.json"
OPENAPI_REL = "contract/openapi-v1.0.0.yaml"
ALLOWLIST_REL = "client-package/api/clientPaths.js"
CLIENT_ROOT_REL = "client-package"

# 🛑 第二个扫描根 = 真实的客户小程序工程（G-B 的 B-3，2026-09-28）。
# 在它加入之前，`client-package` 只是**样例包**：纪律覆盖的是样例，而真正会
# 被编译上传的工程是一个**无人扫描的词面**。这是"清单腐烂"的同型风险 ——
# 门禁照常打印 PASS，而它守护的对象根本不在扫描面里。
# 两个根都要求存在：少一个就 exit 2。若允许"存在即扫、不存在即跳过"，
# 那么有人把这个工程挪走后门禁会安静地少扫一半，与本仓的纪律相反。
CLIENT_ROOTS_REL = ("client-package", "frontends/client-mp/miniprogram")

# 🛑 扫描根的数量是【冻结】的，与 ADR-12 的三面清单同理（清单漂移即拒绝运行）。
# 为什么连数量都要冻：只要求"列出的根都存在"是不够的 —— 有人把第二个根
# **从列表里删掉**，列表里剩下的根确实都在，门禁照样 PASS，而扫描面悄悄少了一半。
# 这正是本仓反复修的"静默少查"。故这里既要求每个根存在，也要求根的数量等于
# 冻结值；改这个数字必须同时改 `EXPECTED_CLIENT_ROOTS` 并说明理由。
EXPECTED_CLIENT_ROOTS = 2


def client_scan_roots(repo_root):
    """Every directory this gate must scan. Raises if any is missing."""
    if len(CLIENT_ROOTS_REL) != EXPECTED_CLIENT_ROOTS:
        raise GateConfigError(
            "the client scan-root list holds %d entries but %d are frozen. "
            "Removing a root shrinks the scanned surface while every remaining "
            "root still exists, so it would pass silently -- the gate refuses "
            "instead." % (len(CLIENT_ROOTS_REL), EXPECTED_CLIENT_ROOTS))
    roots = []
    for rel in CLIENT_ROOTS_REL:
        root = os.path.join(repo_root, rel)
        if not os.path.isdir(root):
            raise GateConfigError(
                "client scan root not found: %s -- refusing to pass. A gate "
                "that scans a smaller surface than it claims reports success."
                % root)
        roots.append(root)
    return roots

# How far up to look for the docs root before giving up. Deliberately small and
# explicit: an unbounded walk would eventually find SOME contract/ on a shared
# machine and verify the wrong repository.
DOCS_ROOT_SEARCH_DEPTH = 5

# The matrix keys whose `client` value must be false for the group's fields to be
# client-forbidden. Named explicitly rather than "everything with client: false"
# so that a NEW field group added to the matrix cannot be silently assumed
# client-visible: it is enumerated here, and an unknown group is a hard failure.
#
# ③ gap_reason and ④ derived_result are the two groups with client=false. They
# are the contract's own hard boundary (matrix `_hard_boundary`: "③④ 为硬约束，
# 不得通过配置放开").
CLIENT_FORBIDDEN_GROUPS = ("gap_reason", "derived_result")

# Mirror of scan_compliance.py's TEXT_SUFFIXES. Duplicated on purpose: importing
# the scanner to borrow this constant would make this gate's coverage change
# whenever the scanner's does, which hides exactly the drift both exist to catch.
# The duplication is ASSERTED equal by FACE 4 rather than trusted.
TEXT_SUFFIXES = frozenset({
    ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".vue", ".json", ".json5",
    ".wxml", ".wxss", ".wxss.js", ".wxs", ".html", ".htm", ".xml", ".yml",
    ".yaml", ".properties", ".java", ".kt", ".csv", ".txt", ".md", ".ini",
    ".cfg", ".conf", ".po", ".pot", ".strings", ".ftl", ".jsp", ".sql",
})

EXCLUDED_DIRS = frozenset({
    "node_modules", "target", "dist", "build", ".git", ".svn", "__pycache__",
})


class GateConfigError(Exception):
    """The gate's own inputs are unusable. Maps to EXIT_MISCONFIGURED."""


# ===========================================================================
# Input loading
# ===========================================================================

def _read_text(path):
    """
    Read a file as text. UTF-8 first (BOM tolerated), then CP936, then latin-1 as
    a last resort so a hit is still REPORTED rather than crashing the gate.

    This machine's platform encoding is GBK; the contract's comments are Chinese,
    so relying on the platform default would either mangle the text or raise.
    """
    if not os.path.isfile(path):
        raise GateConfigError("required file is absent: %s" % path)
    with open(path, "rb") as handle:
        blob = handle.read()
    for encoding in ("utf-8-sig", "cp936", "latin-1"):
        try:
            return blob.decode(encoding)
        except UnicodeDecodeError:
            continue
    return blob.decode("latin-1", errors="replace")


def resolve_docs_root(build_root, explicit=None):
    """
    Find the root that holds the frozen contract.

    The contract sits beside the PRD, one level above the build root. This
    function walks up looking for BOTH contract files and refuses to guess: if it
    cannot find them it raises, because "scan the wrong directory and report
    PASS" is precisely the defect this gate exists to close.
    """
    def holds(root):
        return (os.path.isfile(os.path.join(root, MATRIX_REL)) and
                os.path.isfile(os.path.join(root, OPENAPI_REL)))

    if explicit:
        candidate = os.path.abspath(explicit)
        if not holds(candidate):
            raise GateConfigError(
                "--docs-root %s does not hold both %s and %s. Point it at the "
                "directory containing contract/." % (candidate, MATRIX_REL, OPENAPI_REL))
        return candidate

    cursor = os.path.abspath(build_root)
    for _ in range(DOCS_ROOT_SEARCH_DEPTH):
        if holds(cursor):
            return cursor
        parent = os.path.dirname(cursor)
        if parent == cursor:
            break
        cursor = parent

    raise GateConfigError(
        "could not locate the docs root: no directory within %d levels above %s "
        "holds %s. Refusing to run -- a gate that reads the wrong root reports "
        "PASS while checking nothing. Pass --docs-root explicitly."
        % (DOCS_ROOT_SEARCH_DEPTH, os.path.abspath(build_root), MATRIX_REL))


def load_matrix(docs_root):
    """
    Load the client-forbidden field names from the contract visibility matrix.

    This is the DERIVED source. The whole reason this gate exists is that a
    hand-maintained second list of the same fact drifts away from the first one.
    Reading the matrix mechanically means the forbidden set is whatever the
    contract says it is, today, with no transcription step to get wrong.
    """
    path = os.path.join(docs_root, MATRIX_REL)
    raw = _read_text(path)
    try:
        doc = json.loads(raw)
    except ValueError as exc:
        raise GateConfigError(
            "%s is not parsable JSON: %s\nThe forbidden field set cannot be "
            "derived, so this gate refuses to report PASS." % (MATRIX_REL, exc)
        )
    groups = doc.get("field_groups")
    if not isinstance(groups, dict) or not groups:
        raise GateConfigError(
            "%s has no usable field_groups mapping (got %r). An empty "
            "forbidden set would make FACE 1 scan for nothing and pass."
            % (MATRIX_REL, type(groups).__name__)
        )

    forbidden = {}
    for group in CLIENT_FORBIDDEN_GROUPS:
        if group not in groups:
            raise GateConfigError(
                "%s no longer declares field group %r. The group list is part of "
                "this gate's contract (③ gap_reason / ④ derived_result); a group "
                "that has been renamed or removed must be re-registered here "
                "deliberately, not silently skipped." % (MATRIX_REL, group)
            )
        spec = groups[group]
        if not isinstance(spec, dict):
            raise GateConfigError("%s: group %r is not a mapping"
                                  % (MATRIX_REL, group))
        fields = spec.get("fields")
        if not isinstance(fields, list) or not fields:
            raise GateConfigError(
                "%s: group %r declares no fields. Scanning for an empty set "
                "reports success without checking anything." % (MATRIX_REL, group)
            )
        for name in fields:
            if not isinstance(name, str) or not name.strip():
                raise GateConfigError("%s: group %r has a non-string field name: %r"
                                      % (MATRIX_REL, group, name))
            forbidden.setdefault(name.strip(), group)

    # Cross-check the matrix's own client gate. If a group is listed here as
    # client-forbidden but the matrix says the client MAY see it, the two
    # disagree and the derived set is not trustworthy.
    matrix = doc.get("matrix") or {}
    for group in CLIENT_FORBIDDEN_GROUPS:
        client_cell = (matrix.get("client") or {}).get(group)
        if client_cell is not False:
            raise GateConfigError(
                "%s: group %r is treated as client-forbidden by this gate, but "
                "matrix.client.%s = %r (expected false). The matrix and this "
                "gate's group list disagree." % (MATRIX_REL, group, group, client_cell)
            )
    return forbidden, doc


def _parse_yaml_minimal(text):
    """
    Extract every OPERATION's three attributes, WITHOUT a YAML parser.

    Why not a real parser: this gate must run in CI next to the ADR-12 scan,
    which is stdlib-only, and PyYAML is not guaranteed present there. The Java
    ContractFreezeGateTest does the authoritative snakeyaml parse; this gate needs
    exactly three scalar attributes per operation, and it VALIDATES that it found
    a plausible document rather than assuming the regexes worked.

    Attributes needed per operation:
      x-contract-row       -> cited in the report
      x-callable-roles     -> is `client` allowed?
      x-client-forbidden   -> is the path banned from the bundle?

    ONE OPERATION PER METHOD, NOT ONE PER PATH. This is the whole reason the
    function is written defensively: five contract paths carry more than one
    operation, and TWO of them declare DIFFERENT roles per method --

        /customers/{id}/visits          POST D1 [therapist, meridian, admin]
                                        GET  D2 [client, therapist, ...]
        /customers/{id}/intake-profile  GET  B5 [client, therapist, ...]
                                        PATCH B6 [therapist, meridian, admin]

    A parser that records one attribute set per path silently reads D1 and
    concludes the client may not call the path -- a FALSE POSITIVE that would have
    failed this build on a correct allow-list entry. It was hit for real on the
    first run.

    Structure relied on: `paths:` at column 0, `  /path:` at 2 spaces, the HTTP
    method at 4 spaces, operation attributes at 6 spaces. The caller asserts the
    result is non-trivial, so a failed regex is a config error rather than a
    silent empty set.
    """
    ops = []
    current_path = None
    current_op = None
    in_paths = False
    paths_indent = None
    method_re = re.compile(r'^(\s+)(get|post|put|patch|delete|head|options):\s*$',
                           re.IGNORECASE)
    for line in text.splitlines():
        if not in_paths:
            if re.match(r'^paths:\s*$', line):
                in_paths = True
            continue
        # A new top-level key ends the paths block.
        if re.match(r'^[A-Za-z_][A-Za-z0-9_-]*:', line):
            break

        m = re.match(r'^(\s+)(/[^:\s]+):\s*$', line)
        if m:
            indent = len(m.group(1))
            if paths_indent is None:
                paths_indent = indent
            if indent == paths_indent:
                current_path = m.group(2)
                current_op = None
                continue

        if current_path is None:
            continue

        mm = method_re.match(line)
        if mm:
            current_op = {"path": current_path, "method": mm.group(2).lower(),
                          "row": None, "roles": None, "client_forbidden": None}
            ops.append(current_op)
            continue

        if current_op is None:
            continue
        for attr, key in (("x-contract-row", "row"),
                          ("x-callable-roles", "roles"),
                          ("x-client-forbidden", "client_forbidden")):
            ma = re.match(r'^\s+%s:\s*(.+?)\s*$' % re.escape(attr), line)
            if ma and current_op[key] is None:
                current_op[key] = ma.group(1)
    return ops


def load_contract_ops(docs_root):
    """Load every contract operation with the three attributes this gate needs."""
    text = _read_text(os.path.join(docs_root, OPENAPI_REL))
    ops = _parse_yaml_minimal(text)
    if len(ops) < 10:
        raise GateConfigError(
            "%s parsed to only %d operations. The contract freezes 45; a parse "
            "that finds almost nothing means the file's shape changed and this "
            "gate's extraction no longer works. Refusing to report PASS on a "
            "contract it could not read." % (OPENAPI_REL, len(ops))
        )
    missing = ["%s %s" % (o["method"].upper(), o["path"])
               for o in ops if o["roles"] is None]
    if missing:
        raise GateConfigError(
            "%s: %d operations carry no x-callable-roles, e.g. %s. That "
            "attribute is the authority for whether the client may call an "
            "operation; without it this gate cannot check FACE 2."
            % (OPENAPI_REL, len(missing), ", ".join(missing[:5]))
        )
    return ops


def client_callable_paths(ops):
    """
    The set of PATHS the client may call, aggregated across methods.

    Aggregation is deliberate and its direction matters. A path is client
    callable if ANY of its methods permits the client -- because the allow-list
    is keyed by PATH, not by (path, method). `/customers/{id}/visits` is the live
    example: POST D1 excludes the client, GET D2 includes it, and the bundle
    legitimately needs the path.

    Aggregating the other way (requiring every method to permit the client) would
    flag that correct entry as forbidden, which is the false positive this gate
    produced on its first run. The finer-grained fact -- WHICH method is
    permitted -- stays in the per-operation records and is where a method-level
    allow-list would need it; nothing here discards it.
    """
    return {o["path"] for o in ops if "client" in parse_roles(o["roles"])}


def contract_path_evidence(ops):
    """Per-path summary for the report: methods, contract rows, client callable."""
    summary = {}
    for o in ops:
        entry = summary.setdefault(o["path"], {"methods": [], "rows": [],
                                               "client": False,
                                               "client_forbidden": False})
        entry["methods"].append(o["method"].upper())
        if o["row"]:
            entry["rows"].append(o["row"])
        if "client" in parse_roles(o["roles"]):
            entry["client"] = True
        if o["client_forbidden"] == "true":
            entry["client_forbidden"] = True
    return summary


def parse_roles(raw):
    """Turn `[a, b, c]` into a lowercase set. Tolerates JSON-ish spelling."""
    if raw is None:
        return set()
    body = raw.strip()
    if body.startswith("[") and body.endswith("]"):
        body = body[1:-1]
    return {part.strip().strip('"\'').lower()
            for part in body.split(",") if part.strip()}


def load_client_allowlist(repo_root):
    """Extract CLIENT_ALLOWED_PATHS from the client package's own source."""
    path = os.path.join(repo_root, ALLOWLIST_REL)
    text = _read_text(path)
    m = re.search(r'CLIENT_ALLOWED_PATHS\s*=\s*Object\.freeze\(\s*\[(.*?)\]',
                  text, re.DOTALL)
    if not m:
        raise GateConfigError(
            "%s no longer contains a CLIENT_ALLOWED_PATHS array this gate can "
            "read. If the declaration was renamed, update ALLOWLIST_REL and this "
            "extraction together -- until then the allow-list is UNVERIFIED, "
            "which is the state that produced the S1-6 defect." % ALLOWLIST_REL)
    entries = re.findall(r"'(/api/v1[^']*)'", m.group(1))
    if not entries:
        raise GateConfigError(
            "%s: CLIENT_ALLOWED_PATHS parsed to zero paths. An allow-list with "
            "no entries scans nothing; refusing to report PASS." % ALLOWLIST_REL)
    if len(entries) != len(set(entries)):
        dupes = sorted({p for p in entries if entries.count(p) > 1})
        raise GateConfigError("%s: duplicate allow-list entries: %s"
                              % (ALLOWLIST_REL, ", ".join(dupes)))
    return entries


def iter_source_files(root):
    """Yield every scannable text file under root, deterministically ordered."""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(
            d for d in dirnames
            if d not in EXCLUDED_DIRS and not d.startswith(".cache")
        )
        for filename in sorted(filenames):
            _, ext = os.path.splitext(filename)
            if ext.lower() in TEXT_SUFFIXES:
                found.append(os.path.join(dirpath, filename))
    return found


# ===========================================================================
# Matching
# ===========================================================================

def identifier_segments(token):
    """
    Split an identifier into lowercase segments. `mp_band_derived_x` -> mp, band,
    derived, x. `mpBandDerivedX` -> mp, band, derived, x.

    camelCase IS SPLIT, and this mirrors `scan_compliance.identifier_segments`,
    which has always split it (`_CAMEL_RE`). This gate did NOT, which made it a
    real blind spot rather than a stylistic difference -- measured 2026-09-24:

        effect_verdict   -> caught, exit 1
        effectVerdict    -> NOT caught, [S1-6-GATE] PASS   <-- the JS spelling

    The bundle is JavaScript. camelCase is how it would carry a snake_case
    contract field, so the gate was blind in the dialect most likely to be used,
    while claiming in this very file's docstring to mirror the scanner's rules.
    An identifier run is kept intact through the split (`a3Value` -> [a3, value])
    so digit/letter runs still align with their snake_case form.
    """
    split = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", token)
    split = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", split)
    out = []
    for part in split.replace("-", "_").split("_"):
        if part:
            out.append(part.lower())
    return out


def contains_adjacent_run(haystack, needle):
    """True if `needle` occurs in `haystack` as a CONTIGUOUS run of segments."""
    if not needle or len(needle) > len(haystack):
        return False
    span = len(needle)
    for start in range(len(haystack) - span + 1):
        if haystack[start:start + span] == needle:
            return True
    return False


def line_spells_field(line, field_name):
    """
    Decide whether a line spells a derived field name.

    Matching semantics are mirrored from the ADR-12 scanner ON PURPOSE, so that
    a reader does not have to hold two different matching rules in their head.
    Mirrored semantics, not imported code: importing the scanner would couple
    this gate's detection surface to an unrelated file's future edits.

      * whole token equality        `effect_verdict`
      * segment equality            `effect` inside ... `effect_verdict_x`? no --
                                    a single-word field matches as a segment,
                                    so `a3` inside `mp_a3_tap` fires
      * contiguous multi-segment run `adherence_state` inside
                                    `client_adherence_state_key`

    A FRAGMENT never matches: `refundable` stays quiet because no level compares
    substrings of a segment. That is what keeps the gate explainable.
    """
    needle_segments = identifier_segments(field_name)
    needle_joined = "_".join(needle_segments)
    for token in re.findall(r"[A-Za-z0-9_\-\u4e00-\u9fff]+", line):
        segments = identifier_segments(token)
        if "_".join(segments) == needle_joined:
            return True
        if len(needle_segments) == 1:
            if needle_segments[0] in segments:
                return True
        elif contains_adjacent_run(segments, needle_segments):
            return True
    return False


# ===========================================================================
# Faces
# ===========================================================================

def face_1_no_zero_derived_names(repo_root, forbidden):
    """Every client-invisible derived field name must be absent from the bundle."""
    root = os.path.join(repo_root, CLIENT_ROOT_REL)
    if not os.path.isdir(root):
        raise GateConfigError(
            "client package root not found: %s -- refusing to pass. A gate that "
            "scans nothing reports success." % root)
    hits = []
    scanned = 0
    for root in client_scan_roots(repo_root):
        for path in iter_source_files(root):
            scanned += 1
            text = _read_text(path)
            rel = os.path.relpath(path, repo_root)
            for lineno, line in enumerate(text.splitlines(), start=1):
                if not line.strip():
                    continue
                for field_name, group in sorted(forbidden.items()):
                    if line_spells_field(line, field_name):
                        hits.append({"face": "no-zero-derived-names", "term": field_name,
                                     "group": group, "file": rel, "line": lineno,
                                     "text": line.strip()[:240]})
    return hits, {"files_scanned": scanned, "terms_checked": len(forbidden)}


def face_2_allowlist_within_contract(repo_root, ops, allowlist):
    """
    Every allow-list entry must be a path the contract permits the client to call.

    ONE direction, and it is the direction that matters. The bundle may use fewer
    paths than it is permitted to -- that is normal, not a defect -- but it must
    never be able to reach a path the contract denies. Every entry that is not
    client-callable is a violation, for one of two distinct reasons:

      * the path does not exist in the contract at all. The bundle invented a
        route. Nothing will ever answer it, and if something later does answer a
        similarly-named route, the bundle reaches it un-reviewed.
      * the path exists but is not client-callable. This is the S1-6 defect: an
        allow-list that permits a forbidden endpoint. A word-scan cannot see it,
        because the entry's NAME may be entirely innocent.

    The reverse delta (permitted but undeclared) is returned as INFORMATION, not
    as a violation. An equality check was implemented first and rejected: it
    fails the build for every endpoint the client is allowed to call but does not
    currently use, which is not wrong. A gate that fails on correct content is a
    gate that gets switched off.
    """
    client_paths = client_callable_paths(ops)
    if not client_paths:
        raise GateConfigError(
            "the contract yields ZERO client-callable paths. Every allow-list "
            "entry would then be reported as forbidden, which is a false alarm "
            "produced by a misparse -- refusing to run.")

    evidence = contract_path_evidence(ops)
    violations = []
    # The allow-list is authored WITH the /api/v1 base path (it is used verbatim
    # as a request path); the contract declares paths relative to `servers.url`.
    # Comparing the two without normalising is an off-by-a-prefix bug that
    # reports every single entry as missing -- which is how it was found.
    for raw in sorted(set(allowlist)):
        path = _strip_base(raw)
        entry = evidence.get(path)
        if entry is None:
            violations.append({
                "face": "allowlist-within-contract", "kind": "path-not-in-contract",
                "path": raw,
                "detail": "not present in %s at all" % OPENAPI_REL})
        elif not entry["client"]:
            # Report the per-method roles, because a path whose methods disagree
            # is exactly where a reader will otherwise draw the wrong conclusion.
            per_method = ", ".join(
                "%s %s=%s" % (o["method"].upper(), o["row"],
                              ", ".join(sorted(parse_roles(o["roles"]))))
                for o in sorted((c for c in ops if c["path"] == path),
                                key=lambda x: x["method"]))
            violations.append({
                "face": "allowlist-within-contract", "kind": "client-not-permitted",
                "path": raw,
                "detail": "no method permits the client: %s%s"
                          % (per_method,
                             "; x-client-forbidden=true"
                             if entry["client_forbidden"] else "")})

    undeclared = sorted(client_paths - {_strip_base(p) for p in allowlist})
    return violations, {"contract_client_paths": len(client_paths),
                        "allowlist_entries": len(set(allowlist)),
                        "permitted_but_undeclared": len(undeclared)}, undeclared


def _strip_base(path):
    """Drop the contract's servers.url base path so the two sides compare."""
    for base in ("/api/v1",):
        if path.startswith(base + "/"):
            return path[len(base):]
    return path


def face_3_no_client_forbidden_paths(repo_root, ops):
    """
    A path marked x-client-forbidden: true must not appear in the bundle.

    The contract says this of the whole refund domain (section 2.7): the bundle
    must contain no path, field name or wording of it. This face is the "path"
    half; ADR-12 scan face 1 is the "wording" half.
    """
    banned = sorted({o["path"] for o in ops if o["client_forbidden"] == "true"})
    if not banned:
        raise GateConfigError(
            "the contract declares ZERO x-client-forbidden paths. The refund "
            "domain is ruled fully client-forbidden, so a zero here means the "
            "attribute moved or the parse failed -- refusing to report PASS on "
            "a face that would check nothing.")
    hits = []
    for path in banned:
        # Compare on the path's literal segments, so a bundle that assembled the
        # path from parts is still caught: the leading segment of every banned
        # path is checked as a contiguous run, not as a substring.
        for root in client_scan_roots(repo_root):
            for candidate in iter_source_files(root):
                text = _read_text(candidate)
                rel = os.path.relpath(candidate, repo_root)
                for lineno, line in enumerate(text.splitlines(), start=1):
                    if not line.strip():
                        continue
                    if line_spells_path(line, path):
                        hits.append({"face": "no-client-forbidden-paths",
                                     "term": path, "file": rel, "line": lineno,
                                     "text": line.strip()[:240]})
    return hits, {"forbidden_paths_checked": len(banned)}


def line_spells_path(line, contract_path):
    """
    True if `line` spells `contract_path` (ignoring `{param}` names).

    Uses the same segment machinery as the field check: the path is split into
    its literal segments and looked for as a contiguous run, so
    `/api/v1/refunds/{id}/receipts` also fires on a bundle that wrote it as
    `api/v1/refunds/x/receipts` or built it from a base plus `refunds`.
    """
    literal = [seg for seg in contract_path.split("/")
               if seg and not (seg.startswith("{") and seg.endswith("}"))]
    if not literal:
        return False
    needle = [seg.lower() for seg in literal]
    for token in re.findall(r"[A-Za-z0-9_\-\u4e00-\u9fff]+", line):
        if contains_adjacent_run(identifier_segments(token), needle):
            return True
    return False


def face_4_self_check(repo_root, forbidden, ops, allowlist, contract_doc):
    """
    Assert this gate's own inputs are real, so a PASS means something.

    Every check below corresponds to a way a gate reports success while checking
    nothing -- the failure mode the ADR-12 README names "the gate that rotted".
    """
    problems = []
    if not forbidden:
        problems.append("derived field set is EMPTY: FACE 1 would scan for nothing")
    if len(ops) < 10:
        problems.append("contract parsed to %d operations" % len(ops))
    if not allowlist:
        problems.append("allow-list is EMPTY: FACE 2 would compare nothing")

    # The suffix set is duplicated from the ADR-12 scanner. Assert the two agree,
    # so that a future edit to one of them cannot silently narrow this gate's
    # coverage without touching it.
    try:
        sys.path.insert(0, os.path.join(repo_root, "compliance"))
        import scan_compliance  # noqa: E402
        if set(scan_compliance.TEXT_SUFFIXES) != set(TEXT_SUFFIXES):
            problems.append(
                "TEXT_SUFFIXES has drifted from scan_compliance.py: "
                "only-here=%s only-there=%s"
                % (sorted(set(TEXT_SUFFIXES) - set(scan_compliance.TEXT_SUFFIXES)),
                   sorted(set(scan_compliance.TEXT_SUFFIXES) - set(TEXT_SUFFIXES))))
    except ImportError as exc:
        problems.append("could not import scan_compliance to cross-check the "
                        "suffix set: %s" % exc)

    # The matrix must still declare the two groups this gate derives from.
    for group in CLIENT_FORBIDDEN_GROUPS:
        if group not in (contract_doc.get("field_groups") or {}):
            problems.append("visibility matrix no longer declares group %r" % group)

    return [{"face": "gate-self-check", "detail": p} for p in problems], {
        "derived_fields": len(forbidden),
        "contract_paths": len(ops),
        "allowlist_entries": len(allowlist),
    }


# ===========================================================================
# Main
# ===========================================================================

def harden_console_encoding():
    """Pin stdout/stderr to UTF-8: this platform's default is GBK and the report
    quotes Chinese source lines, which would otherwise be mangled or raise."""
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass


def main(argv=None):
    harden_console_encoding()
    parser = argparse.ArgumentParser(
        description="S1-6 client zero-derived regression gate")
    parser.add_argument("--repo-root", default=os.getcwd(),
                        help="build root: the parent of compliance/ and "
                             "client-package/")
    parser.add_argument("--docs-root", default=None,
                        help="docs root: the directory containing contract/. "
                             "Auto-detected above --repo-root when omitted.")
    parser.add_argument("--json-report", default=None,
                        help="write a machine-readable report here")
    parser.add_argument("--quiet", action="store_true",
                        help="print only the verdict line")
    args = parser.parse_args(argv)

    repo_root = os.path.abspath(args.repo_root)

    def emit(msg):
        if not args.quiet:
            print(msg)

    emit("=" * 79)
    emit("S1-6 CLIENT ZERO-DERIVED REGRESSION GATE")
    emit("repo-root : %s" % repo_root)
    emit("=" * 79)
    emit("")

    try:
        docs_root = resolve_docs_root(repo_root, args.docs_root)
        emit("docs-root : %s" % docs_root)
        emit("")
        forbidden, contract_doc = load_matrix(docs_root)
        ops = load_contract_ops(docs_root)
        allowlist = load_client_allowlist(repo_root)
    except GateConfigError as exc:
        print("[GATE-ERROR] %s" % exc, file=sys.stderr)
        print("[S1-6-GATE] FAIL (misconfigured, no PASS printed)", file=sys.stderr)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001 -- internal error must be loud
        print("[GATE-ERROR] internal error: %r" % exc, file=sys.stderr)
        return EXIT_INTERNAL

    violations = []
    audit = {}
    undeclared = []

    for name, fn in (
        ("FACE 1 no-zero-derived-names",
         lambda: face_1_no_zero_derived_names(repo_root, forbidden)),
        ("FACE 3 no-client-forbidden-paths",
         lambda: face_3_no_client_forbidden_paths(repo_root, ops)),
    ):
        try:
            hits, stats = fn()
        except GateConfigError as exc:
            print("[GATE-ERROR] %s: %s" % (name, exc), file=sys.stderr)
            print("[S1-6-GATE] FAIL (misconfigured, no PASS printed)",
                  file=sys.stderr)
            return EXIT_MISCONFIGURED
        except Exception as exc:  # noqa: BLE001
            # An internal crash must NOT be reported as a violation. A traceback
            # exits the process with status 1 -- the SAME status a real violation
            # uses -- so an uncaught crash here would be indistinguishable from a
            # finding, and a witness that only checks "nonzero means caught" would
            # count its own brokenness as detection. Distinct exit code, distinct
            # banner.
            print("[GATE-ERROR] %s: internal error: %r" % (name, exc),
                  file=sys.stderr)
            print("[S1-6-GATE] FAIL (internal error, no PASS printed)",
                  file=sys.stderr)
            return EXIT_INTERNAL
        violations.extend(hits)
        audit[name] = stats
        audit[name]["violations"] = len(hits)

    try:
        hits, stats, undeclared = face_2_allowlist_within_contract(
            repo_root, ops, allowlist)
    except GateConfigError as exc:
        print("[GATE-ERROR] FACE 2 allowlist-within-contract: %s" % exc,
              file=sys.stderr)
        print("[S1-6-GATE] FAIL (misconfigured, no PASS printed)", file=sys.stderr)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        print("[GATE-ERROR] FACE 2 allowlist-within-contract: internal error: %r"
              % (exc,), file=sys.stderr)
        print("[S1-6-GATE] FAIL (internal error, no PASS printed)", file=sys.stderr)
        return EXIT_INTERNAL
    violations.extend(hits)
    audit["FACE 2 allowlist-within-contract"] = stats
    audit["FACE 2 allowlist-within-contract"]["violations"] = len(hits)

    self_problems, self_stats = face_4_self_check(
        repo_root, forbidden, ops, allowlist, contract_doc)
    audit["FACE 4 gate-self-check"] = self_stats

    emit("FACE AUDIT (a face with 0 inputs is not a check)")
    emit("-" * 79)
    for key in sorted(audit):
        stats = ", ".join("%s=%s" % (k, v) for k, v in sorted(audit[key].items()))
        emit("  %-38s %s" % (key, stats))
    emit("")

    emit("")
    evidence = contract_path_evidence(ops)
    client_n = sum(1 for v in evidence.values() if v["client"])
    banned_n = sum(1 for v in evidence.values() if v["client_forbidden"])
    emit("contract coverage: %d operations over %d paths "
         "(%d paths client-callable, %d paths client-forbidden)"
         % (len(ops), len(evidence), client_n, banned_n))
    emit("")

    if self_problems:
        print("[GATE-ERROR] the gate's own inputs failed validation:", file=sys.stderr)
        for p in self_problems:
            print("  - %s" % p["detail"], file=sys.stderr)
        print("[S1-6-GATE] FAIL (misconfigured, no PASS printed)", file=sys.stderr)
        return EXIT_MISCONFIGURED

    if violations:
        emit("VIOLATIONS")
        emit("-" * 79)
        for v in violations:
            if v["face"] == "allowlist-within-contract":
                emit("  [%s] %s" % (v["kind"], v["path"]))
                emit("        %s" % v["detail"])
            else:
                emit("  [%s] %s:%d" % (v["term"], v["file"], v["line"]))
                emit("        %s" % v["text"])
        emit("")
        emit("[S1-6-GATE] FAIL  faces=3  violations=%d" % len(violations))
        if args.json_report:
            _write_report(args.json_report, repo_root, violations, audit)
        return EXIT_VIOLATION

    emit("no violations found on any face")
    if undeclared:
        emit("")
        emit("NOTE (information, not a violation): the contract permits the client")
        emit("to call %d path(s) the allow-list does not declare. Not a defect --" % len(undeclared))
        emit("a bundle may use fewer paths than it may call -- but each one is a")
        emit("path an intended caller will find missing, so it is listed here:")
        for path in undeclared:
            emit("  %s" % path)
    emit("")
    emit("[S1-6-GATE] PASS  faces=3  violations=0  derived_fields=%d  "
         "contract_client_paths=%d  allowlist_entries=%d  permitted_undeclared=%d"
         % (len(forbidden), audit["FACE 2 allowlist-within-contract"]
            ["contract_client_paths"], len(allowlist), len(undeclared)))
    if args.json_report:
        _write_report(args.json_report, repo_root, violations, audit,
                      undeclared=undeclared)
    return EXIT_PASS


def _write_report(path, repo_root, violations, audit, undeclared=None):
    payload = {
        "gate": "s1-6-client-zero-derived",
        "repo_root": repo_root,
        "verdict": "FAIL" if violations else "PASS",
        "violation_count": len(violations),
        "violations": violations,
        "audit": audit,
        "permitted_but_undeclared": undeclared or [],
    }
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory, exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


if __name__ == "__main__":
    sys.exit(main())