"""The effective heap of every JVM a replay started, from the logs _JAVA_OPTIONS asked for.

    heap.py <log-dir>

replay.sh appends `-Xmx<n> -Xlog:gc+init=info:file=<log-dir>/jvm-%p-%t.log` to _JAVA_OPTIONS,
which every JVM reads last (java, javac, jar, jshell and its remote VM, the frontend a
`graphite build` starts, the CLI's probes), so each writes the `Heap Max Capacity` it actually
got after every heap option it inherited or was given. Prints one line per JVM and a summary;
exit 1 when no JVM logged, when a log has no capacity, or when any capacity exceeds 8 GiB.
"""
import os
import re
import sys

CEILING = 8 * 1024 ** 3
UNITS = {"B": 1, "K": 1024, "M": 1024 ** 2, "G": 1024 ** 3, "T": 1024 ** 4}
CAPACITY = re.compile(r"Heap Max Capacity: (\d+)([BKMGT])\b")


def capacity(text):
    """The heap ceiling in bytes a gc+init log names, or None."""
    match = CAPACITY.search(text)
    return int(match.group(1)) * UNITS[match.group(2)] if match else None


def main(argv):
    if len(argv) != 2:
        raise SystemExit(__doc__)
    names = sorted(n for n in os.listdir(argv[1]) if n.endswith(".log"))
    print("# Effective JVM heap of every JVM that read the replay's _JAVA_OPTIONS")
    print("# (-Xlog:gc+init; ceiling 8 GiB = %d bytes)" % CEILING)
    failures, seen = [], {}
    for name in names:
        with open(os.path.join(argv[1], name), errors="replace") as f:
            text = f.read()
        size = capacity(text)
        label = re.sub(r"-\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d", "", name)
        if size is None:
            failures.append("%s logged no Heap Max Capacity" % label)
            print("%s: no Heap Max Capacity" % label)
            continue
        print("%s: Heap Max Capacity %d bytes" % (label, size))
        seen[size] = seen.get(size, 0) + 1
        if size > CEILING:
            failures.append("%s ran with %d bytes" % (label, size))
    if not names:
        failures.append("no JVM logged its heap")
    summary = ", ".join("%d x %d bytes" % (n, s) for s, n in sorted(seen.items()))
    print("== %d JVMs: %s" % (len(names), summary or "none"))
    for failure in failures:
        print("FAIL: " + failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
