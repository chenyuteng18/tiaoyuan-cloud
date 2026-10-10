#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADR-12 build-time compliance scanner -- the compliance gate itself.

Authority
---------
  ADR-12            arch-decisions-lead-2026-09-19.md  section 12
  H-2 / H-11 / O-10 arch-constraints-inventory-2026-09-19.md
  contract          contract-t6-api-freeze-2026-09-19.md
                      section 5.1 rules R1~R8 (client-visible names / wording)
                      section 5 row X-2 (build-time scan of the bundle)
  PRD               section 2.4 X-2,  section 2.6 (P0-26 W-1/W-2)

Citations are by SEMANTIC ANCHOR (section / rule id / item title / config number
/ function name), never by line number: the upstream documents move, and a
citation that quietly points at the wrong line manufactures false confidence.

Design notes
------------
* This file is PURE ASCII on purpose. Every non-ASCII literal lives in an
  external wordlist file so that this script behaves identically no matter what
  the platform ANSI code page is.
* Wordlists are decoded as UTF-8 EXPLICITLY, never with the platform default
  encoding. On this machine the platform encoding is GBK; Python 3.15+ and
  PEP 686 flip text-mode defaults to UTF-8, which would otherwise make the gate
  silently change behaviour across interpreter upgrades. The encoding is
  therefore pinned here rather than inherited.
* Matching model. The two writing systems need two different rules, and getting
  this wrong breaks the gate in opposite directions:

    - ASCII terms are matched against IDENTIFIER SEGMENTS, not against the whole
      token and not as a substring. Client-side identifiers are snake_case and
      camelCase, so the refund wording arrives as `mp_refund_entry_tap` or
      `RefundView` -- neither of which equals `refund`. Matching whole tokens
      would therefore miss the single channel ADR-12 calls out loudest (the
      analytics event name and the subscription-message template). Matching as a
      plain substring would instead fire on `refundable`. Splitting the token on
      underscores and camel-case humps satisfies both: it catches
      `mp_refund_entry_tap`, `RefundView`, `AS_refund` and a `/api/v1/refund/x`
      path segment, while leaving `refundable` alone.

    - CJK terms are matched as SUBSTRINGS. A Chinese sentence has no spaces, so
      the entire sentence is one token; token-exact matching would never find
      the term inside it. Substring matching is the only workable rule here, and
      it shifts a duty onto the WORDLIST: no term may be short enough to be a
      substring of an innocuous customer-facing word. The wordlist is curated
      against that rule and the tests pin the cases that matter.

* Wordlist entries are matched case-insensitively; identifiers in the client
  package are lower-case, and case must not be a way to slip past the gate.
* A rule is only allowed to fire if it has a non-empty owner. A rule whose
  owner is blank is reported as owner-missing and FAILS the build: an ownerless
  rule is a dead rule, it decays into a silent no-op the moment nobody is
  looking at it.

Exit codes
----------
  0  PASS
  1  violation(s) found              (a term fired)
  2  gate configuration error        (owner missing / wordlist unreadable)
  3  internal scanner error
  4  scan-surface mutation detected  (declared face missing, renamed, or extra)
