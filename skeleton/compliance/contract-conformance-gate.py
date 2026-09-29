#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
S1-8 CONTRACT CONFORMANCE GATE  --  do the shipped artifacts still describe the
frozen contract?

WHAT THIS IS FOR
================
The repository already has three build-time gates. None of them answers this
gate's question:

  * ADR-12 (`scan_compliance.py`) asks whether a FORBIDDEN WORD reached the
    client bundle.
  * S1-6  (`client-zero-derived-gate.py`) asks whether the bundle's permission
    CLAIM (its allow-list) reaches a path the contract denies.
  * `ContractFreezeGateTest` (Java) asks whether the contract agrees with its own
    upstream sources.

This gate asks whether the ARTIFACTS STILL TELL THE TRUTH ABOUT THE CONTRACT --
in the places where the artifact restates a contract fact in its own words:

    the allow-list's row citations      ('/api/v1/...  // D1 ...')
    the error-copy table's code keys    (1002, 2001, ...)
    the client sync-state namespace     (syncing, synced, ...)
    field names the contract hides from the client
    the visibility matrix's field lists (a DERIVED export, consumed at build time)

Each of those is a RESTATEMENT. A restatement is a claim, it can rot, and until
this gate existed nothing checked it.

THE DEFECT THAT MOTIVATED IT (found while wiring this gate up, not hypothesised)
==============================================================================
Entry 6 of the allow-list read:

    '/api/v1/customers/{id}/visits',   // D1 服务核销

D1 is the POST operation on that path, and its x-callable-roles is
[therapist, meridian, admin] -- the client is NOT among them. The client may call
the path only through D2 (the GET). So one of the two entries that S1-6 had
verified as correct carried a citation that names an operation forbidding the
very role the entry exists for.

S1-6 did not catch it, and could not: S1-6 aggregates roles ACROSS METHODS on
purpose, because the allow-list is keyed by path. That aggregation is right for
the question S1-6 asks ("may the bundle reach this path?") and blind to the
question this gate asks ("is the citation on this line true?"). The two gates
read the same file and check different facts about it.

WHY A REAL YAML PARSER, WHEN THE OTHER GATES USE REGEXES
========================================================
The ADR-12 scan and the S1-6 gate are deliberately stdlib-only so they run on a
bare CI runner. This gate does NOT follow them, and the reason is a measurement,
not a preference.

S1-6 needs exactly three scalar attributes per operation, so a line-oriented
extractor is safe there. This gate needs OPERATION-LEVEL CORRESPONDENCE: for a
cited row, which path and method carries it, and which roles does that method
allow. Establishing correspondence means walking `paths -> path -> method`, and
the file has 40 paths, 45 operations and 5 paths carrying more than one method.

While measuring this gate's inputs, two hand-rolled regex probes over the same
file, run minutes apart, returned DIFFERENT field census counts (42 then 2) --
same file, same intent, different answers, because the probe's window was
sensitive to YAML block layout. A probe that disagrees with itself cannot be the
basis of a gate that must be trusted. The Java `ContractFreezeGateTest` already
parses this file with snakeyaml; `sdk-generator/generate.sh` already parses it
with PyYAML. A third, hand-written YAML dialect would be the least trustworthy
of the three.

So: PyYAML. And its ABSENCE IS A LOUD FAILURE (exit 2), never a silent downgrade
to regex -- because a degraded parse that still prints PASS is precisely the
failure mode every gate in this directory exists to make impossible.

FACES
=====
  FACE 1 path-citations-are-true        every `// ROW` citation names a row that
                                        sits on that path AND allows the client
  FACE 2 error-copy-codes-are-real      every code key exists in x-error-codes
  FACE 3 client-enum-namespace-isolated package enum values ⊆ the contract's
                                        client enum, and disjoint from the
                                        internal enum's exclusive values
  FACE 4 no-client-invisible-field-names contract fields marked not-visible-to
                                        client do not appear in the bundle
  FACE 5 matrix-field-lists-agree       the visibility matrix's ③④ field lists
                                        are a subset of the contract's

EXIT CODES (deliberately identical to S1-6, so CI reads one legend)
==================================================================
  0 PASS   1 VIOLATION   2 MISCONFIGURED   3 INTERNAL ERROR
3 exists as a separate code because a Python traceback exits with status 1 --
the SAME status a violation uses. Without a distinct code, this gate crashing
would be indistinguishable from this gate finding something, and a witness that
only asserts "nonzero means caught" would record its own brokenness as detection.
That trap was hit for real while building S1-6.
"""

import argparse
import io
import json
import os
import re
import sys

EXIT_PASS = 0
EXIT_VIOLATION = 1
EXIT_MISCONFIGURED = 2
EXIT_INTERNAL = 3

# ---------------------------------------------------------------------------
# Inputs, and the roots they hang off.
#
# Two roots, for the same reason S1-6 needs two: the contract lives one level
# ABOVE the build root (beside the PRD), while the artifacts live inside it.
# ---------------------------------------------------------------------------
OPENAPI_REL = "contract/openapi-v1.0.0.yaml"
MATRIX_REL = "contract/visibility/band-visibility-matrix.json"
CLIENT_ROOT_REL = "client-package"
ALLOWLIST_REL = "client-package/api/clientPaths.js"
ERROR_COPY_REL = "client-package/errors/clientErrorCopy.js"
SYNC_ENUM_REL = "client-package/enums/clientSyncState.js"

DOCS_ROOT_SEARCH_DEPTH = 5

# The two field groups the contract rules client-invisible (③④). Named, not
# derived from "whichever group has client:false", so that ADDING a group to the
# contract cannot silently widen or narrow this gate: an unknown group is a hard
# failure, not an assumption.
CLIENT_FORBIDDEN_GROUPS = ("gap_reason", "derived_result")

# Mirrors scan_compliance.py / client-zero-derived-gate.py. Duplicated on purpose
# (importing would couple this gate's coverage to an unrelated file's edits) and
# cross-asserted by FACE self-check.
TEXT_SUFFIXES = frozenset({
    ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".vue", ".json", ".json5",
    ".wxml", ".wxss", ".wxss.js", ".wxs", ".html", ".htm", ".xml", ".yml",
    ".yaml", ".properties", ".java", ".kt", ".csv", ".txt", ".md", ".ini",
    ".cfg", ".conf", ".po", ".pot", ".strings", ".ftl", ".jsp", ".sql",
})

EXCLUDED_DIRS = frozenset({
    "node_modules", "target", "dist", "build", ".git", ".svn", "__pycache__",
})

HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options")


class GateConfigError(Exception):
    """This gate's own inputs are unusable. Maps to EXIT_MISCONFIGURED."""


