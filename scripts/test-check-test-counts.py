#!/usr/bin/env python3
"""Checks scripts/check-test-counts.py against made-up device-test results. Run by CI: python3 -I <this file>."""

import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    "check_test_counts", os.path.join(os.path.dirname(os.path.abspath(__file__)), "check-test-counts.py")
)
counts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(counts)


class CheckTestCountsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)

    def write(self, module, build_type, passed=0, failed=0, skipped=0, name="TEST-device.xml"):
        """A JUnit XML file as AGP writes it for a connected run."""
        directory = os.path.join(self.root.name, module, counts.RESULTS, build_type, "emulator-5554")
        os.makedirs(directory, exist_ok=True)
        cases = (
            ["<testcase name='ok' />"] * passed
            + ["<testcase name='bad'><failure>boom</failure></testcase>"] * failed
            + ["<testcase name='later'><skipped /></testcase>"] * skipped
        )
        with open(os.path.join(directory, name), "w", encoding="utf-8") as out:
            out.write(f"<testsuite tests='{len(cases)}'>{''.join(cases)}</testsuite>")

    def run_check(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            status = counts.main([*args, "--root", self.root.name])
        return status, printed.getvalue()

    def full_debug_run(self):
        self.write("app", "debug", passed=14)
        self.write("core/data", "debug", passed=1)
        self.write("core/ocr", "debug", passed=2)
        self.write("core/imaging", "debug", passed=1)
        self.write("core/pdf", "debug")  # modules without device tests report an empty suite

    def test_a_full_run_passes(self):
        self.full_debug_run()
        status, printed = self.run_check("debug")
        self.assertEqual(status, 0, printed)
        self.assertIn("18 passed", printed)

    def test_an_app_that_crashed_at_startup_fails_although_gradle_passed(self):
        self.full_debug_run()
        self.write("app", "debug", passed=0)
        status, printed = self.run_check("debug")
        self.assertEqual(status, 1)
        self.assertIn("app: 0 tests ran, at least 14 expected", printed)

    def test_failures_and_skips_count_against_the_minimum(self):
        self.full_debug_run()
        self.write("core/ocr", "debug", passed=1, failed=1)
        self.write("core/data", "debug", skipped=1)
        status, printed = self.run_check("debug")
        self.assertEqual(status, 1)
        self.assertIn("core/ocr: 1 failed", printed)
        self.assertIn("core/data: 0 tests ran, at least 1 expected", printed)

    def test_results_split_over_several_files_add_up(self):
        self.write("app", "release", passed=10, name="TEST-a.xml")
        self.write("app", "release", passed=4, name="TEST-b.xml")
        self.assertEqual(self.run_check("release")[0], 0)

    def test_missing_results_fail(self):
        status, printed = self.run_check("release")
        self.assertEqual(status, 1)
        self.assertIn("app: no results for release", printed)

    def test_one_module_needs_at_least_one_test_even_when_not_listed(self):
        self.full_debug_run()
        self.assertEqual(self.run_check("debug", "--module", "core/ocr")[0], 0)
        self.assertEqual(self.run_check("debug", "--module", "core/pdf")[0], 1)
        self.write("feature/scan", "debug", passed=3)
        self.assertEqual(self.run_check("debug", "--module", "feature/scan")[0], 0)

    def test_one_module_ignores_the_others(self):
        self.write("core/data", "debug", passed=1)
        status, printed = self.run_check("debug", "--module", "core/data")
        self.assertEqual(status, 0, printed)
        self.assertNotIn("app", printed)


if __name__ == "__main__":
    result = unittest.main(exit=False, verbosity=0).result
    sys.exit(0 if result.wasSuccessful() else 1)
