#!/usr/bin/env python3
"""Summarise Gradle JUnit XML results as Markdown: tests that FAILED and tests that were FLAKY
(failed, then passed on the retry from .github/ci-test-retry.gradle, which merges reruns into one
<testcase>: a <flakyFailure> child means it passed in the end, a <failure> child means it never did).

Usage: junit-summary.py <results-dir>
Prints Markdown to stdout. When GITHUB_OUTPUT is set, also writes total=, failed= and flaky= to it.
Always exits 0: the Gradle step, not this summary, decides whether the build failed.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

LIST_LIMIT = 50


def main(results_dir):
    total = 0
    failed = []
    flaky = []
    for path in sorted(glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True)):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            if case.find("skipped") is not None:
                continue
            total += 1
            name = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(name)
            elif case.find("flakyFailure") is not None or case.find("flakyError") is not None:
                flaky.append(name)

    lines = ["### Unit tests", ""]
    if total == 0:
        lines.append("No test results found (the build stopped before the tests ran).")
    else:
        lines.append("%d run, %d failed, %d flaky (passed only on retry)." % (total, len(failed), len(flaky)))
    for title, names in (("FAILED", failed), ("FLAKY (passed on retry; not blocking)", flaky)):
        if not names:
            continue
        lines += ["", "**%s**" % title, ""]
        lines += ["- `%s`" % n for n in names[:LIST_LIMIT]]
        if len(names) > LIST_LIMIT:
            lines.append("- ... and %d more" % (len(names) - LIST_LIMIT))
    print("\n".join(lines))

    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as f:
            f.write("total=%d\nfailed=%d\nflaky=%d\n" % (total, len(failed), len(flaky)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results")