# ===========================================================================
# Loading
# ===========================================================================

def _read_text(path):
    """
    Read a file as text: UTF-8 (BOM tolerated), then CP936, then latin-1.

    The contract and the client copy are Chinese; this platform's default
    encoding is GBK, so relying on the platform default would mangle the text or
    raise. Falling through to latin-1 keeps a hit REPORTABLE instead of crashing.
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
    Locate the root holding the frozen contract; REFUSE rather than guess.

    Falling back to the build root when the contract cannot be found would mean
    the gate verifies a tree that has no contract in it -- and a gate that
    checks the wrong place is the failure mode this refuses to enable.
    """
    def holds(root):
        return (os.path.isfile(os.path.join(root, OPENAPI_REL)) and
                os.path.isfile(os.path.join(root, MATRIX_REL)))

    if explicit:
        candidate = os.path.abspath(explicit)
        if not holds(candidate):
            raise GateConfigError(
                "--docs-root %s does not hold both %s and %s. Point it at the "
                "directory containing contract/." % (candidate, OPENAPI_REL, MATRIX_REL))
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
        "no docs root within %d levels of %s holds both %s and %s. Pass "
        "--docs-root explicitly. Refusing to run: verifying a tree with no "
        "contract would report PASS while checking nothing."
        % (DOCS_ROOT_SEARCH_DEPTH, build_root, OPENAPI_REL, MATRIX_REL))


def load_yaml(path):
    """
    Parse a YAML file, or fail LOUDLY.

    PyYAML's absence is a configuration error, not a reason to degrade to a
    regex extractor: see the module docstring for the measured reason.

    Both failure modes map onto GateConfigError, mirroring load_json: a
    malformed truth source is a misconfiguration of THIS gate's input, not an
    internal fault of the gate. The two exit with different codes on purpose --
    "the contract does not parse" and "the checker is broken" need different
    responses from whoever is on call.

    Note: yaml.YAMLError is NOT a subclass of ValueError, so catching the
    latter alone lets a ParserError escape as EXIT_INTERNAL. That misroutes
    exactly the case this branch exists to classify.
    """
    try:
        import yaml  # noqa: PLC0415 -- deliberate: absence must be catchable
    except ImportError as exc:
        raise GateConfigError(
            "PyYAML is not available (%s). This gate parses the 45-operation "
            "contract with a real parser on purpose -- a line-oriented "
            "extractor was measured to disagree with itself on the same file. "
            "Install PyYAML rather than weakening the parse: "
            "`python -m pip install pyyaml`." % exc)
    try:
        doc = yaml.safe_load(_read_text(path))
    except yaml.YAMLError as exc:
        raise GateConfigError("%s is not valid YAML: %s" % (path, exc))
    if not isinstance(doc, dict):
        raise GateConfigError("%s did not parse to a mapping" % path)
    return doc


def load_json(path):
    """
    Load a JSON input, mapping ANY failure onto a configuration error.

    A malformed truth source is a misconfiguration, not an internal fault, and
    the two exit with different codes on purpose: "the gate is broken" and "an
    input is broken" need different responses from whoever is on call.
    """
    try:
        return json.loads(_read_text(path))
    except ValueError as exc:
        raise GateConfigError("%s is not valid JSON: %s" % (path, exc))


def load_contract(docs_root):
    """Load and structurally validate the contract."""
    doc = load_yaml(os.path.join(docs_root, OPENAPI_REL))
    if not doc.get("openapi"):
        raise GateConfigError(
            "%s carries no `openapi:` version key -- it is not an OpenAPI "
            "document, so every face below would be checking nothing."
            % OPENAPI_REL)
    paths = doc.get("paths")
    if not isinstance(paths, dict) or not paths:
        raise GateConfigError("%s declares no paths" % OPENAPI_REL)
    codes = doc.get("x-error-codes")
    if not isinstance(codes, list) or not codes:
        raise GateConfigError(
            "%s declares no x-error-codes; FACE 2 would check nothing"
            % OPENAPI_REL)
    schemas = (doc.get("components") or {}).get("schemas")
    if not isinstance(schemas, dict) or not schemas:
        raise GateConfigError("%s declares no component schemas" % OPENAPI_REL)
    return doc


