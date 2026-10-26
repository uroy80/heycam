#!/usr/bin/env python3
"""Checks the results of a device-test run: nothing failed, and each module ran at least the tests it has.

Gradle reports success when a test APK crashes at startup and runs nothing. So in every module with device tests,
connected<Debug|Release>AndroidTest ends with this check for that module (build-logic registers it as
check<Debug|Release>DeviceTestCount), and the whole run can be checked again from the repository root:

  ./gradlew connectedDebugAndroidTest                                        each module checks itself
  python3 -I scripts/check-test-counts.py debug                              every module: 18
  ./gradlew -Pheycam.testBuildType=release :app:connectedReleaseAndroidTest  checks app
  python3 -I scripts/check-test-counts.py release                            app 14, on the R8 build

It reads <module>/build/outputs/androidTest-results/connected/<debug|release>/**/TEST-*.xml under --root (default:
the current directory). --module checks one module, which must then have run at least one test. Raise a minimum
below when a module gains device tests.
"""

import argparse
import glob
import os
import sys
import time
import xml.etree.ElementTree as ElementTree

MINIMUM = {
    "debug": {"app": 14, "core/data": 1, "core/ocr": 2, "core/imaging": 1},
    "release": {"app": 14},
}
RESULTS = "build/outputs/androidTest-results/connected"


def results(root, build_type):
    """{module: [result files]} for modules one or two levels down (app, core/data), never .work/ or build/."""
    found = {}
    for depth in ("*", "*/*"):
        pattern = os.path.join(root, depth, RESULTS, build_type, "**", "TEST-*.xml")
        for path in glob.glob(pattern, recursive=True):
            module = os.path.relpath(path, root).replace(os.sep, "/").split(f"/{RESULTS}/", 1)[0]
            found.setdefault(module, []).append(path)
    return found


def count(paths):
    """(tests, failed, skipped, newest file time) over every testcase in the files."""
    tests = failed = skipped = 0
    for path in paths:
        for case in ElementTree.parse(path).getroot().iter("testcase"):
            tests += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failed += 1
            elif case.find("skipped") is not None:
                skipped += 1
    return tests, failed, skipped, max(os.path.getmtime(path) for path in paths)


def check(root, build_type, module=None):
    """Prints a table and returns what is wrong with the run (nothing when it passed) and how many tests ran."""
    found = results(root, build_type)
    minimum = MINIMUM[build_type]
    modules = [module] if module else sorted(set(found) | set(minimum))
    problems = []
    ran = 0
    print(f"{'module':<20} {'tests':>5} {'failed':>6} {'skipped':>7}  results from")
    for name in modules:
        if name not in found:
            print(f"{name:<20} {'-':>5}")
            problems.append(f"{name}: no results for {build_type}")
            continue
        tests, failed, skipped, newest = count(found[name])
        ran += tests - skipped
        when = time.strftime("%Y-%m-%d %H:%M", time.localtime(newest))
        print(f"{name:<20} {tests:>5} {failed:>6} {skipped:>7}  {when}")
        expected = minimum.get(name, 1 if module else 0)
        if failed:
            problems.append(f"{name}: {failed} failed")
        if tests - skipped < expected:
            problems.append(f"{name}: {tests - skipped} tests ran, at least {expected} expected")
    return problems, ran


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("build_type", choices=sorted(MINIMUM), help="the build type the device tests ran against")
    parser.add_argument("--module", help="check only this module, as a path from the root (app, core/data)")
    parser.add_argument("--root", default=".", help="the repository root (default: the current directory)")
    args = parser.parse_args(argv)

    problems, ran = check(args.root, args.build_type, args.module)
    if problems:
        print("\n".join(["", "Device tests did not pass:"] + [f"  {problem}" for problem in problems]))
        return 1
    scope = args.module or "every module at or above its minimum"
    print(f"\nDevice tests ({args.build_type}): {ran} passed, {scope}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
