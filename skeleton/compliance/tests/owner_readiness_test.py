#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADR-12 compliance gate -- OWNER READINESS witness.

WHY THIS FILE EXISTS
--------------------
The gate already refused an owner column that was EMPTY. It had no opinion at
all about an owner column that was FILLED IN WITH A PLACEHOLDER, which is the
state the repository was actually in: every row read
`role:dev-compliance-lead` / `role:client-package-owner` with
`tbd@diaoyuanyun.invalid`, and the gate printed `owners_ok=yes`. The README
recorded this as a P0 go-live blocker, i.e. the only thing holding the line was
somebody remembering to read the README.

This witness makes the distinction mechanically checkable: NAMED versus
PLACEHOLDER, judged by fixed rules, reported per subject, and -- under
`--require-named-owners` -- build-blocking.

WHAT IS ASSERTED (all BEHAVIOURAL: the real gate is driven as a subprocess and
the assertions read the gate's own output and exit code)

  R0  default is unchanged: no switch, the real placeholder table still PASSES,
      the pre-existing `owners_ok` field is still printed, and `owners_named`
      reports the readiness count alongside it
  R1  `--require-named-owners` against the real placeholder table -> exit 1,
      with EVERY placeholder subject named in the output
  R2  injection on a TEMPORARY COPY: one row given a real name + reachable
      contact turns that subject NAMED (1/4) while the other three stay
      placeholder; the real `owners.csv` is proven untouched by hash
  R3  all four rows named -> the switch PASSES (the check is a gate, not a
      permanent red light)
  R4  boundary rules, each pinned separately: empty contact, `tbd` contact,
      `invalid` contact, and a `role:` label with an otherwise perfect contact
      are ALL placeholders; a real name with a routable mailbox is NAMED
  R5  `--quiet` suppresses the roster but NOT the verdict line, and `--report`
      still emits parseable JSON carrying the readiness block

The rules are asserted through the gate's observable output, never by importing
a helper and calling it: what matters is what CI sees.

Usage
-----
  python compliance/tests/owner_readiness_test.py
Exit code 0 = all assertions held.
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
COMPLIANCE_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(COMPLIANCE_DIR)
OWNERS = os.path.join(COMPLIANCE_DIR, "owners.csv")

# Reuse the existing harness plumbing. Importing it is safe: its main() only
# runs under __main__, and its helpers are exactly the drive-the-real-gate
# behaviour this witness needs. Re-implementing them here would mean two
# harnesses could disagree about how the gate is invoked.
sys.path.insert(0, HERE)
from compliance_injection_test import (  # noqa: E402
    AssertionFailed, check, run_gate, stage_manifest,
)

# An INDEPENDENT copy of the current placeholder roster, written out by hand.
# If owners.csv is edited -- someone naming a real owner, say -- this witness
# goes red and forces the reader to update the expectation deliberately, rather
# than silently re-baselining the blocker away.
PLACEHOLDER_SUBJECTS = (
    "scan1_refund", "scan2_negative", "scan3_derived", "client-package",
)

SUMMARY_RE = re.compile(r"owners_named=(\d+)/(\d+)")
# Matches ONLY the roster header line, e.g. "  [OWNER_READINESS] scan1_refund".
# The violation lines share the tag -- "[OWNER_READINESS] term='<owner-not-named>'"
# -- and must NOT be read as subjects, or every assertion below would pass on the
# literal string `term=` instead of on real subject names.
ROSTER_RE = re.compile(r"^\[OWNER_READINESS\]\s+([A-Za-z0-9._:-]+)\s*$")

HEADER = "subject,owner_id,owner_display,contact,escalation\n"


def owners_digest(path):
    """Hash of the REAL owners.csv, so the witness can prove it left it alone."""
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def summary_counts(out):
    """(named, total) from the verdict line, or None if it is not there."""
    found = SUMMARY_RE.search(out)
    if not found:
        return None
    return int(found.group(1)), int(found.group(2))


def roster(out):
    """The set of subjects the gate listed as still placeholder."""
    subjects = set()
    for line in out.splitlines():
        found = ROSTER_RE.match(line.strip())
        if found:
            subjects.add(found.group(1))
    return subjects


def placeholder_row(subject, owner_id="role:dev-compliance-lead",
                    contact="tbd@diaoyuanyun.invalid"):
    return "%s,%s,Placeholder,%s,team-lead\n" % (subject, owner_id, contact)


def named_row(subject, owner_id="zhang.san", contact="zhang.san@example.com"):
    return "%s,%s,Named Owner,%s,team-lead\n" % (subject, owner_id, contact)


# ---------------------------------------------------------------------------
def r0_default_behaviour_is_unchanged():
    """
    The regression that matters most: landing this feature must not redden the
    build. No switch -> the real placeholder table still exits 0, the old
    `owners_ok` field is still emitted, and the new count rides alongside it.
    """
    digest_before = owners_digest(OWNERS)
    code, out, err = run_gate(["--repo-root", REPO_ROOT])
    check(code == 0,
          "R0: without --require-named-owners the current placeholder table must "
          "still PASS, got exit %d\n%s%s" % (code, out, err))
    check("PASS" in out, "R0: default run must report PASS\n%s" % out)
    check("owners_ok=yes" in out,
          "R0: the pre-existing owners_ok field must NOT be removed\n%s" % out)
    check(summary_counts(out) == (0, 4),
          "R0: the readiness count must be reported as owners_named=0/4\n%s" % out)
    check("owners_named=0/4" in out,
          "R0: the verdict line must carry the readiness field\n%s" % out)
    check("owner readiness NOT enforced" in out,
          "R0: the default must say so in a NOTE rather than fail silently or "
          "fail loudly\n%s" % out)
    check(roster(out) == set(),
          "R0: the default must not print the failing roster\n%s" % out)
    check(owners_digest(OWNERS) == digest_before,
          "R0: the real owners.csv was modified by a read-only run")
    return ("no switch -> exit 0, PASS, owners_ok=yes preserved, "
            "owners_named=0/4, placeholder count reported as a NOTE only")


def r1_require_named_owners_fails_and_names_every_subject():
    """
    The load-bearing half. A verdict that says "4 placeholders" without naming
    them is a number nobody can act on; this asserts the roster is complete.
    """
    digest_before = owners_digest(OWNERS)
    code, out, err = run_gate(
        ["--repo-root", REPO_ROOT, "--require-named-owners"]
    )
    check(code == 1,
          "R1: placeholder owners must fail the build under "
          "--require-named-owners, got exit %d\n%s%s" % (code, out, err))
    check("FAIL" in out, "R1: the gate must report FAIL\n%s" % out)
    check(roster(out) == set(PLACEHOLDER_SUBJECTS),
          "R1: the output must name EVERY placeholder subject.\n"
          "expected: %s\nobserved: %s\n%s"
          % (sorted(PLACEHOLDER_SUBJECTS), sorted(roster(out)), out))
    check("owners_named=0/4" in out,
          "R1: the verdict line must report owners_named=0/4\n%s" % out)
    # The readiness failure is NOT an owner-missing failure, and the two must
    # stay distinguishable or the reader is sent to the wrong fix.
    check("owners_ok=yes" in out,
          "R1: a placeholder owner is not a missing owner -- owners_ok must "
          "stay yes so the two failure modes remain distinguishable\n%s" % out)
    check("OWNER_READINESS" in out,
          "R1: the failure must be attributed to OWNER_READINESS\n%s" % out)
    check("owners.csv" in out,
          "R1: the output must name the file to fix\n%s" % out)
    check(owners_digest(OWNERS) == digest_before,
          "R1: the real owners.csv was modified by a read-only run")
    return ("--require-named-owners vs the placeholder table -> exit 1, all %d "
            "subjects named, owners_ok=yes kept distinct from the readiness "
            "failure" % len(PLACEHOLDER_SUBJECTS))


def r2_named_injection_on_a_temporary_copy():
    """
    The direction that proves the check discriminates rather than always
    firing: give ONE subject a real name and a routable contact and exactly
    that subject must leave the placeholder set.

    The injection happens on a TEMPORARY COPY of owners.csv. The real file is
    hashed before and after -- naming the real owner is a decision for the R&D
    side, and a test that "fixes" the blocker by writing a fake signature into
    the real table would be forging an attribution.
    """
    real_digest = owners_digest(OWNERS)
    with tempfile.TemporaryDirectory(prefix="adr12-named-") as tmp:
        text = (
            HEADER
            + named_row("scan1_refund")
            + placeholder_row("scan2_negative")
            + placeholder_row("scan3_derived")
            + placeholder_row("client-package",
                              owner_id="role:client-package-owner")
        )
        manifest_path = stage_manifest(tmp, owners_text=text)

        code, out, err = run_gate(
            ["--repo-root", REPO_ROOT, "--manifest", manifest_path,
             "--require-named-owners"]
        )
        check(code == 1,
              "R2: three of four subjects are still placeholders, so the build "
              "must still fail, got exit %d\n%s%s" % (code, out, err))
        check(summary_counts(out) == (1, 4),
              "R2: exactly one subject must be counted as NAMED (owners_named="
              "1/4)\n%s" % out)
        observed = roster(out)
        check("scan1_refund" not in observed,
              "R2: the named subject must leave the placeholder roster\n%s"
              % out)
        check(observed == {"scan2_negative", "scan3_derived", "client-package"},
              "R2: the other three subjects must remain placeholders.\n"
              "observed: %s\n%s" % (sorted(observed), out))

        # control, same staged table: without the switch the tree is green
        # again, so the failure above came from the switch and not from the
        # staging.
        code2, out2, err2 = run_gate(
            ["--repo-root", REPO_ROOT, "--manifest", manifest_path, "--quiet"]
        )
        check(code2 == 0,
              "R2 control: the same staged table must PASS without the switch, "
              "got exit %d\n%s%s" % (code2, out2, err2))
        check("owners_named=1/4" in out2,
              "R2 control: the count must follow the data, not the switch\n%s"
              % out2)

    check(owners_digest(OWNERS) == real_digest,
          "R2: the REAL owners.csv was modified. Naming the real owner is the "
          "R&D side's decision; a test must never write an attribution into it.")
    return ("one subject named on a temp copy -> owners_named=1/4, that subject "
            "cleared, other three still placeholder, real owners.csv hash "
            "unchanged, same table green without the switch")


def r3_all_named_passes():
    """
    The check must be a gate, not a permanent red light. If naming every owner
    did not turn it green, teams would learn to route around it.
    """
    with tempfile.TemporaryDirectory(prefix="adr12-allnamed-") as tmp:
        text = HEADER + "".join(
            named_row(subject) for subject in PLACEHOLDER_SUBJECTS
        )
        manifest_path = stage_manifest(tmp, owners_text=text)
        code, out, err = run_gate(
            ["--repo-root", REPO_ROOT, "--manifest", manifest_path,
             "--require-named-owners"]
        )
        check(code == 0,
              "R3: with every owner named the switch must PASS, got exit %d\n%s%s"
              % (code, out, err))
        check("owners_named=4/4" in out,
              "R3: the verdict line must report owners_named=4/4\n%s" % out)
        check(roster(out) == set(),
              "R3: nothing may be listed as a placeholder once all are named\n%s"
              % out)
    return ("all four subjects named -> exit 0 under the switch, "
            "owners_named=4/4, empty roster")


# (subject, owner_id, contact, expect_named, why)
BOUNDARY_ROWS = (
    ("b_empty_contact", "zhang.san", "", False,
     "a name with no reachable contact is not an accountable owner"),
    ("b_contact_tbd", "zhang.san", "tbd@example.com", False,
     "a to-be-determined mailbox is a placeholder in a better costume"),
    ("b_contact_tbd_upper", "zhang.san", "TBD@Example.COM", False,
     "the tbd marker is matched case-insensitively"),
    ("b_contact_invalid", "zhang.san", "owner@invalid", False,
     "an unroutable .invalid address cannot receive an escalation"),
    ("b_role_prefix", "role:dev-lead", "lead@example.com", False,
     "a role label is not a person even with a working mailbox"),
    ("b_role_prefix_upper", "ROLE:dev-lead", "lead@example.com", False,
     "the role: prefix is matched case-insensitively"),
    ("b_named", "zhang.san", "zhang.san@example.com", True,
     "a real name plus a routable mailbox is NAMED"),
    ("b_named_second", "li.si", "li.si@example.com", True,
     "a second real name, to prove the count is not a constant"),
)


def r4_boundary_rules_each_pinned():
    """
    Each rule is pinned by its own row, so deleting one rule cannot pass by
    riding on another. The two negative cases with a PERFECT contact
    (`b_role_prefix`, `b_role_prefix_upper`) are the important ones: they prove
    the role check is not merely an alias for the contact check.
    """
    expected_named = {row[0] for row in BOUNDARY_ROWS if row[3]}
    expected_placeholder = {row[0] for row in BOUNDARY_ROWS if not row[3]}
    # The three scan faces must be owned, or the PRE-EXISTING owner-missing
    # discipline fires and this assertion stops being about readiness. They are
    # carried as properly named rows so the two failure modes stay separable.
    face_rows = "".join(named_row(face) for face in
                        ("scan1_refund", "scan2_negative", "scan3_derived"))
    total_subjects = len(BOUNDARY_ROWS) + 3
    named_total = len(expected_named) + 3
    with tempfile.TemporaryDirectory(prefix="adr12-bounds-") as tmp:
        # The boundary table carries ONLY the boundary subjects plus the three
        # faces, so an owner-missing failure here would mean the two
        # disciplines have been conflated.
        text = HEADER + face_rows + "".join(
            "%s,%s,Boundary,%s,team-lead\n" % (subject, owner_id, contact)
            for subject, owner_id, contact, _, _ in BOUNDARY_ROWS
        )
        manifest_path = stage_manifest(tmp, owners_text=text)
        code, out, err = run_gate(
            ["--repo-root", REPO_ROOT, "--manifest", manifest_path,
             "--require-named-owners"]
        )
        check(code == 1,
              "R4: placeholder rows are present, so the switch must fail, got "
              "exit %d\n%s%s" % (code, out, err))
        check("OWNER_DISCIPLINE" not in out,
              "R4: this table has no blank owner, so an owner-missing failure "
              "here would mean the two disciplines have been conflated\n%s"
              % out)
        check(summary_counts(out) == (named_total, total_subjects),
              "R4: expected owners_named=%d/%d\n%s"
              % (named_total, total_subjects, out))
        observed = roster(out)
        check(observed == expected_placeholder,
              "R4: boundary misclassification.\nexpected placeholder: %s\n"
              "observed placeholder: %s\n%s"
              % (sorted(expected_placeholder), sorted(observed), out))
        check(not (observed & expected_named),
              "R4: a NAMED row was reported as a placeholder: %s\n%s"
              % (sorted(observed & expected_named), out))
        # Each rule pinned by name, so deleting one rule cannot hide behind
        # another still firing.
        for subject, owner_id, contact, expect_named, why in BOUNDARY_ROWS:
            check((subject not in observed) == expect_named,
                  "R4[%s] (owner_id=%r, contact=%r): expected %s -- %s\n%s"
                  % (subject, owner_id, contact,
                     "NAMED" if expect_named else "PLACEHOLDER", why, out))
    return ("%d boundary rows classified exactly (%d placeholder, %d named); "
            "empty / tbd / invalid contact and an uppercase role: prefix all "
            "stay placeholder, role: with a working mailbox included"
            % (len(BOUNDARY_ROWS), len(expected_placeholder),
               len(expected_named)))


def r5_quiet_and_report_contract():
    """
    CI consumes two outputs: the verdict line (greppable) and the JSON report
    (machine-readable). `--quiet` must silence the narrative, not the verdict,
    and the report must stay parseable now that it carries a new block.
    """
    code, out, err = run_gate(
        ["--repo-root", REPO_ROOT, "--require-named-owners", "--quiet"]
    )
    check(code == 1,
          "R5: --quiet must not change the exit code, got %d\n%s%s"
          % (code, out, err))
    check("owners_named=0/4" in out,
          "R5: --quiet must still print the verdict line with the readiness "
          "count\n%s" % out)
    check(roster(out) == set(),
          "R5: --quiet must suppress the per-subject roster\n%s" % out)

    with tempfile.TemporaryDirectory(prefix="adr12-report-") as tmp:
        report_path = os.path.join(tmp, "scan.json")
        code2, out2, err2 = run_gate(
            ["--repo-root", REPO_ROOT, "--require-named-owners",
             "--report", report_path, "--quiet"]
        )
        check(code2 == 1,
              "R5: the report run must exit 1, got %d\n%s%s"
              % (code2, out2, err2))
        check(os.path.isfile(report_path),
              "R5: the report file must be written: %s" % report_path)
        with io.open(report_path, "r", encoding="utf-8") as handle:
            report = json.load(handle)
        check(report.get("verdict") == "FAIL",
              "R5: report verdict must be FAIL, got %r"
              % report.get("verdict"))
        readiness = report.get("owner_readiness")
        check(isinstance(readiness, dict),
              "R5: the report must carry the owner_readiness block, got %r"
              % readiness)
        check(readiness.get("named") == 0 and readiness.get("total") == 4,
              "R5: report readiness counts must be 0/4, got %r" % readiness)
        check(readiness.get("enforced") is True,
              "R5: the report must record that readiness was enforced, got %r"
              % readiness)
        check(sorted(readiness.get("placeholder_subjects") or [])
              == sorted(PLACEHOLDER_SUBJECTS),
              "R5: the report must list every placeholder subject, got %r"
              % readiness.get("placeholder_subjects"))
    return ("--quiet keeps the verdict line and drops the roster; --report "
            "emits parseable JSON carrying owner_readiness 0/4 enforced=true")


def main():
    assertions = [
        ("R0 default unchanged", r0_default_behaviour_is_unchanged),
        ("R1 require-named fails", r1_require_named_owners_fails_and_names_every_subject),
        ("R2 named injection", r2_named_injection_on_a_temporary_copy),
        ("R3 all named passes", r3_all_named_passes),
        ("R4 boundary rules", r4_boundary_rules_each_pinned),
        ("R5 quiet + report", r5_quiet_and_report_contract),
    ]
    real_digest_before = owners_digest(OWNERS)

    print("=" * 79)
    print("ADR-12 COMPLIANCE GATE -- OWNER READINESS WITNESS")
    print("=" * 79)
    failures = 0
    for name, fn in assertions:
        try:
            detail = fn()
            print("  [ok] %-24s %s" % (name, detail))
        except AssertionFailed as exc:
            failures += 1
            print("  [FAIL] %s" % name)
            for line in str(exc).splitlines():
                print("         %s" % line)
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print("  [FAIL] %s (unexpected: %r)" % (name, exc))

    # The repository must be exactly as it was found. The blocker stays open
    # until the R&D side names the owners; nothing in this witness may close it
    # by writing an attribution into the real table.
    if owners_digest(OWNERS) != real_digest_before:
        failures += 1
        print("  [FAIL] the real owners.csv was modified by this witness")

    print("=" * 79)
    print("[OWNER-READINESS-SELFTEST] %s  assertions=%d  failed=%d"
          % ("PASS" if failures == 0 else "FAIL", len(assertions), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())