def contract_operations(doc):
    """
    Flatten the contract to one record PER (path, method).

    Per operation, not per path, and that distinction is the whole point of
    this gate. One path can carry several operations with DIFFERENT roles:

        /customers/{id}/visits          POST D1 [therapist, meridian, admin]
                                        GET  D2 [client, therapist, ...]
        /customers/{id}/intake-profile  GET  B5 [client, ...]
                                        PATCH B6 [therapist, meridian, admin]

    Collapsing those to one role set per path is exactly how the drifted
    citation on the visits entry survived review.
    """
    ops = []
    for path, item in (doc.get("paths") or {}).items():
        if not isinstance(item, dict):
            continue
        for method, op in item.items():
            if method.lower() not in HTTP_METHODS or not isinstance(op, dict):
                continue
            ops.append({
                "path": path,
                "method": method.lower(),
                "row": op.get("x-contract-row"),
                "roles": [str(r).strip().lower()
                          for r in (op.get("x-callable-roles") or [])],
                "client_forbidden": bool(op.get("x-client-forbidden")),
            })
    if len(ops) < 10:
        raise GateConfigError(
            "the contract yielded only %d operations. It freezes 45; a parse "
            "that finds almost nothing means the file's shape changed and this "
            "gate's walk no longer works. Refusing to report PASS on a contract "
            "it could not read." % len(ops))
    return ops


def index_operations(ops):
    by_row, by_path = {}, {}
    for op in ops:
        if op["row"]:
            by_row.setdefault(op["row"], []).append(op)
        by_path.setdefault(op["path"], []).append(op)
    return by_row, by_path


def client_invisible_fields(doc):
    """
    {field_name: sorted(roles)} for every contract field NOT visible to client.

    The contract is the authority here, not the visibility matrix: x-visible-to
    is attached at field level in the OpenAPI document, which is the artifact the
    three ends are told to code against.

    Walked recursively through properties / items / allOf, because a field
    annotated inside a nested object is still a field the client must not see.
    """
    found = {}

    def walk(schema):
        if not isinstance(schema, dict):
            return
        for name, spec in (schema.get("properties") or {}).items():
            if not isinstance(spec, dict):
                continue
            visible = spec.get("x-visible-to")
            if visible is not None:
                roles = [str(r).strip().lower() for r in visible]
                if "client" not in roles:
                    found[name] = sorted(set(found.get(name, []) + roles))
            for key in ("items", "allOf", "oneOf", "anyOf",
                        "additionalProperties", "not"):
                if key in spec:
                    walk(spec[key])
        for key in ("items", "allOf", "oneOf", "anyOf", "additionalProperties"):
            if key in schema and isinstance(schema[key], dict):
                walk(schema[key])

    for schema in ((doc.get("components") or {}).get("schemas") or {}).values():
        walk(schema)
    return found


def contract_group_fields(doc):
    """
    {group: {field names}} from field-level x-field-group, per schema.

    A field name can appear under more than one group in principle (as_value is
    declared twice: CustomerDetailData and BandDerivedData, both derived_result),
    so the value is a set.
    """
    groups = {}

    def walk(schema):
        if not isinstance(schema, dict):
            return
        for name, spec in (schema.get("properties") or {}).items():
            if not isinstance(spec, dict):
                continue
            group = spec.get("x-field-group")
            if group:
                groups.setdefault(group, set()).add(name)
            for key in ("items", "allOf", "oneOf", "anyOf",
                        "additionalProperties"):
                if key in spec:
                    walk(spec[key])
        for key in ("items", "allOf", "oneOf", "anyOf", "additionalProperties"):
            if key in schema and isinstance(schema[key], dict):
                walk(schema[key])

    for schema in ((doc.get("components") or {}).get("schemas") or {}).values():
        walk(schema)
    return groups


def contract_property_names(doc):
    """Every property name declared anywhere in the contract, recursively."""
    names = set()

    def walk(schema):
        if not isinstance(schema, dict):
            return
        for name, spec in (schema.get("properties") or {}).items():
            names.add(name)
            if isinstance(spec, dict):
                for key in ("items", "allOf", "oneOf", "anyOf",
                            "additionalProperties", "not"):
                    if key in spec:
                        walk(spec[key])
        for key in ("items", "allOf", "oneOf", "anyOf", "additionalProperties"):
            if key in schema and isinstance(schema[key], dict):
                walk(schema[key])

    for schema in ((doc.get("components") or {}).get("schemas") or {}).values():
        walk(schema)
    return names


def contract_enum_values(doc):
    """
    {enum_value} across every enum in the contract.

    Needed to tell a MISSPELLED FIELD NAME apart from a CONCEPTUAL NAME. The
    visibility matrix's ① group names raw-data metrics; the contract expresses
    those as the VALUES of the `metric` enum rather than as properties, so a
    check that only knew about property names would call the whole group phantom
    -- a false accusation against a correct artifact, which is how a gate gets
    switched off.
    """
    values = set()

    def walk(schema):
        if not isinstance(schema, dict):
            return
        for value in schema.get("enum") or []:
            values.add(str(value).lower())
        for spec in (schema.get("properties") or {}).values():
            if isinstance(spec, dict):
                walk(spec)
        for key in ("items", "allOf", "oneOf", "anyOf", "additionalProperties"):
            if key in schema and isinstance(schema[key], dict):
                walk(schema[key])
            elif key in schema and isinstance(schema[key], list):
                for item in schema[key]:
                    walk(item)

    for schema in ((doc.get("components") or {}).get("schemas") or {}).values():
        walk(schema)
    return values


def contract_error_codes(doc):
    """{code: (http, name)} from the authoritative x-error-codes list."""
    out = {}
    for entry in doc.get("x-error-codes") or []:
        if isinstance(entry, dict) and entry.get("code") is not None:
            out[int(entry["code"])] = (entry.get("http"), entry.get("name"))
    return out


# ===========================================================================
# Artifact parsing
# ===========================================================================

