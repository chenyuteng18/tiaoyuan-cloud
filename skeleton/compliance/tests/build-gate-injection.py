#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADR-12 build-gate end-to-end injection.

Proves the property the task actually cares about: with a real hit present in the
client package, `mvn package` itself fails. Asserting the scanner's exit code is
not enough -- the scanner could be correct and the build could still ignore it.
This script therefore drives the real build.

It also asserts the second half of "a hit fails the build": the failure must
happen at
the validate phase, i.e. BEFORE any artefact is produced. A gate that lets the
bundle be packaged and then complains has already failed its purpose.

Usage
-----
  python compliance/tests/build-gate-injection.py [--mvn <path to mvn>]
Exit code 0 = the build failed on the injected hit, at validate, and the tree
returned to green afterwards.
"""

from __future__ import annotations

import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
COMPLIANCE_DIR = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(COMPLIANCE_DIR)
sys.path.insert(0, HERE)
from compliance_injection_test import INJECTIONS  # noqa: E402

DEFAULT_MVN = "mvn"

# The injected sample for scan face 1 -- the analytics event name, the channel
# that survives a UI-only review.
SAMPLE = INJECTIONS["scan1_refund"]


def find_mvn(explicit):
    if explicit:
        return explicit
    candidates = [
        os.path.join(os.path.dirname(REPO_ROOT), ".tools",
                     "apache-maven-3.9.9", "bin", "mvn.cmd"),
        os.path.join(os.path.dirname(os.path.dirname(REPO_ROOT)),
                     "2026-09-16-10-37-59", ".tools",
                     "apache-maven-3.9.9", "bin", "mvn.cmd"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return DEFAULT_MVN


def run_build(mvn, *goals):
    """
    Run maven with the given goals.

    Goals are passed as separate argv entries. Passing "clean package" as one
    string would hand Maven a single unrecognised goal, which fails with an
    unrelated error and would make this test report a false green.
    """
    env = dict(os.environ)
    env["MAVEN_OPTS"] = "-Dfile.encoding=UTF-8"
    env.pop("DY_COMPLIANCE_ALLOW_MISSING_ROOT", None)
    proc = subprocess.run(
        [mvn, "-f", os.path.join(REPO_ROOT, "pom.xml"), "-B", "-ntp"] + list(goals),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
        cwd=REPO_ROOT,
    )
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def main(argv):
    mvn = None
    if "--mvn" in argv:
        mvn = argv[argv.index("--mvn") + 1]
    mvn = find_mvn(mvn)
    print("maven: %s" % mvn)

    target = os.path.join(REPO_ROOT, SAMPLE["path"])
    if os.path.exists(target):
        print("[FAIL] injection target already exists: %s" % target)
        return 1

    # Remove any artefact left by an earlier successful build first. `clean` runs
    # per-module as the reactor reaches each module, so once the root module's
    # validate phase fails, no module is ever reached and no module is cleaned
    # -- a stale jar from a previous run would survive and make the "nothing
    # shipped" assertion below meaningless.
    jar = os.path.join(REPO_ROOT, "dy-app", "target",
                       "dy-app-0.0.1-SNAPSHOT.jar")
    if os.path.isfile(jar):
        os.remove(jar)
        print("removed stale artefact from a previous build before testing")
    jar_pre_existing = os.path.isfile(jar)

    failures = []
    try:
        parent = os.path.dirname(target)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent)
        with io.open(target, "w", encoding="utf-8", newline="") as handle:
            handle.write(SAMPLE["content"])
        print("injected: %s" % SAMPLE["path"])

        code, out = run_build(mvn, "clean", "package")
        if code == 0:
            failures.append("BUILD SUCCESS with a violation present -- the gate "
                            "is not load-bearing")
        else:
            print("build failed as required (exit %d)" % code)

        # the gate must be what failed it, not an unrelated error
        if "ADR-12 BUILD-TIME COMPLIANCE SCAN" not in out:
            failures.append("the compliance gate did not run in the build")
        else:
            print("compliance gate ran inside the build")
        if "[COMPLIANCE-GATE] FAIL" not in out:
            failures.append("the compliance gate did not report FAIL")
        else:
            print("gate reported FAIL")
        if "scan1_refund" not in out:
            failures.append("the failure was not attributed to scan face 1")
        else:
            print("failure attributed to scan face 1")

        # failure must land at validate, before any artefact is produced
        validate_pos = out.find("adr12-compliance-scan")
        compile_pos = out.find("maven-compiler-plugin")
        package_pos = out.find("maven-jar-plugin")
        print("phase positions: validate=%d compile=%d package=%d"
              % (validate_pos, compile_pos, package_pos))
        if validate_pos < 0:
            failures.append("the gate did not run at the validate phase")
        if package_pos > 0:
            failures.append(
                "an artefact was packaged before the gate failed -- the gate "
                "must run at validate, not after packaging")
        else:
            print("no artefact packaged: the gate failed before packaging")

        # The build was driven as `clean package`, and the artefact was removed before
        # the run, so a jar on disk now would prove the gate ran too late. This is
        # the hard form of "a hit fails the build": not merely "it complained", but
        # "nothing new was produced".
        jar_present = os.path.isfile(jar)
        print("dy-app jar present after failed build: %s (pre-existing: %s)"
              % (jar_present, jar_pre_existing))
        if jar_present:
            failures.append(
                "a deployable artefact was produced despite the gate failing: "
                "%s -- the gate must block before packaging" % jar)
        else:
            print("no deployable artefact produced: the gate blocked the build")
    finally:
        if os.path.exists(target):
            os.remove(target)

    code_after, out_after = run_build(mvn, "validate")
    if code_after != 0:
        failures.append("tree did not return to green (exit %d)\n%s"
                        % (code_after, out_after[-2000:]))
    else:
        print("tree restored to green: validate exit 0")

    print("=" * 79)
    if failures:
        for item in failures:
            print("  [FAIL] %s" % item)
        print("[BUILD-GATE-INJECTION] FAIL")
        return 1
    print("[BUILD-GATE-INJECTION] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))