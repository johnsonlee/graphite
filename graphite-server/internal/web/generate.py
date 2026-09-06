#!/usr/bin/env python3
"""Synchronize Explorer assets exactly; the JVM module is the source of truth."""
import argparse
from pathlib import Path
import shutil


def files(directory):
    return {path.relative_to(directory): path for path in directory.rglob('*') if path.is_file()}


def differences(source, target):
    expected, actual = files(source), files(target)
    return sorted(str(name) for name in expected.keys() | actual.keys()
                  if name not in expected or name not in actual
                  or expected[name].read_bytes() != actual[name].read_bytes())


def synchronize(source, target):
    if not source.is_dir():
        raise FileNotFoundError(f'Explorer asset source is missing: {source}')
    expected = files(source)
    target.mkdir(parents=True, exist_ok=True)
    # Remove deleted/renamed outputs before creating paths, including a previous
    # file replaced by a directory or a previous directory replaced by a file.
    for name, path in files(target).items():
        if name not in expected:
            path.unlink()
    for path in sorted((p for p in target.rglob('*') if p.is_dir()),
                       key=lambda p: len(p.parts), reverse=True):
        if not any(path.iterdir()):
            path.rmdir()
    for name, path in expected.items():
        output = target / name
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Report drift without modifying generated assets')
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    source = here.parents[2] / 'graphite-explore/src/main/resources/web'
    target = here / 'assets'
    if not source.is_dir():
        parser.error(f'Explorer asset source is missing: {source}')
    if args.check:
        changed = differences(source, target)
        if changed:
            parser.exit(1, 'Explorer assets differ: ' + ', '.join(changed) + '\n')
    else:
        synchronize(source, target)


if __name__ == '__main__':
    main()