def parse_allowlist_citations(repo_root):
    """
    [(path, cited_row, comment_text)] from the client allow-list.

    An entry with NO citation is reported as cited_row=None rather than skipped:
    the citation is the claim FACE 1 exists to check, so a missing one is a
    finding, not an absence of evidence.
    """
    path = os.path.join(repo_root, ALLOWLIST_REL)
    text = _read_text(path)
    block = re.search(r'CLIENT_ALLOWED_PATHS\s*=\s*Object\.freeze\(\s*\[(.*?)\]',
                      text, re.DOTALL)
    if not block:
        raise GateConfigError(
            "%s no longer contains a CLIENT_ALLOWED_PATHS array this gate can "
            "read. If the declaration was renamed, update ALLOWLIST_REL and this "
            "extraction together -- until then every citation is UNVERIFIED."
            % ALLOWLIST_REL)
    entries = []
    for line in block.group(1).splitlines():
        pm = re.search(r"'(/api/v1[^']*)'", line)
        if not pm:
            continue
        cm = re.search(r'//\s*([A-Z]\d+)\b', line)
        entries.append((pm.group(1), cm.group(1) if cm else None, line.strip()))
    if not entries:
        raise GateConfigError(
            "%s: no allow-list entries parsed. An allow-list with no entries has "
            "no citations to verify; refusing to report PASS." % ALLOWLIST_REL)
    return entries


def parse_error_copy_codes(repo_root):
    """Sorted code keys of the client error-copy table."""
    text = _read_text(os.path.join(repo_root, ERROR_COPY_REL))
    block = re.search(r'CLIENT_ERROR_COPY\s*=\s*Object\.freeze\(\s*\{(.*?)\n\}\)',
                      text, re.DOTALL)
    if not block:
        raise GateConfigError(
            "%s no longer contains a CLIENT_ERROR_COPY object this gate can "
            "read; FACE 2 would check nothing." % ERROR_COPY_REL)
    codes = sorted({int(m) for m in re.findall(r'^\s*(\d{3,5})\s*:\s*\{',
                                               block.group(1), re.MULTILINE)})
    if not codes:
        raise GateConfigError(
            "%s: CLIENT_ERROR_COPY parsed to zero codes; refusing to report "
            "PASS on an empty table." % ERROR_COPY_REL)
    return codes


def parse_client_sync_enum(repo_root):
    """The client-visible sync-state values declared by the package."""
    text = _read_text(os.path.join(repo_root, SYNC_ENUM_REL))
    block = re.search(r'CLIENT_SYNC_STATE\s*=\s*Object\.freeze\(\s*\{(.*?)\n\}\)',
                      text, re.DOTALL)
    if not block:
        raise GateConfigError(
            "%s no longer contains a CLIENT_SYNC_STATE object this gate can "
            "read; FACE 3 would check nothing." % SYNC_ENUM_REL)
    values = re.findall(r":\s*'([a-z0-9_]+)'", block.group(1))
    if not values:
        raise GateConfigError(
            "%s: CLIENT_SYNC_STATE parsed to zero values." % SYNC_ENUM_REL)
    return sorted(set(values))


def iter_source_files(root):
    """Every scannable text file under root, deterministically ordered."""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames
                             if d not in EXCLUDED_DIRS and not d.startswith(".cache"))
        for filename in sorted(filenames):
            _, ext = os.path.splitext(filename)
            if ext.lower() in TEXT_SUFFIXES:
                found.append(os.path.join(dirpath, filename))
    return found


# ===========================================================================
# Matching (mirrors the S1-6 gate's semantics on purpose)
# ===========================================================================

