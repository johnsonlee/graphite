#!/usr/bin/env python3
"""Parse a release tag as strict SemVer and print the outputs the publish workflow needs.

    release-version.py v3.0.0-rc.1      -> version=3.0.0-rc.1 / prerelease=true
    release-version.py --self-test      -> exercises the parser, exits non-zero on a defect

The tag must be `v` followed by a SemVer 2.0.0 version: MAJOR.MINOR.PATCH, an optional
pre-release (`-rc.1`), an optional build (`+build.5`). Anything else (`v`, `vfoo`,
`vv3.0.0`, `v3.0`, `v03.0.0`) is refused before any publishing job starts, so a malformed
tag can neither publish partial artifacts nor move the `latest` channels. Pre-release is
decided by the parsed pre-release part, not by a `-` anywhere in the tag: `v3.0.0+linux-1`
is a stable release with build metadata.
"""

import re
import sys

SEMVER = re.compile(
    r"^v(?P<major>0|[1-9]\d*)\.(?P<minor>0|[1-9]\d*)\.(?P<patch>0|[1-9]\d*)"
    r"(?:-(?P<prerelease>(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+(?P<build>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


def parse(tag):
    """The (version, prerelease) of a `v`-prefixed SemVer tag, or None."""
    m = SEMVER.match(tag)
    if not m:
        return None
    return tag[1:], m.group("prerelease") is not None


def self_test():
    ok = {
        "v3.0.0": ("3.0.0", False),
        "v3.0.0-alpha.1": ("3.0.0-alpha.1", True),
        "v3.0.0-rc.1+build.5": ("3.0.0-rc.1+build.5", True),
        "v3.0.0+linux-1": ("3.0.0+linux-1", False),
        "v0.0.1": ("0.0.1", False),
        "v10.20.30-0.3.7": ("10.20.30-0.3.7", True),
    }
    bad = ["", "v", "vfoo", "vv3.0.0", "3.0.0", "v3.0", "v03.0.0", "v3.0.0-", "v3.0.0-01",
           "v3.0.0+", "v3.0.0-rc.1 ", "V3.0.0", "v3.0.0.1"]
    for tag, expected in ok.items():
        assert parse(tag) == expected, (tag, parse(tag))
    for tag in bad:
        assert parse(tag) is None, tag
    print(f"release-version: {len(ok)} accepted, {len(bad)} refused")


def main(argv):
    if argv == ["--self-test"]:
        self_test()
        return 0
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    parsed = parse(argv[0])
    if parsed is None:
        print(f"RELEASE_TAG must be v<MAJOR>.<MINOR>.<PATCH>[-<prerelease>][+<build>], got '{argv[0]}'",
              file=sys.stderr)
        return 1
    version, prerelease = parsed
    print(f"version={version}")
    print(f"prerelease={'true' if prerelease else 'false'}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
