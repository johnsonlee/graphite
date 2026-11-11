#!/usr/bin/env python3
"""Fetch SHA256-pinned evidence into an external cache; never modify the checkout."""
import argparse
import hashlib
import json
import tempfile
import urllib.request
from pathlib import Path


def verify(path, row):
    if path.stat().st_size != row['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != row['sha256']:
        raise ValueError(f"Evidence size/SHA256 mismatch: {path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache-dir', type=Path, default=Path.home() / '.cache/graphite/evidence/pr212-2026-11-11')
    parser.add_argument('--verify-only', action='store_true', help='Check existing cache files without network access')
    args = parser.parse_args()
    catalog = json.loads(Path(__file__).with_name('assets.json').read_text())
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    for row in catalog['assets']:
        name = row['name']
        if Path(name).name != name or name in {'.', '..'}:
            raise ValueError(f'Invalid asset name: {name}')
        target = args.cache_dir / name
        if target.exists() or args.verify_only:
            verify(target, row)
        else:
            url = f"https://github.com/{catalog['repository']}/releases/download/{catalog['releaseTag']}/{name}"
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(dir=args.cache_dir, delete=False) as output:
                    temporary = Path(output.name)
                    with urllib.request.urlopen(url, timeout=60) as response:
                        while chunk := response.read(1024 * 1024):
                            output.write(chunk)
                verify(temporary, row)
                temporary.replace(target)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        print(f"Verified {row['sha256']}  {target}", flush=True)


if __name__ == '__main__':
    main()