def identifier_segments(token):
    """
    `mp_band_a3_value` -> [mp, band, a3, value]
    `mpBandA3Value`     -> [mp, band, a3, value]

    camelCase is split too, and that is a CORRECTION rather than a convenience.
    The contract names fields in snake_case; the client bundle is JavaScript, so
    the natural way for it to carry one of those names is camelCase. Measured
    before the fix:

        refund_visibility   -> matched      (snake_case spelling)
        refund-visibility   -> matched      (hyphen spelling)
        refundVisibility    -> NOT matched  <-- the idiomatic JS spelling

    i.e. the scanner was blind in exactly the dialect most likely to be used, and
    a leak written the obvious way would have shipped under a green gate. The S1-6
    gate had the same hole and was fixed in the same pass (with its own W1b
    witness pinning the camelCase form); ADR-12's scanner already split camelCase
    humps and is left as it is. An identifier run is kept intact through the split
    (`a3Value` -> [a3, value], not [a, 3, value]) so digit/letter runs still align
    with their snake_case form.
    """
    token = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", "_", token)
    token = re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", "_", token)
    return [p.lower() for p in token.replace("-", "_").split("_") if p]


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
    Whether a line spells a field name, using the SAME three levels as the
    ADR-12 scanner and the S1-6 gate -- whole token, single-word segment, or
    contiguous multi-segment run. A fragment never matches: `refundable` stays
    quiet because no level compares substrings inside a segment.

    ON SPLITTING. identifier_segments here splits BOTH camelCase humps
    (`refundVisibility`) and acronym humps (`ASValue` -> as, value). The S1-6
    gate was brought up to the camelCase half on 2026-09-24 -- it had been blind
    to it, so the JS spelling of a snake_case field shipped under a green gate.
    ADR-12's scanner splits the camelCase half only, and deliberately: its
    wordlist precision rule (`refund` must not fire on `refundable`) leans on a
    narrower split, and widening a WORD matcher is a different risk from widening
    a DECLARATION matcher. Declaration matching can afford to be the wider of the
    two, because a false positive here is a name the contract really does declare.

    Mirrored rather than imported: importing would tie this gate's detection
    surface to another file's future edits, and the three files are small enough
    that a reviewer can confirm they agree. The duplication is cross-asserted by
    the self-check face.
    """
    needle = identifier_segments(field_name)
    joined = "_".join(needle)
    for token in re.findall(r"[A-Za-z0-9_\-\u4e00-\u9fff]+", line):
        segments = identifier_segments(token)
        if "_".join(segments) == joined:
            return True
        if len(needle) == 1:
            if needle[0] in segments:
                return True
        elif contains_adjacent_run(segments, needle):
            return True
    return False


# ===========================================================================
# Faces
# ===========================================================================

def face_1_citations_are_true(repo_root, ops):
    """
    Every allow-list citation must name a row that is ON that path and ALLOWS
    the client.

    Three ways a citation can be false, each reported under its own kind so the
    reader knows which one they have:

      row-not-in-contract   the cited row exists nowhere -- a typo or a leftover
      row-not-on-this-path  the row exists, but sits on a different path -- the
                            entry cites a fact about another endpoint
      row-excludes-client   the row exists on this path, but its operation does
                            NOT permit the client. This is the defect that
                            motivated the gate: the visits entry cited D1, and D1
                            is the POST, which excludes the client. The entry is
                            still CORRECT (the path is client-callable through
                            D2); the CITATION is false.
    """
    by_row, by_path = index_operations(ops)
    if not by_row:
        raise GateConfigError(
            "no operation in the contract carries x-contract-row; citations "
            "cannot be resolved. Refusing to pass.")
    violations = []
    for raw_path, cited_row, line in parse_allowlist_citations(repo_root):
        if cited_row is None:
            violations.append({
                "face": "path-citations-are-true", "kind": "citation-missing",
                "path": raw_path, "row": None,
                "detail": "the entry carries no `// ROW` citation, so nothing "
                          "ties it to a contract operation: %s" % line})
            continue
        row_ops = by_row.get(cited_row)
        if not row_ops:
            violations.append({
                "face": "path-citations-are-true", "kind": "row-not-in-contract",
                "path": raw_path, "row": cited_row,
                "detail": "no operation in %s carries x-contract-row %s"
                          % (OPENAPI_REL, cited_row)})
            continue
        on_path = [o for o in row_ops if o["path"] == raw_path
                   or o["path"] == _strip_base(raw_path)]
        if not on_path:
            actual = sorted({"%s %s" % (o["method"].upper(), o["path"])
                             for o in row_ops})
            violations.append({
                "face": "path-citations-are-true", "kind": "row-not-on-this-path",
                "path": raw_path, "row": cited_row,
                "detail": "%s is on %s, not on this path"
                          % (cited_row, ", ".join(actual))})
            continue
        if not any("client" in o["roles"] for o in on_path):
            per_method = ", ".join(
                "%s %s=%s" % (o["method"].upper(), o["row"], o["roles"])
                for o in sorted(on_path, key=lambda x: x["method"]))
            sibling = sorted({
                "%s %s" % (o["method"].upper(), o["row"])
                for o in by_path.get(_strip_base(raw_path), [])
                if "client" in o["roles"]})
            violations.append({
                "face": "path-citations-are-true", "kind": "row-excludes-client",
                "path": raw_path, "row": cited_row,
                "detail": "the cited operation does not permit the client: %s. "
                          "Client-callable operation(s) on this path: %s"
                          % (per_method, ", ".join(sibling) or "none")})
    return violations, {"entries_checked": len(parse_allowlist_citations(repo_root)),
                        "rows_indexed": len(by_row)}


def face_2_error_codes_are_real(repo_root, contract_codes):
    """
    Every code key in the client copy table must exist in the contract.
    """
    if not contract_codes:
        raise GateConfigError(
            "the contract declares zero error codes; FACE 2 would check nothing")
    violations = []
    for code in parse_error_copy_codes(repo_root):
        if code not in contract_codes:
            violations.append({
                "face": "error-copy-codes-are-real", "kind": "code-not-in-contract",
                "code": code,
                "detail": "%d is not among the contract's %d error codes. Copy "
                          "for a code the server cannot send is dead weight, and "
                          "it means the code that WAS meant is missing."
                          % (code, len(contract_codes))})
    return violations, {"codes_in_copy": len(parse_error_copy_codes(repo_root)),
                        "codes_in_contract": len(contract_codes)}


def face_3_enum_namespace_isolated(repo_root, matrix):
    """
    The package's client-visible enum must stay inside the contract's client
    namespace, and must NOT contain a value that exists only in the internal one.

    The second half is the one that matters. A cross-namespace value does not
    leak a word -- it leaks a MEANING: the internal enum's exclusive values name
    why a day has no data, which is precisely the attribution the client surface
    is forbidden to make. The two enums are required to be independent namespaces
    (contract conflict point C-3), and they happen to share two values, which is
    what makes an accidental merge hard to see.
    """
    declarations = matrix.get("client_sync_state") or {}
    internal = matrix.get("internal_gap_reason") or {}
    allowed = declarations.get("enum")
    internal_values = internal.get("enum")
    if not allowed or not internal_values:
        raise GateConfigError(
            "the visibility matrix no longer declares both client_sync_state and "
            "internal_gap_reason enums; FACE 3 would check nothing.")
    allowed_set = {str(v) for v in allowed}
    internal_set = {str(v) for v in internal_values}
    internal_only = internal_set - allowed_set

    violations = []
    for value in parse_client_sync_enum(repo_root):
        if value in internal_only:
            violations.append({
                "face": "client-enum-namespace-isolated",
                "kind": "internal-value-in-client-namespace",
                "value": value,
                "detail": "%r exists only in the internal enum. Shipping it in "
                          "the client namespace distributes the attribution the "
                          "client surface must not make (C-3)." % value})
        elif value not in allowed_set:
            violations.append({
                "face": "client-enum-namespace-isolated",
                "kind": "value-not-in-contract-enum",
                "value": value,
                "detail": "%r is not in the contract's client sync-state enum %s"
                          % (value, sorted(allowed_set))})
    return violations, {"client_values": len(allowed_set),
                        "internal_values": len(internal_set),
                        "internal_only": len(internal_only)}


def face_4_no_client_invisible_field_names(repo_root, invisible):
    """
    Contract fields the client may not see must not be named in the bundle.

    SUPERSET OF S1-6'S FACE 1, NOT A DUPLICATE. S1-6 scans the six names the
    visibility matrix lists for ③④. This scans every field the contract marks
    x-visible-to without `client` -- 23 of them at the time of writing, of which
    the 17 outside ③④ had never been scanned by anything: `refund_visibility`,
    `staff_id`, `store_scope`, `threshold_version`, `evidence_snapshot` and
    others. A field name is a strong enough handle that a bundle carrying it is
    carrying a fact about the internal shape, whether or not it can call the
    endpoint that returns it.
    """
    root = os.path.join(repo_root, CLIENT_ROOT_REL)
    if not os.path.isdir(root):
        raise GateConfigError(
            "client package root not found: %s -- refusing to pass. A gate that "
            "scans nothing reports success." % root)
    if not invisible:
        raise GateConfigError(
            "the contract yields ZERO client-invisible fields. The ③④ groups "
            "alone guarantee more than zero, so this means the walk broke -- "
            "refusing to report PASS on a face that would check nothing.")
    hits = []
    scanned = 0
    for path in iter_source_files(root):
        scanned += 1
        rel = os.path.relpath(path, repo_root)
        for lineno, line in enumerate(_read_text(path).splitlines(), start=1):
            if not line.strip():
                continue
            for field_name in sorted(invisible):
                if line_spells_field(line, field_name):
                    hits.append({"face": "no-client-invisible-field-names",
                                 "kind": "client-invisible-field-name",
                                 "term": field_name,
                                 "roles": invisible[field_name],
                                 "file": rel, "line": lineno,
                                 "detail": "%r is x-visible-to %s -- the client "
                                           "is not among them, so the bundle must "
                                           "not name it. Found at %s:%d: %s"
                                           % (field_name,
                                              invisible[field_name],
                                              rel, lineno, line.strip()[:200]),
                                 "text": line.strip()[:240]})
    return hits, {"files_scanned": scanned, "fields_checked": len(invisible)}


def face_5_matrix_field_lists_agree(contract_groups, property_names,
                                    enum_values, matrix):
    """
    The visibility matrix's ③④ field lists must be field names the contract
    actually declares. Separate rule for ①②, see below.

    WHY THIS IS A FACE AND NOT A DOCUMENTATION TIDY-UP. The matrix declares
    itself a DERIVED export and names the OpenAPI document as a source. Its field
    lists are therefore a restatement -- and a restatement that had rotted:

        group  matrix said                          contract actually has
        ①      sleep_minutes, steps, ... (6 names)  `metrics` + a 13-value enum
        ②      captured_days, synced_at, link_state `collected_days`,
                                                    `synced_date`, `data_source`
        ④      adherence_dimension_score, a3, ...   `a3_applicable`, `a3_value`,
                                                    `as_value`, `effect_verdict`,
                                                    `refund_eligibility`

    Of the four groups only ③ agreed. Two of the drifted ④ names exist in NO
    contract schema at all.

    This is the third appearance of one failure mode on this project: a fact
    restated by hand in a second place, with nothing comparing the two. The
    allow-list was the first (S1-6), and the S1-6 wordlist's own additions were
    the second -- they were seeded from this drifted list, so the gate built to
    catch hand-copies was itself fed by one.

    WHY ③④ GET A FIELD-NAME RULE AND ①② DO NOT. Measured, not assumed:

      ③ gap_reason       1/1 name is a property     -> field-name list
      ④ derived_result   5/5 names are properties   -> field-name list
      ② capture_status   1/3 names are properties; the other two
                         (`captured_days`, `link_state`) are NEITHER properties
                         NOR enum values -- they are conceptual labels for
                         `collected_days` / `synced_date` / `data_source`
      ① raw_data         0/6 names are properties; ALL SIX are values of the
                         contract's `metric` enum (`sleep` vs `sleep_minutes`)

    So a field-name rule applied to ①② would accuse a legitimately conceptual
    grouping of being wrong, and a gate that fails on correct content gets
    switched off. Instead ①② are held to a weaker, TRUE rule: a name must at
    least be a contract property OR a contract enum value, i.e. it must be
    GROUNDED in the contract rather than invented. ②'s two conceptual labels are
    therefore reported as INFORMATION (a naming divergence for the contract
    owner to rule on), never as a build failure.

    The ③④ direction is BIDIRECTIONAL, and the reason is fail-open. An earlier
    version of this face reported `contract_has_but_matrix_lacks` as
    INFORMATION, reasoning that "the matrix may be coarser than the contract."
    That reasoning is wrong here, and measurably so: the visibility matrix is an
    INPUT to the S1-6 zero-derived gate, which builds its forbidden-field set
    from these very lists. If the matrix drops `as_value`, S1-6 does not report a
    narrower check -- it reports PASS while guarding one field less, and nothing
    down the line can notice. Extra names over-guard and are harmless; MISSING
    names silently disable protection.

    So the rule is symmetric for ③④: the matrix may neither invent a name the
    contract does not declare, nor omit one it does. That is legitimate precisely
    because the matrix declares itself a DERIVED export of the contract -- both
    sides are the same fact read two ways, so any difference is a defect on one
    side, never acceptable coarseness.
    """
    violations = []
    info = {}
    for group in CLIENT_FORBIDDEN_GROUPS:
        declared = ((matrix.get("field_groups") or {}).get(group) or {}).get("fields")
        if declared is None:
            raise GateConfigError(
                "the visibility matrix no longer declares a field list for %s. "
                "That list is what FACE 5 checks; its absence is a "
                "misconfiguration, not a pass." % group)
        actual = contract_groups.get(group)
        if not actual:
            violations.append({
                "face": "matrix-field-lists-agree", "kind": "group-absent-in-contract",
                "group": group,
                "detail": "the matrix declares group %r with %d field(s), but no "
                          "contract field carries x-field-group: %s"
                          % (group, len(declared), group)})
            continue
        for name in declared:
            if name not in actual:
                violations.append({
                    "face": "matrix-field-lists-agree", "kind": "phantom-field",
                    "group": group, "field": name,
                    "detail": "the matrix lists %r under %s, but no contract field "
                              "carries that name. Contract fields in this group: %s"
                              % (name, group, sorted(actual))})
        for name in sorted(set(actual) - set(declared)):
            violations.append({
                "face": "matrix-field-lists-agree",
                "kind": "matrix-omits-contract-field",
                "group": group, "field": name,
                "detail": "the contract marks %r as x-field-group: %s, but the "
                          "visibility matrix does not list it. S1-6 builds its "
                          "forbidden-field set from this list, so the omission "
                          "narrows that gate SILENTLY -- it keeps reporting PASS "
                          "while guarding one field less. Matrix fields: %s"
                          % (name, group, declared)})
        info[group] = {"contract_fields": sorted(actual), "matrix_fields": declared}

    # ①②: grounding rule, information-only for names that are conceptual.
    ungrounded = {}
    for group, entry in (matrix.get("field_groups") or {}).items():
        if group in CLIENT_FORBIDDEN_GROUPS:
            continue
        names = set(entry.get("fields") or [])
        if not names:
            continue
        bad = sorted(n for n in names
                     if n not in property_names and n.lower() not in enum_values)
        if bad:
            ungrounded[group] = bad
    if ungrounded:
        info["_conceptual_names_in_groups_1_2"] = ungrounded
    return violations, info


def _strip_base(path):
    """Drop the contract's servers.url base path so the two sides compare."""
    for base in ("/api/v1",):
        if path.startswith(base + "/"):
            return path[len(base):]
    return path


