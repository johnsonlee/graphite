#!/usr/bin/env python3
"""Embed byte-identical Explorer assets; the JVM module is the source of truth."""
from pathlib import Path
import shutil

here = Path(__file__).resolve().parent
source = here.parents[2] / "graphite-explore/src/main/resources/web"
target = here / "assets"
target.mkdir(exist_ok=True)
for path in sorted(source.iterdir()):
    if path.is_file():
        shutil.copyfile(path, target / path.name)
