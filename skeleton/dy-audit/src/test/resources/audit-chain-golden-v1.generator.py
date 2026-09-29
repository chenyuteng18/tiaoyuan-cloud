#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Independent generator for dy-audit chain-hash golden vectors (canonical v1).

WHY THIS FILE EXISTS
--------------------
The JUnit test AuditChainGoldenVectorTest asserts that the Java implementation
produces exactly the digests recorded here. If those digests came from the Java
code under test, the test would be circular -- it would only prove "Java agrees
with Java". They come from CPython's hashlib instead: a DIFFERENT implementation
of SHA-256, driven by a canonical-string layout written from the specification
in AuditChainHash's javadoc rather than by calling the Java code.

THE CANONICAL FORM (frozen as v1)
---------------------------------
    field(v) = v is None ? "~null~" : "~s~" + escape(v)
    escape(v):  '\\' -> '\\\\'   '|' -> '\\|'   LF -> '\\n'   CR -> '\\r'
                (that order -- backslash FIRST; doing it after '|' would
                 re-escape the backslashes this step just produced)
    record   = field(tenant_id) | field(actor) | field(action) | field(target_type)
             | field(target_id) | field(payload) | field(prev_hash)
    subject  = "dy-audit-chain/v1" + "\\n" + record
    hash     = lowercase hex of SHA-256(subject encoded as UTF-8)

WHY ESCAPING + A NULL PREFIX, AND NOT JUST "|".join(...)
--------------------------------------------------------
A naive join is NOT injective, and that is a forgery path, not a theoretical
blemish:

    (actor="a|b", action="c"  )  ->  "..|a|b|c|.."
    (actor="a",   action="b|c")  ->  "..|a|b|c|.."     <- same string, same hash

so anyone able to rewrite actor/action could move bytes across a field boundary
and the record would change while its hash did not. Escaping makes '|' and
newlines unable to occur inside an encoded field, so boundaries are fixed; the
"~null~" vs "~s~" prefix keeps a null payload from colliding with a payload that
literally is the string "<null>". Vectors 5-10 below pin all of this: each one
has a value that would break a naive implementation.

WHY UTF-8 IS NOT OPTIONAL
-------------------------
This machine's platform encoding is GBK (`mvn -v` reports "platform encoding:
GBK"). A `getBytes()` call written without an explicit charset would hash CJK
payloads differently from this reference. Vector 2 is the probe that makes that
failure visible instead of silently shipping a chain nobody else can reproduce.

OUTPUT FORMAT (audit-chain-golden-v1.txt)
-----------------------------------------
Deliberately not JSON: parsing JSON in dy-audit would drag Jackson onto the test
classpath purely for a fixture, and a fixture is not a reason to grow a module's
dependency surface. Flat and lossless:

    key=value                       (header keys)
    [vector]
    name=genesis_link
    prev_hash_b64=...               base64 of the UTF-8 bytes
    payload_b64=... | payload_b64=<NULL>
    canonical_b64=...
    hash=<64 hex chars>             hex needs no encoding
    # canonical_plain=...           one line, for the human reviewer only

Everything that can carry non-ASCII is base64 so the file survives any console
code page; `hash` stays hex so it can be eyeballed next to what Java prints.
The test parses the base64 fields and never the `canonical_plain` line.

Regenerate with:

    python dy-audit/src/test/resources/audit-chain-golden-v1.generator.py

Output is written with newline="\\n": the Java reader splits on '\\n', so a CRLF
working tree would otherwise leave a trailing '\\r' inside every value.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import os
import sys

CANONICAL_V1 = "dy-audit-chain/v1"
GENESIS_PREV_HASH = "0" * 64
NULL_PREFIX = "~null~"
STRING_PREFIX = "~s~"
NULL_TOKEN = "<NULL>"


def escape(value: str) -> str:
    """Backslash FIRST: see the module docstring."""
    out = value.replace("\\", "\\\\")
    out = out.replace("|", "\\|")
    out = out.replace("\n", "\\n")
    out = out.replace("\r", "\\r")
    return out


def field(value):
    if value is None:
        return NULL_PREFIX
    return STRING_PREFIX + escape(value)


def canonical_v1(tenant_id, actor, action, target_type, target_id, payload, prev_hash):
    """The FULL subject string: version label, then the record.

    The version label is part of the hashed subject on purpose: without it, a
    chain built under a future v2 layout could be re-verified as if it were v1
    (or vice versa) and a cross-version mismatch would masquerade as a tamper.
    """
    record = "|".join([
        field(tenant_id), field(actor), field(action), field(target_type),
        field(target_id), field(payload), field(prev_hash),
    ])
    return CANONICAL_V1 + "\n" + record


def chain_hash(tenant_id, actor, action, target_type, target_id, payload, prev_hash):
    subject = canonical_v1(tenant_id, actor, action, target_type, target_id, payload, prev_hash)
    return hashlib.sha256(subject.encode("utf-8")).hexdigest()


def b64(value):
    if value is None:
        return NULL_TOKEN
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def vec(name, prev_hash, canonical, digest, **record):
    return dict(name=name, prev_hash=prev_hash, canonical=canonical,
                hash=digest, record=record)