def self_check(repo_root, doc, ops, invisible, contract_groups, matrix):
    """
    Assert this gate's own inputs are real, so a PASS means something.

    Each check corresponds to a way a gate reports success while checking
    nothing -- the failure mode the ADR-12 README names "the gate that rotted".
    """
    problems = []
    if len(ops) < 10:
        problems.append("contract parsed to %d operations" % len(ops))
    if not invisible:
        problems.append("client-invisible field set is EMPTY: FACE 4 scans nothing")
    for group in CLIENT_FORBIDDEN_GROUPS:
        if not contract_groups.get(group):
            problems.append("no contract field carries x-field-group: %s" % group)
    if not matrix.get("client_sync_state", {}).get("enum"):
        problems.append("matrix declares no client_sync_state enum values")

    # The suffix set is duplicated across three gates. Assert this copy agrees
    # with the scanner, so an edit to one cannot silently narrow this gate.
    try:
        sys.path.insert(0, os.path.join(repo_root, "compliance"))
        import scan_compliance  # noqa: PLC0415
        if set(scan_compliance.TEXT_SUFFIXES) != set(TEXT_SUFFIXES):
            problems.append(
                "TEXT_SUFFIXES drifted from scan_compliance.py: only-here=%s "
                "only-there=%s"
                % (sorted(set(TEXT_SUFFIXES) - set(scan_compliance.TEXT_SUFFIXES)),
                   sorted(set(scan_compliance.TEXT_SUFFIXES) - set(TEXT_SUFFIXES))))
    except ImportError as exc:
        problems.append("could not import scan_compliance to cross-check the "
                        "suffix set: %s" % exc)

    # The three faces that read an artifact must find their artifact. Absence is
    # a misconfiguration, not an empty result.
    for rel in (ALLOWLIST_REL, ERROR_COPY_REL, SYNC_ENUM_REL):
        if not os.path.isfile(os.path.join(repo_root, rel)):
            problems.append("artifact this gate verifies is absent: %s" % rel)

    return [{"face": "gate-self-check", "detail": p} for p in problems]


