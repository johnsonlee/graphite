#!/usr/bin/env python3
"""Correctness tests for release archive, formula, and Docker input validation."""
import hashlib
import io
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest

import release
import stage_docker


class ReleaseTests(unittest.TestCase):
    def test_archives_preserve_bytes_modes_and_have_reproducible_metadata(self):
        content = {"graphite-server": (b"native", 0o755), "licenses/example": (b"notice", 0o644)}
        first = release.tar_bytes(content)
        self.assertEqual(first, release.tar_bytes(dict(reversed(list(content.items())))))
        with tarfile.open(fileobj=io.BytesIO(first), mode="r:gz") as archive:
            binary = archive.getmember("graphite-server")
            self.assertEqual((binary.mode, binary.uid, binary.gid, binary.mtime), (0o755, 0, 0, 0))
            self.assertEqual(archive.extractfile(binary).read(), b"native")
            self.assertEqual(archive.extractfile("licenses/example").read(), b"notice")

    def test_formula_carries_all_exact_platform_hashes_and_both_runtimes(self):
        hashes = {"graphite.jar": "a"*64}
        for index, target in enumerate(release.TARGETS):
            hashes[f"graphite-server-1.2.3-{target}.tar.gz"] = str(index)*64
        rendered = release.formula("1.2.3", hashes, "exec @NATIVE@ \"$@\"\n")
        for name, value in hashes.items():
            self.assertIn("/v1.2.3/"+name, rendered)
            self.assertIn('sha256 "'+value+'"', rendered)
        self.assertEqual(rendered.count('resource "native-server"'), 4)
        self.assertIn('depends_on "openjdk@17"', rendered)
        self.assertIn('libexec.install "graphite.jar"', rendered)
        self.assertIn('libexec.install "graphite-server"', rendered)
        self.assertIn("shellescape", rendered)

    def make_assets(self, root):
        files = {}
        for arch, machine in (("amd64", 62), ("arm64", 183)):
            header = bytearray(64); header[:6] = b"\x7fELF\x02\x01"; struct.pack_into("<H", header, 18, machine)
            files[arch] = {"graphite-server": (bytes(header), 0o755), "VERSION": (b"test\n", 0o644), "LICENSE": (b"notice", 0o644)}
        self.write_assets(root, files)
        return files

    def write_assets(self, root, files):
        manifest = []
        for arch, content in files.items():
            filename = f"graphite-server-test-linux-{arch}.tar.gz"
            data = release.tar_bytes(content); (root/filename).write_bytes(data)
            manifest.append(f"{hashlib.sha256(data).hexdigest()}  {filename}\n")
        (root/"SHA256SUMS").write_text("".join(manifest))

    def test_docker_stages_verified_platform_bytes_and_nonroot_directories(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); files = self.make_assets(root); output=root/"stage"
            stage_docker.stage(root, "test", output)
            for arch in files:
                binary = output/f"linux-{arch}/graphite-server"
                self.assertEqual(binary.read_bytes(), files[arch]["graphite-server"][0])
                self.assertEqual(binary.stat().st_mode & 0o777, 0o755)
            self.assertEqual((output/"rootfs/etc/passwd").read_text(), "graphite:x:1000:1000:Graphite:/home/graphite:/sbin/nologin\n")
            self.assertTrue((output/"writable/app").is_dir())
            self.assertTrue((output/"writable/data").is_dir())
            self.assertFalse((output/"app/graphite.jar").exists())

    def test_docker_rejects_bad_checksum_wrong_arch_version_and_unsafe_paths(self):
        for defect in ("checksum", "architecture", "version", "path", "duplicate"):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp); files=self.make_assets(root)
                if defect == "architecture": files["arm64"]["graphite-server"] = files["amd64"]["graphite-server"]
                if defect == "version": files["amd64"]["VERSION"] = (b"other\n", 0o644)
                if defect == "path": files["amd64"]["../escape"] = (b"bad", 0o644)
                self.write_assets(root, files)
                if defect == "checksum": (root/"graphite-server-test-linux-amd64.tar.gz").write_bytes(b"corrupt")
                if defect == "duplicate":
                    path=root/"SHA256SUMS"; path.write_text(path.read_text()*2)
                with self.assertRaises(ValueError): stage_docker.stage(root, "test", root/"stage")
                self.assertFalse((root/"stage").exists())
                self.assertFalse((root/"escape").exists())


if __name__ == "__main__": unittest.main()