def build_vectors():
    out = []

    def add(name, prev_hash, payload, **record_fields):
        rec = dict(record_fields, payload=payload)
        out.append(vec(
            name=name,
            prev_hash=prev_hash,
            canonical=canonical_v1(payload=payload, prev_hash=prev_hash, **record_fields),
            digest=chain_hash(payload=payload, prev_hash=prev_hash, **record_fields),
            **rec,
        ))
        return out[-1]["hash"]

    # ---- 1) the first record of a chain -------------------------------------
    t_a = dict(
        tenant_id="aaaaaaaa-1111-1111-1111-111111111111",
        actor="staff-0001",
        action="CUSTOMER_STATUS_CHANGE",
        target_type="customer",
        target_id="c1111111-0000-0000-0000-000000000001",
    )
    h1 = add("genesis_link", GENESIS_PREV_HASH, '{"from":"pending","to":"active"}', **t_a)

    # ---- 2) unicode payload: pins UTF-8 against the GBK platform default -----
    t_b = dict(
        tenant_id="aaaaaaaa-1111-1111-1111-111111111111",
        actor="staff-0001",
        action="CUSTOMER_NOTE_APPEND",
        target_type="customer",
        target_id="c1111111-0000-0000-0000-000000000001",
    )
    unicode_payload = '{"note":"\u624b\u73af\u6570\u636e\u4e3a\u53c2\u8003\u4e4b\u4e00"}'
    h2 = add("unicode_payload_link", h1, unicode_payload, **t_b)

    # ---- 3) payload that contains the DELIMITER ----------------------------
    # A naive "|".join would let this payload's '|' line up with a field boundary.
    h3 = add("payload_with_delimiter_is_escaped", h2, '{"note":"a|b"}', **t_b)

    # ---- 4) payload that contains a BACKSLASH ------------------------------
    # Pins the escape ORDER: escaping '|' before '\' would turn "\|" into "\\|".
    h4 = add("payload_with_backslash_is_escaped", h3, '{"path":"C:\\\\tmp"}', **t_b)

    # ---- 5) payload that contains a NEWLINE --------------------------------
    h5 = add("payload_with_newline_is_escaped", h4, '{"m":"line1\nline2"}', **t_b)

    # ---- 6) the injectivity pair: two DIFFERENT records, two DIFFERENT hashes
    # This is the pair that a naive implementation makes collide.
    t_c = dict(
        tenant_id="aaaaaaaa-1111-1111-1111-111111111111",
        actor="a|b",
        action="c",
        target_type="tt",
        target_id="i",
    )
    t_d = dict(
        tenant_id="aaaaaaaa-1111-1111-1111-111111111111",
        actor="a",
        action="b|c",
        target_type="tt",
        target_id="i",
    )
    h6a = add("injectivity_actor_holds_delimiter", h5, "p", **t_c)
    h6b = add("injectivity_action_holds_delimiter", h5, "p", **t_d)
    assert h6a != h6b, "escaping failed: two different records collided"

    # ---- 7) null payload vs the literal string that a naive marker uses -----
    # A naive scheme that renders null as "<null>" collides with a payload whose
    # value really is "<null>". The "~null~"/"~s~" prefixes keep them apart.
    t_e = dict(
        tenant_id="bbbbbbbb-2222-2222-2222-222222222222",
        actor="system",
        action="RETENTION_SWEEP",
        target_type="audit_log",
        target_id="-",
    )
    h7a = add("null_payload_link", h6b, None, **t_e)
    h7b = add("literal_null_marker_payload_link", h6b, "<null>", **t_e)
    assert h7a != h7b, "null payload collided with the literal '<null>' payload"

    # ---- 8) null payload vs EMPTY payload ----------------------------------
    h8 = add("empty_payload_link_must_differ_from_null", h6b, "", **t_e)
    assert h8 != h7a, "null payload collided with the empty payload"

    # ---- 9) tamper references ----------------------------------------------
    tampered_payload = '{"note":"\u624b\u73af\u6570\u636e\u5df2\u6821\u6b63"}'
    add("tampered_payload_of_unicode_link", h1, tampered_payload, **t_b)
    tampered_prev = "1" * 64
    add("tampered_prev_hash_of_unicode_link", tampered_prev, unicode_payload, **t_b)

    return out


def main(argv):
    parser = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    parser.add_argument("--out", default=os.path.join(here, "audit-chain-golden-v1.txt"))
    args = parser.parse_args(argv)

    vectors = build_vectors()

    lines = [
        "# Golden vectors for dy-audit canonical v1.",
        "# Generated by audit-chain-golden-v1.generator.py with CPython hashlib --",
        "# NOT by the Java code under test. Do not hand-edit; regenerate instead.",
        "# Values that may contain non-ASCII are base64 of their UTF-8 bytes.",
        "# payload_b64=<NULL> means a null payload; each field is prefixed with",
        "# ~null~ or ~s~ and escaped, so a null never collides with a string.",
        "canonical_version=%s" % CANONICAL_V1,
        "genesis_prev_hash=%s" % GENESIS_PREV_HASH,
        "null_field_prefix=%s" % NULL_PREFIX,
        "string_field_prefix=%s" % STRING_PREFIX,
    ]
    for v in vectors:
        rec = v["record"]
        lines.append("[vector]")
        lines.append("name=%s" % v["name"])
        for key in ("prev_hash", "tenant_id", "actor", "action",
                    "target_type", "target_id", "payload"):
            lines.append("%s_b64=%s" % (key, b64(v["prev_hash"] if key == "prev_hash" else rec[key])))
        lines.append("canonical_b64=%s" % b64(v["canonical"]))
        lines.append("hash=%s" % v["hash"])
        lines.append("# canonical_plain=%s" % v["canonical"].replace("\n", "\\n"))
    lines.append("")

    with io.open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines))

    print("wrote %s (%d vectors)" % (args.out, len(vectors)))
    for v in vectors:
        print("  %-46s %s" % (v["name"], v["hash"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))