# ===========================================================================
# Main
# ===========================================================================

def harden_console_encoding():
    """Pin stdout/stderr to UTF-8: this platform defaults to GBK and the report
    quotes Chinese source lines, which would otherwise be mangled or raise."""
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        if stream is not None and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except (ValueError, OSError):
                pass


def _run_face(name, fn, violations, audit):
    """
    Run one face, mapping its failure modes onto distinct exit codes.

    A crash MUST NOT be folded into "caught something": Python exits with status
    1 on an uncaught exception, the same status a violation uses, so an
    uncaught crash would be indistinguishable from a finding.
    """
    try:
        hits, stats = fn()
    except GateConfigError as exc:
        print("[GATE-ERROR] %s: %s" % (name, exc), file=sys.stderr)
        print("[S1-8-GATE] FAIL (misconfigured, no PASS printed)", file=sys.stderr)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001 -- internal error must be loud
        print("[GATE-ERROR] %s: internal error: %r" % (name, exc), file=sys.stderr)
        print("[S1-8-GATE] FAIL (internal error, no PASS printed)", file=sys.stderr)
        return EXIT_INTERNAL
    violations.extend(hits)
    audit[name] = stats
    audit[name]["violations"] = len(hits)
    return None


def main(argv=None):
    harden_console_encoding()
    parser = argparse.ArgumentParser(
        description="S1-8 contract conformance gate")
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
    emit("S1-8 CONTRACT CONFORMANCE GATE")
    emit("repo-root : %s" % repo_root)

    try:
        docs_root = resolve_docs_root(repo_root, args.docs_root)
        emit("docs-root : %s" % docs_root)
        emit("")
        doc = load_contract(docs_root)
        ops = contract_operations(doc)
        invisible = client_invisible_fields(doc)
        contract_groups = contract_group_fields(doc)
        property_names = contract_property_names(doc)
        enum_values = contract_enum_values(doc)
        contract_codes = contract_error_codes(doc)
        matrix = load_json(os.path.join(docs_root, MATRIX_REL))
    except GateConfigError as exc:
        print("[GATE-ERROR] %s" % exc, file=sys.stderr)
        print("[S1-8-GATE] FAIL (misconfigured, no PASS printed)", file=sys.stderr)
        return EXIT_MISCONFIGURED
    except Exception as exc:  # noqa: BLE001
        print("[GATE-ERROR] internal error: %r" % exc, file=sys.stderr)
        return EXIT_INTERNAL

    violations, audit = [], {}
    face5_info = {}
    for name, fn in (
        ("FACE 1 path-citations-are-true",
         lambda: face_1_citations_are_true(repo_root, ops)),
        ("FACE 2 error-copy-codes-are-real",
         lambda: face_2_error_codes_are_real(repo_root, contract_codes)),
        ("FACE 3 client-enum-namespace-isolated",
         lambda: face_3_enum_namespace_isolated(repo_root, matrix)),
        ("FACE 4 no-client-invisible-field-names",
         lambda: face_4_no_client_invisible_field_names(repo_root, invisible)),
        ("FACE 5 matrix-field-lists-agree",
         lambda: face_5_matrix_field_lists_agree(contract_groups, property_names,
                                                 enum_values, matrix)),
    ):
        code = _run_face(name, fn, violations, audit)
        if code is not None:
            return code
        if name.startswith("FACE 5"):
            face5_info = dict(audit[name])
            face5_info.pop("violations", None)

    self_problems = self_check(repo_root, doc, ops, invisible,
                               contract_groups, matrix)
    audit["FACE 6 gate-self-check"] = {"problems": len(self_problems)}

    emit("FACE AUDIT (a face with 0 inputs is not a check)")
    emit("-" * 79)
    for key in sorted(audit):
        stats = ", ".join("%s=%s" % (k, v) for k, v in sorted(audit[key].items()))
        emit("  %-42s %s" % (key, stats))
    emit("")
    emit("contract coverage: %d operations over %d paths "
         "(%d client-invisible fields, %d error codes, %d group(s) checked)"
         % (len(ops), len(doc.get("paths") or {}), len(invisible),
            len(contract_codes), len(CLIENT_FORBIDDEN_GROUPS)))
    emit("")

    if self_problems:
        print("[GATE-ERROR] the gate's own inputs failed validation:", file=sys.stderr)
        for p in self_problems:
            print("  - %s" % p["detail"], file=sys.stderr)
        print("[S1-8-GATE] FAIL (misconfigured, no PASS printed)", file=sys.stderr)
        return EXIT_MISCONFIGURED

    if violations:
        emit("VIOLATIONS")
        emit("-" * 79)
        for v in violations:
            where = v.get("file")
            if where and v.get("line"):
                where = "%s:%s" % (where, v["line"])
            subject = (v.get("path") or where or v.get("term") or v.get("field")
                       or v.get("value") or v.get("group") or "?")
            # v.get("kind"), never v["kind"]: a finding that forgets its kind
            # must still be REPORTED. Keying straight into the dict turned a
            # detection into a traceback, and the traceback exited 1 -- the same
            # status a violation uses -- so the crash read as a pass.
            emit("  [%s] %s" % (v.get("kind", "unclassified"), subject))
            emit("        %s" % v.get("detail", ""))
        emit("")
        emit("[S1-8-GATE] FAIL  faces=5  violations=%d" % len(violations))
        if args.json_report:
            _write_report(args.json_report, repo_root, violations, audit)
        return EXIT_VIOLATION

    emit("no violations found on any face")
    if face5_info:
        emit("")
        emit("NOTE (information, not a violation):")
        conceptual = face5_info.get("_conceptual_names_in_groups_1_2") or {}
        if conceptual:
            emit("  groups ① / ② use CONCEPTUAL names rather than field names.")
            emit("  FACE 5 requires only that such a name be grounded in the")
            emit("  contract (a property or an enum value); these are neither,")
            emit("  so they are surfaced for the contract owner to rule on --")
            emit("  naming divergence, not a build failure:")
            for group in sorted(conceptual):
                emit("    %s: %s" % (group, ", ".join(conceptual[group])))
            emit("")
            emit("  The contract expresses ② as `collected_days` / `synced_date` /")
            emit("  `data_source`; ① as the values of the `metric` enum.")
    emit("")
    emit("[S1-8-GATE] PASS  faces=5  violations=0  operations=%d  "
         "invisible_fields=%d  contract_codes=%d"
         % (len(ops), len(invisible), len(contract_codes)))
    if args.json_report:
        _write_report(args.json_report, repo_root, violations, audit)
    return EXIT_PASS


def _write_report(path, repo_root, violations, audit):
    payload = {
        "gate": "s1-8-contract-conformance",
        "repo_root": repo_root,
        "verdict": "FAIL" if violations else "PASS",
        "violation_count": len(violations),
        "violations": violations,
        "audit": audit,
    }
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory, exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


if __name__ == "__main__":
    sys.exit(main())