"""

from __future__ import annotations

import argparse
import datetime
import io
import json
import os
import re
import sys
import unicodedata

# --------------------------------------------------------------------------
# Declared scan surfaces. ADR-12 fixes exactly three of them. This tuple is
# duplicated in tests/compliance_injection_test.py, which asserts it against
# its own independent copy -- a change to one without the other FAILS.
# --------------------------------------------------------------------------
SCAN_FACES = ("scan1_refund", "scan2_negative", "scan3_derived")

# Things the gate refuses to scan even when they sit inside a scanned root.
# Keeps the text announcements from sweeping up the gate's own artefacts.
EXCLUDED_DIRS = frozenset({
    ".git", ".hg", ".svn", "node_modules", "target", "build", "dist",
    "out", ".idea", ".vscode", "__pycache__", "reports",
})

# --------------------------------------------------------------------------
# Channel parity for scan face 1. ADR-12 states the refund wording must be
# absent from every channel it can reach the customer through, and names the two
# that are routinely forgotten: the analytics event name and the subscription
# message template -- "the page was changed but the push was not".
#
# The list is a CONSTANT here, not a manifest setting, for the same reason the
# scan faces are: it is the ruling, and a ruling that lives in a config file is
# a ruling that gets edited away. The manifest may only be a superset of it.
# --------------------------------------------------------------------------
CANONICAL_CHANNELS = (
    "i18n",
    "enums",
    "constants",
    "errors",
    "analytics",
    "subscribe-messages",
    "api",
)

TEXT_SUFFIXES = frozenset({
    ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".vue", ".json", ".json5",
    ".wxml", ".wxss", ".wxss.js", ".wxs", ".html", ".htm", ".xml", ".yml",
    ".yaml", ".properties", ".java", ".kt", ".csv", ".txt", ".md", ".ini",
    ".cfg", ".conf", ".po", ".pot", ".strings", ".ftl", ".jsp", ".sql",
})

# Token characters used when splitting a line into match candidates. CJK and
# CJK punctuation are kept INSIDE the token on purpose: a Chinese sentence must
# remain one token so that terms can be found inside it as substrings.
#
# `-` is inside the token, and that is a FIX, not a detail -- see the note below.
_TOKEN_RE = re.compile(
    r"[0-9A-Za-z_\-\u4e00-\u9fff\u3000-\u303f\uff00-\uffef]+"
)

# ASCII identifiers are further split on '_' and on camel-case humps, so that
# `mp_refund_entry_tap` yields the segment `refund`.
_IDENT_SEP_RE = re.compile(r"[_\-.]")
_CAMEL_RE = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")

# ---------------------------------------------------------------------------
# WHY `-` IS A TOKEN CHARACTER (fix, 2026-09-24)
# ---------------------------------------------------------------------------
# The hyphen is a legal separator in JSON keys, URL slugs, CSS class names and
# YAML keys, so an identifier reaches a bundle as `gap-reason` at least as easily
# as `gap_reason`. It was NOT a token character, so the tokeniser split
# `gap-reason` into two tokens, `gap` and `reason`, and NO LEVEL of the match
# rules could reassemble them:
#
#     whole token   `gap` != `gap_reason` ; `reason` != `gap_reason`
#     segment       neither is a segment of the other
#     adjacent run  a run cannot span two separate tokens
#
# Measured before the fix -- every MULTI-word term was reachable by the hyphen
# spelling, and it stayed silent:
#
#     gap_reason      HIT    gap-reason      MISS
#     effect_verdict  HIT    effect-verdict  MISS
#     blood_sugar     HIT    blood-sugar     MISS
#     AS_refund       HIT    AS-refund       MISS
#     refund_clause   HIT    refund-clause   HIT   <- single-word term: the
#                                                   `refund` segment is found
#                                                   inside the `refund` token
#
# So the miss was not random: it hit exactly the multi-word terms, which is the
# shape the adjacent-run level was added for. Single-word terms were caught only
# by luck -- the hyphen happened to fall between the term and its suffix.
#
# The fix widens the token, not the match rule: `gap-reason` becomes ONE token
# whose segments are [gap, reason], which the existing adjacent-run level already
# matches. `_IDENT_SEP_RE` already split on `-`, so nothing about segment
# semantics changes -- this only lets the tokeniser see the whole identifier.
#
# Verified no false positives: 16 hyphenated identifiers a real bundle plausibly
# contains (`no-data-today`, `client-sync-state`, `read-only`, `end-to-end`,
# `self-check`, `high-level`, `band-not-connected`, ...) all stay quiet on all
# three faces, because no level matches a fragment and none of their segments
# forms a listed contiguous run. `T5` now asserts this both ways.
# ---------------------------------------------------------------------------

# Escape sequences. `"\u9000\u6b3e"` is the cheapest way to smuggle a forbidden
# word past a naive grep: the bundle source contains no non-ASCII at all, yet the
# string decodes to the forbidden word the moment it is evaluated. A compliance
# gate that reads only the raw bytes would report PASS on that bundle, so every
# line is also scanned in its escape-expanded form.
_U_ESCAPE_RE = re.compile(r"\\u([0-9a-fA-F]{4})")
_X_ESCAPE_RE = re.compile(r"\\x([0-9a-fA-F]{2})")

# F-2 时间锚点判据（2026-10-09）。_requirements_locked 第③条要求每个订阅消息
# 模板正文必须带时间锚点（"XX 小时内"）。此前这条只写在数据里、无任何脚本读取，
# 终稿换文案时会静默丢失——正是本仓第 89 条"手写覆盖宣言没人守"的同族形态。这里把它
# 变成机器判据：命中 {hours} / 小时内 / \d+\s*hours 等形态即视为已带锚点。
_TIME_ANCHOR_RE = re.compile(r"\{hours\}|小时内|\d+\s*hours", re.IGNORECASE)

ENV_ALLOW = "DY_COMPLIANCE_ALLOW_MISSING_ROOT"

# --------------------------------------------------------------------------
# Owner readiness. "The owner column is not empty" and "a named human owns
# this rule" are two different properties, and the gate only checked the first
# one -- which is why placeholder roles sailed through as owners_ok=yes while
# nobody was accountable for deciding that a term belongs in a wordlist.
#
# Both markers below are FIXED, not configurable. A marker that lives in a
# config file is a marker that gets edited away, and the whole point of this
# check is that the placeholder cannot be relabelled into a named owner by
# editing data alone: only a real name (plus a reachable contact) turns a
# subject NAMED.
# --------------------------------------------------------------------------
ROLE_PREFIX = "role:"
CONTACT_TBD_MARKER = "tbd"
CONTACT_INVALID_MARKER = "invalid"


class OwnerRecord:
    """One owners.csv row, carrying the readiness evidence it was judged on."""

    __slots__ = ("subject", "owner_id", "contact")

    def __init__(self, subject, owner_id, contact):
        self.subject = subject
        self.owner_id = owner_id
        self.contact = contact

    def __repr__(self):
        return "OwnerRecord(%r, %r, %r)" % (
            self.subject, self.owner_id, self.contact,
        )


class GateConfigError(Exception):
    """The gate itself is misconfigured -- must not be reported as a pass."""


class FaceMutationError(Exception):
    """The set of scan faces no longer matches the ADR-12 ruling."""


def norm(text: str) -> str:
    """NFKC + casefold. Unicode homoglyphs must not defeat the wordlist."""
    return unicodedata.normalize("NFKC", text).casefold()


def load_wordlist(path: str) -> list:
    """Return the wordlist terms, in file order, decoded strictly as UTF-8."""
    try:
        # utf-8-sig is still UTF-8; it only additionally strips a leading BOM,
        # which editors on this platform like to add and which would otherwise
        # corrupt the FIRST term in the file into an unmatchable string.
        with io.open(path, "r", encoding="utf-8-sig", newline="") as handle:
            raw = handle.read()
    except OSError as exc:
        raise GateConfigError("wordlist unreadable: %s (%s)" % (path, exc))
    except UnicodeDecodeError as exc:
        raise GateConfigError(
            "wordlist is not valid UTF-8: %s (%s)" % (path, exc)
        )
    terms = []
    for line in raw.splitlines():
        line = line.strip().lstrip("\ufeff").strip()
        if not line or line.startswith("#"):
            continue
        terms.append(line)
    if not terms:
        raise GateConfigError("wordlist declares no terms: %s" % path)
    return terms


def parse_owners_table(path: str) -> list:
    """
    Parse owners.csv into OwnerRecord rows. Header row is required.

    Kept separate from load_owners so that the readiness judgement can see the
    `contact` column. load_owners only ever needed subject and owner_id, and
    re-deriving the contact from the raw file at the point of the judgement
    would mean two parsers could drift apart about what the same row says.
    """
    try:
        with io.open(path, "r", encoding="utf-8-sig", newline="") as handle:
            raw = handle.read()
    except OSError as exc:
        raise GateConfigError("owners file unreadable: %s (%s)" % (path, exc))
    lines = [ln.replace("\ufeff", "") for ln in raw.splitlines() if ln.strip()]
    if not lines:
        raise GateConfigError("owners file is empty: %s" % path)
    header = [c.strip() for c in lines[0].split(",")]
    for required in ("subject", "owner_id"):
        if required not in header:
            raise GateConfigError(
                "owners file header must contain column '%s': %s"
                % (required, path)
            )
    idx_subject = header.index("subject")
    idx_owner = header.index("owner_id")
    idx_contact = header.index("contact") if "contact" in header else -1
    records = []
    for line in lines[1:]:
        cells = [c.strip() for c in line.split(",")]
        if len(cells) <= max(idx_subject, idx_owner):
            continue
        subject = cells[idx_subject]
        if not subject:
            continue
        owner = cells[idx_owner]
        contact = ""
        if idx_contact >= 0 and len(cells) > idx_contact:
            contact = cells[idx_contact]
        records.append(OwnerRecord(subject, owner, contact))
    return records


def load_owners(path: str) -> dict:
    """Parse owners.csv -> {subject: owner_id}. Header row is required."""
    owners = {}
    for record in parse_owners_table(path):
        # An empty owner is NOT stored: it must surface as owner-missing.
        if record.owner_id:
            owners[record.subject] = record.owner_id
    return owners


def owner_placeholder_reasons(record: OwnerRecord) -> list:
    """
    Return the reasons a row is still a placeholder, empty list if it is NAMED.

    The rules are deliberately crude and closed: a row is NAMED only when it
    has an owner that is not a role label AND a contact that is neither blank
    nor a known dead marker. Anything a real attribution would need -- a person,
    a mailbox that can receive mail -- is checked; nothing about seniority,
    team, or wording is.
    """
    reasons = []
    owner = record.owner_id.strip()
    contact = record.contact.strip()
    if not owner:
        # The pre-existing owner-missing discipline already fails the build for
        # this row, but a blank owner is still not a named owner: silently
        # counting it as NAMED here would put a hole in the readiness check
        # exactly where the older check is strongest.
        reasons.append("owner_id is empty")
    elif owner.lower().startswith(ROLE_PREFIX):
        reasons.append("owner_id is a role label (%r)" % record.owner_id)
    if not contact:
        reasons.append("contact is empty")
    else:
        folded = contact.casefold()
        if CONTACT_INVALID_MARKER in folded:
            reasons.append("contact is an unroutable address (%r)"
                           % record.contact)
        if CONTACT_TBD_MARKER in folded:
            reasons.append("contact is still to-be-determined (%r)"
                           % record.contact)
    return reasons


def assess_owner_readiness(records) -> dict:
    """
    Split the owner table into NAMED and PLACEHOLDER subjects.

    Returns a dict with:
      named        {subject: owner_id}       -- a real, accountable owner
      placeholder  [(subject, owner_id, [reason, ...]), ...] in file order
      records      the input rows, unchanged
    """
    named = {}
    placeholder = []
    for record in records:
        reasons = owner_placeholder_reasons(record)
        if reasons:
            placeholder.append((record.subject, record.owner_id, reasons))
        else:
            name = record.owner_id
            # An absent owner is a separate, pre-existing discipline and must
            # not be double-counted here as "named".
            if name:
                named[record.subject] = name
    return {"named": named, "placeholder": placeholder, "records": list(records)}


def iter_source_files(root: str):
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


def read_text_file(path: str) -> str:
    """
    Read a candidate file. UTF-8 first (with BOM tolerance), then CP936, then
    latin-1 as a last resort so that the gate reports a hit rather than an
    encoding crash. Bytes are never silently dropped.
    """
    with open(path, "rb") as handle:
        blob = handle.read()
    for encoding in ("utf-8-sig", "cp936", "latin-1"):
        try:
            return blob.decode(encoding)
        except UnicodeDecodeError:
            continue
    return blob.decode("latin-1", errors="replace")


def is_ascii(text: str) -> bool:
    try:
        text.encode("ascii")
        return True
    except UnicodeEncodeError:
        return False


def identifier_segments(token: str):
    """Split an ASCII identifier into segments. `mp_refund_x` -> mp, refund, x."""
    for part in _IDENT_SEP_RE.split(token):
        if not part:
            continue
        for hump in _CAMEL_RE.split(part):
            if hump:
                yield hump


def contains_adjacent_run(haystack: list, needle: list) -> bool:
    """
    True if `needle` occurs in `haystack` as a CONTIGUOUS run of segments.

    Contiguity is the whole point: `blood_sugar` must fire on
    `mp_blood_sugar_tap` (a leak wearing a prefix and a suffix) but must NOT
    fire on `sugar_blood` (segments merely reordered) or on `blood_x_sugar`
    (segments present but not adjacent). A plain subset test would report both
    of those, which is a false positive nobody could explain.
    """
    if not needle or len(needle) > len(haystack):
        return False
    first = needle[0]
    span = len(needle)
    for start in range(len(haystack) - span + 1):
        if haystack[start] != first:
            continue
        if haystack[start:start + span] == needle:
            return True
    return False


def escape_expanded_variants(line: str):
    """
    Yield the raw line plus every escape-decoded variant of it.

    Scanning only the raw text is defeated by `"\\u9000\\u6b3e"`: the file bytes
    are pure ASCII, the gate sees nothing, and the shipped string is the
    forbidden word. Decoding the escapes before matching closes that path. The
    raw form is always included so that plain-text hits are still reported with
    their original wording.
    """
    variants = [line]
    try:
        decoded_u = _U_ESCAPE_RE.sub(
            lambda m: chr(int(m.group(1), 16)), line
        )
        if decoded_u != line:
            variants.append(decoded_u)
        decoded_x = _X_ESCAPE_RE.sub(
            lambda m: chr(int(m.group(1), 16)), line
        )
        if decoded_x != line:
            variants.append(decoded_x)
        both = _U_ESCAPE_RE.sub(
            lambda m: chr(int(m.group(1), 16)),
            _X_ESCAPE_RE.sub(lambda m: chr(int(m.group(1), 16)), line),
        )
        if both not in variants:
            variants.append(both)
    except (ValueError, OverflowError):
        # A malformed escape must never make the gate crash: crashing turns a
        # suspicious file into an unscanned file. Fall back to the raw forms.
        pass
    return variants


def check_subscribe_message_time_anchors(pkg_root_abs: str, repo_root: str):
    """
    F-2: 每条订阅消息模板正文必须含时间锚点（_requirements_locked 第③条）。

    只读取 client-package/subscribe-messages/templates.json 的
    templates.*.content，用 _TIME_ANCHOR_RE 判据；缺锚点的模板作为违例并入
    all_hits（使构建失败）。该 json 是 subscribe-messages 通道的声明文件，通道
    存在性已由 channel parity 把关，这里只补上"时间锚点"这一条此前无人读取的判据，
    使终稿换文案时第③条不能被悄悄丢掉——见本仓第 89 条"手写覆盖宣言没人守"。
    """
    violations = []
    path = os.path.join(pkg_root_abs, "subscribe-messages", "templates.json")
    if not os.path.isfile(path):
        # 通道目录存在性由 channel parity 负责，这里不重复报错。
        return violations
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, ValueError) as exc:
        return [{
            "face": "SUBSCRIBE_TIME_ANCHOR",
            "term": "<unreadable>",
            "file": os.path.relpath(path, repo_root),
            "line": 0,
            "text": "templates.json 无法读取: %s" % exc,
        }]
    templates = data.get("templates") or {}
    rel = os.path.relpath(path, repo_root)
    for name, body in templates.items():
        content = (body or {}).get("content", "")
        if not _TIME_ANCHOR_RE.search(content):
            violations.append({
                "face": "SUBSCRIBE_TIME_ANCHOR",
                "term": "<missing-time-anchor>",
                "file": rel,
                "line": 0,
                "text": "模板 %r 正文缺少时间锚点（requirement ③：XX 小时内）"
                        % name,
            })
    return violations


def line_matches(line: str, term: str) -> bool:
    """
    Decide whether a wordlist term occurs in a line.

    The rule differs by writing system -- see the module docstring for why.
    Both the line and the term are normalised first, so case and Unicode
    homoglyphs cannot be used to slip past the gate. The line is also checked in
    its escape-decoded form, so a `\\uXXXX`-encoded string cannot hide a term.
    """
    needle = norm(term)
    if not needle:
        return False
    for variant in escape_expanded_variants(line):
        if is_ascii(needle):
            # Identifier semantics, compared at three levels. All are required:
            #   whole token  -> catches a multi-word term written as one
            #                   identifier, e.g. `gap_reason`, `AS_refund`,
            #                   `client_sync_state`. Without this level such a
            #                   term can never match, because the token gets
            #                   split on '_' before the comparison.
            #   each segment -> catches a SINGLE-word term embedded in a longer
            #                   identifier, e.g. `refund` inside
            #                   `mp_refund_entry_tap` or `RefundView`. This is
            #                   the channel ADR-12 names loudest.
            #   adjacent run -> catches a MULTI-word term embedded in a longer
            #                   identifier, e.g. `blood_sugar` inside
            #                   `mp_blood_sugar_tap` or `bloodSugarView`.
            #                   Without it a multi-word term only matched when
            #                   the identifier was spelled exactly like the term,
            #                   which is the one shape an accidental leak is
            #                   least likely to have.
            #
            # The term must appear as a CONTIGUOUS run of token segments:
            # `blood_sugar` matches `mp_blood_sugar_tap` but neither
            # `sugar_blood` (wrong order) nor `blood_x_sugar` (interrupted).
            # A term is also matched segment-wise at the whole-token level, so
            # `refundable` still stays quiet -- no level matches a fragment.
            #
            # Known risk, accepted and NOT special-cased: a term whose segments
            # are individually ordinary words can collide with an unrelated
            # name of the same shape, e.g. `blood_pressure` with a future
            # `bloodPressureCuff`. Conditioning the rule on "the term has a
            # sensitive tail" was rejected on purpose: it would make the gate
            # unexplainable, and a rule nobody can explain gets switched off.
            for raw_token in _TOKEN_RE.findall(variant):
                if norm(raw_token) == needle:
                    return True
                segments = [norm(s) for s in identifier_segments(raw_token)]
                for segment in segments:
                    if segment == needle:
                        return True
                needle_segments = [norm(s) for s in identifier_segments(term)]
                if len(needle_segments) >= 2:
                    if contains_adjacent_run(segments, needle_segments):
                        return True
        else:
            # CJK: the sentence is one token, so substring matching is the only
            # rule that can find the term at all. The wordlist is curated
            # against the collision risk this creates.
            if needle in norm(variant):
                return True
    return False


def resolve_root(repo_root: str, root: str) -> str:
    """Resolve a manifest path against the repository root.

    Shared on purpose. The scan faces, the channel parity check and the
    client-package-root check must never disagree about where a relative
    manifest path points: if they did, "the root is not there" and "a channel
    went missing" could be reported as the same problem, which is exactly the
    diagnostic defect this helper exists to prevent.
    """
    return root if os.path.isabs(root) else os.path.join(repo_root, root)


def scan_face(face: str, terms: list, roots: list, repo_root: str,
              allow_missing: bool):
    """Scan one face across all of its roots. Returns (hits, notes)."""
    hits = []
    notes = []
    for root in roots:
        abs_root = resolve_root(repo_root, root)
        if not os.path.isdir(abs_root):
            if allow_missing:
                notes.append(
                    "root absent, NOT scanned: %s (%s)" % (root, ENV_ALLOW)
                )
                continue
            raise GateConfigError(
                "scanned root does not exist: %s -- refusing to pass. "
                "A gate that scans nothing reports success; set %s=1 only for "
                "a deliberate dry-run." % (root, ENV_ALLOW)
            )
        for path in iter_source_files(abs_root):
            text = read_text_file(path)
            rel = os.path.relpath(path, repo_root)
            for lineno, line in enumerate(text.splitlines(), start=1):
                if not line.strip():
                    continue
                for term in terms:
                    if line_matches(line, term):
                        hits.append({
                            "face": face,
                            "term": term,
                            "file": rel,
                            "line": lineno,
                            "text": line.strip()[:240],
                        })
    return hits, notes


def harden_console_encoding():
    """
    Pin stdout/stderr to UTF-8.

    This machine's platform encoding is GBK. A violation report quotes the
    offending source line, which is usually Chinese, so printing it under a GBK
    console raises UnicodeEncodeError -- and a scanner that crashes on the very
    input it exists to report is worse than useless: it turns a compliance
    failure into an infrastructure failure that somebody will "fix" by disabling
    the gate. errors='replace' keeps the ASCII verdict line and the file:line
    location readable no matter what the terminal can render.
    """
    for stream_name in ("stdout", "stderr"):
        stream = getattr(sys, stream_name, None)
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            # Cannot be reconfigured (already wrapped by Maven, redirected, or
            # captured). Not fatal: the verdict and exit code are unaffected.
            pass


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="ADR-12 build-time compliance scanner",
    )
    parser.add_argument(
        "--repo-root", default=os.getcwd(),
        help="repository root that relative paths resolve against",
    )
    parser.add_argument(
        "--manifest", default=None,
        help="manifest json describing faces, roots, wordlists and owners",
    )
    parser.add_argument(
        "--report", default=None,
        help="optional path to write a machine-readable JSON report",
    )
    parser.add_argument(
        "--quiet", action="store_true", help="only print the verdict line",
    )
    parser.add_argument(
        "--require-named-owners", action="store_true",
        help="fail the build while any owner row is still a placeholder "
             "(role: label or unreachable contact). Default: report the "
             "placeholder count as a note only, which is the go-live-blocker "
             "state the README already records.",
    )
    return parser.parse_args(argv)


def resolve_manifest(args) -> str:
    if args.manifest:
        return os.path.abspath(args.manifest)
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "scan-manifest.json")


def main(argv):
    harden_console_encoding()
    args = parse_args(argv)
    repo_root = os.path.abspath(args.repo_root)
    manifest_path = resolve_manifest(args)

    try:
        with io.open(manifest_path, "r", encoding="utf-8") as handle:
            manifest = json.load(handle)
    except (OSError, ValueError) as exc:
        sys.stderr.write("[GATE-ERROR] manifest unreadable: %s (%s)\n"
                         % (manifest_path, exc))
        return 2

    declared = tuple(manifest.get("scan_faces") or ())
    if sorted(declared) != sorted(SCAN_FACES):
        sys.stderr.write(
            "[GATE-ERROR] scan surfaces do not match the ADR-12 ruling.\n"
            "  expected (ADR-12, immutable): %s\n"
            "  declared in manifest        : %s\n"
            % (", ".join(SCAN_FACES), ", ".join(declared) or "<none>")
        )
        return 4

    allow_missing = os.environ.get(ENV_ALLOW) == "1"
    owners_rel = manifest.get("owners", "owners.csv")
    all_hits = []
    all_notes = []
    audit = []
    owner_failures = []

    # ------------------------------------------------------------------
    # The client package root itself. Checked BEFORE and independently of
    # channel parity, because the two produce the same symptom -- "directory
    # absent" -- from completely different causes, and conflating them sends
    # the reader to the wrong fix. A root that is not there is usually an
    # invocation problem: --repo-root defaults to os.getcwd(), so running the
    # script from inside compliance/ points the whole gate at
    # compliance/client-package. Reported as a channel-parity violation, that
    # made every one of the seven channels look absent at once and then
    # suggested a dry-run opt-out -- i.e. it invited the reader to convert a
    # load-bearing gate into a passing no-op. The message below therefore
    # names the resolved absolute path, says plainly that no channel was
    # dropped, and never mentions the dry-run opt-out.
    # ------------------------------------------------------------------
    pkg_root = manifest.get("client_package_root", "client-package")
    pkg_root_abs = resolve_root(repo_root, pkg_root)
    if not os.path.isdir(pkg_root_abs):
        if allow_missing:
            all_notes.append(
                "client package root absent, NOT scanned: %s (%s)"
                % (pkg_root_abs, ENV_ALLOW)
            )
        else:
            sys.stderr.write(
                "[GATE-ERROR] client package root not found: %s\n"
                "  This is not a content violation and not a channel-parity "
                "failure: no channel was dropped from the manifest. The "
                "manifest declares the root as %r, which resolves against "
                "the repository root to the path above.\n"
                "  The usual cause is the working directory. --repo-root "
                "defaults to the current directory, so run the gate from the "
                "repository root, or pass --repo-root=<path> explicitly.\n"
                "  Refusing to report a verdict for a scan surface that is "
                "not there.\n"
                % (pkg_root_abs, pkg_root)
            )
            return 2

    # ------------------------------------------------------------------
    # Channel parity for scan face 1. A missing channel is a silent hole:
    # nothing fails, the gate prints PASS, and the forbidden wording keeps
    # reaching the customer through the channel nobody remembered to add.
    # ------------------------------------------------------------------
    parity_misses = []
    declared_channels = list(manifest.get("required_channels") or [])
    for channel in CANONICAL_CHANNELS:
        expected = "%s/%s" % (pkg_root, channel)
        # The manifest is the source of truth for what must be scanned. A
        # channel that is declared but whose directory has gone missing is also
        # a hole, so both directions are checked.
        if expected not in declared_channels:
            parity_misses.append("channel not declared in manifest: %s" % expected)
            continue
        if not os.path.isdir(resolve_root(repo_root, expected)):
            parity_misses.append("declared channel directory absent: %s" % expected)
    if parity_misses:
        if allow_missing:
            for miss in parity_misses:
                all_notes.append("CHANNEL PARITY NOT ENFORCED (%s): %s"
                                 % (ENV_ALLOW, miss))
        else:
            sys.stderr.write(
                "[GATE-ERROR] scan face 1 channel parity violated. A channel "
                "that is not scanned is exactly how 'the page was changed but "
                "the push was not' survives to production:\n"
            )
            for miss in parity_misses:
                sys.stderr.write("  - %s\n" % miss)
            sys.stderr.write("  set %s=1 only for a deliberate dry-run.\n"
                             % ENV_ALLOW)
            return 2

    owners_path = owners_rel if os.path.isabs(owners_rel) \
        else os.path.join(os.path.dirname(manifest_path), owners_rel)
    try:
        owner_records = parse_owners_table(owners_path)
        owners = load_owners(owners_path)
    except GateConfigError as exc:
        sys.stderr.write("[GATE-ERROR] %s\n" % exc)
        return 2

    # ------------------------------------------------------------------
    # Owner readiness. The owner-missing check below answers "is the column
    # filled in"; it cannot answer "is anybody accountable". A row reading
    # `role:dev-compliance-lead` with `tbd@...invalid` satisfies the first and
    # fails the second, so the two are judged, reported and counted separately.
    # The default stays advisory on purpose: promoting a known go-live blocker
    # to a build-blocking failure without an explicit opt-in would redden every
    # build in the repository the moment it landed, and a gate that is red on
    # arrival gets switched off. --require-named-owners is the opt-in the go-live
    # pipeline (and the test witness) uses to make it load-bearing.
    # ------------------------------------------------------------------
    readiness = assess_owner_readiness(owner_records)
    placeholder_subjects = [item[0] for item in readiness["placeholder"]]
    named_count = len(readiness["named"])
    total_subjects = len(owner_records)

    for face in SCAN_FACES:
        spec = manifest["faces"].get(face)
        if spec is None:
            sys.stderr.write("[GATE-ERROR] face missing from manifest: %s\n"
                             % face)
            return 4
        wordlist_rel = spec["wordlist"]
        wordlist_path = wordlist_rel if os.path.isabs(wordlist_rel) \
            else os.path.join(os.path.dirname(manifest_path), wordlist_rel)
        try:
            terms = load_wordlist(wordlist_path)
        except GateConfigError as exc:
            sys.stderr.write("[GATE-ERROR] %s\n" % exc)
            return 2

        owner = owners.get(face, "")
        if not owner:
            owner_failures.append(face)

        roots = spec.get("roots") or []
        try:
            hits, notes = scan_face(face, terms, roots, repo_root, allow_missing)
        except GateConfigError as exc:
            sys.stderr.write("[GATE-ERROR] %s\n" % exc)
            return 2

        all_hits.extend(hits)
        all_notes.extend(notes)
        audit.append({
            "face": face,
            "owner": owner or "<EMPTY>",
            "wordlist": wordlist_rel,
            "term_count": len(terms),
            "roots": roots,
            "hits": len(hits),
        })

    # ------------------------------------------------------------------
    # owner-missing discipline. A rule with no owner is a failed rule.
    # ------------------------------------------------------------------
    if owner_failures:
        all_hits.append({
            "face": "OWNER_DISCIPLINE",
            "term": "<owner-missing>",
            "file": manifest.get("owners", "owners.csv"),
            "line": 0,
            "text": "rule without owner is a dead rule: %s"
                    % ", ".join(owner_failures),
        })

    # ------------------------------------------------------------------
    # F-2 时间锚点判据（2026-10-09）。_requirements_locked 第③条要求每个订阅消息
    # 模板正文带时间锚点，此前只写在数据里、无脚本读取，终稿换文案时会静默丢失——
    # 见本仓第 89 条"手写覆盖宣言没人守"。这里把它变成机器判据，使换文案不能悄悄
    # 丢掉这一条（SUBSCRIBE_TIME_ANCHOR 违例并入 all_hits，构建失败）。
    # ------------------------------------------------------------------
    anchor_violations = check_subscribe_message_time_anchors(
        pkg_root_abs, repo_root)
    all_hits.extend(anchor_violations)

    # ------------------------------------------------------------------
    # Owner readiness verdict. Only the explicit opt-in turns placeholders into
    # a failure; without it the count is a NOTE, so the default build behaviour
    # is unchanged down to the report line.
    # ------------------------------------------------------------------
    if placeholder_subjects:
        if args.require_named_owners:
            for subject, owner_id, reasons in readiness["placeholder"]:
                all_hits.append({
                    "face": "OWNER_READINESS",
                    "term": "<owner-not-named>",
                    "file": manifest.get("owners", "owners.csv"),
                    "line": 0,
                    "text": "subject %s is still owned by a placeholder "
                            "(%s): %s" % (subject, owner_id or "<EMPTY>",
                                          "; ".join(reasons)),
                })
        else:
            all_notes.append(
                "owner readiness NOT enforced: %d of %d subjects are still "
                "placeholders (%s). Pass --require-named-owners to fail the "
                "build on this; a placeholder owner is a go-live blocker, not "
                "an accountability."
                % (len(placeholder_subjects), total_subjects,
                   ", ".join(placeholder_subjects))
            )

    verdict = "FAIL" if all_hits else "PASS"
    stamp = datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")

    if not args.quiet:
        print("=" * 79)
        print("ADR-12 BUILD-TIME COMPLIANCE SCAN  --  %s" % verdict)
        print("repo-root : %s" % repo_root)
        print("manifest  : %s" % manifest_path)
        print("stamp     : %s" % stamp)
        print("=" * 79)
        print("")
        print("SCAN FACE AUDIT (a face with 0 roots or 0 terms is not a scan)")
        print("-" * 79)
        for row in audit:
            print("  %-16s owner=%-28s terms=%-4d roots=%d hits=%d"
                  % (row["face"], row["owner"], row["term_count"],
                     len(row["roots"]), row["hits"]))
            for root in row["roots"]:
                print("        root: %s" % root)
        print("")
        for note in all_notes:
            print("  [NOTE] %s" % note)
        if all_notes:
            print("")

        # ------------------------------------------------------------------
        # Owner readiness, printed per subject. The roster is the point: a
        # summary count tells the reader there is a problem, the roster tells
        # the reader WHICH rows to fill in -- and an unfillable roster is how a
        # blocker stays a blocker instead of quietly becoming a number.
        # ------------------------------------------------------------------
        if readiness["placeholder"] and args.require_named_owners:
            print("OWNER READINESS (--require-named-owners: placeholder owners "
                  "fail the build)")
            print("-" * 79)
            for subject, owner_id, reasons in readiness["placeholder"]:
                print("  [OWNER_READINESS] %s" % subject)
                print("      current owner_id: %s" % (owner_id or "<EMPTY>"))
                print("      why not named   : %s" % "; ".join(reasons))
            print("  ACTION REQUIRED: the named owner MUST be written into "
                  "%s by the R&D side"
                  % manifest.get("owners", "owners.csv"))
            print("  (the drafting side cannot supply it; a role label is not "
                  "an accountable owner)")
            print("")

        if all_hits:
            print("VIOLATIONS (a hit fails the build; it is never a warning)")
            print("-" * 79)
            for hit in all_hits:
                print("  [%s] term=%r" % (hit["face"], hit["term"]))
                print("      at %s:%s" % (hit["file"], hit["line"]))
                print("      source: %s" % hit["text"])
            print("")
        else:
            print("no violations found on any scan face")
            print("")

    if args.report:
        report = {
            "verdict": verdict,
            "generated_at": stamp,
            "repo_root": repo_root,
            "manifest": manifest_path,
            "faces": audit,
            "notes": all_notes,
            "violations": all_hits,
            "owner_readiness": {
                "enforced": bool(args.require_named_owners),
                "named": named_count,
                "total": total_subjects,
                "placeholder_subjects": placeholder_subjects,
            },
        }
        report_dir = os.path.dirname(os.path.abspath(args.report))
        if report_dir and not os.path.isdir(report_dir):
            os.makedirs(report_dir, exist_ok=True)
        with io.open(args.report, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(report, ensure_ascii=False, indent=2))

    print("[COMPLIANCE-GATE] %s  faces=%d  violations=%d  owners_ok=%s  "
          "owners_named=%d/%d"
          % (verdict, len(SCAN_FACES), len(all_hits),
             "yes" if not owner_failures else "no",
             named_count, total_subjects))

    return 1 if all_hits else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as exc:  # noqa: BLE001 - the gate must never crash-silently
        sys.stderr.write("[GATE-ERROR] internal scanner error: %r\n" % (exc,))
        sys.exit(3)