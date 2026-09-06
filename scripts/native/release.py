#!/usr/bin/env python3
"""Prepare local release archives/formula/Docker context. Never uploads artifacts."""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import zipfile

TARGETS = ("darwin-amd64", "darwin-arm64", "linux-amd64", "linux-arm64")


def digest(path):
    with path.open("rb") as file:
        value = hashlib.sha256()
        for block in iter(lambda: file.read(1024 * 1024), b""): value.update(block)
        return value.hexdigest()


def module_licenses(repo):
    # Collect modules used by the actual server, excluding dependency test-only
    # modules. This is the same CGo-free dependency graph as the native build.
    data = ""
    for target in TARGETS:
        goos, goarch = target.split("-")
        data += subprocess.check_output(["go", "list", "-deps", "-json", "./cmd/graphite-server"],
                                        cwd=repo / "graphite-server", text=True,
                                        env=dict(os.environ, CGO_ENABLED="0", GOFLAGS="", GOOS=goos, GOARCH=goarch))
    decoder = json.JSONDecoder()
    values = []
    while data.strip():
        value, end = decoder.raw_decode(data.lstrip())
        values.append(value)
        data = data.lstrip()[end:]
    result = {}
    modules = {value["Module"]["Path"]: value["Module"] for value in values if "Module" in value}
    for module in modules.values():
        if module.get("Main"): continue
        path = Path(module["Dir"])
        name = module["Path"].replace("/", "_") + "@" + module["Version"]
        license_files = sorted(p for p in path.iterdir() if p.is_file() and (p.name.startswith("LICENSE") or p.name.startswith("NOTICE") or p.name.startswith("COPYING")))
        if not license_files: raise ValueError("No license found for " + module["Path"])
        for file in license_files: result["licenses/" + name + "/" + file.name] = file.read_bytes()
    goroot = Path(subprocess.check_output(["go", "env", "GOROOT"], text=True).strip())
    go_license = goroot / "LICENSE"
    if not go_license.is_file() and goroot.name == "libexec":
        go_license = goroot.parent / "LICENSE" # Homebrew installs its license beside libexec.
    result["licenses/Go-LICENSE"] = go_license.read_bytes()
    # Keep the notices in all fdlibm-derived files, verbatim and with attribution.
    for file in sorted((repo / "graphite-server/internal/javamath").glob("*.go")):
        lines = file.read_text().splitlines()
        notices = []
        for line in lines:
            if line.startswith("import "): break
            if line.startswith("//"): notices.append(line[2:].lstrip())
        if any("Copyright" in line for line in notices):
            result["licenses/fdlibm-" + file.stem + ".txt"] = ("\n".join(notices)+"\n").encode()
    return result


def tar_bytes(files):
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT) as archive:
            for name, (content, mode) in sorted(files.items()):
                info = tarfile.TarInfo(name)
                info.size, info.mode, info.mtime = len(content), mode, 0
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                archive.addfile(info, io.BytesIO(content))
    return buffer.getvalue()


def formula(version, hashes, launcher):
    url = "https://github.com/johnsonlee/graphite/releases/download/v" + version + "/"
    lines = ["require \"shellwords\"", "", "class Graphite < Formula",
             '  desc "Build, query, and serve Graphite static analysis graphs"',
             '  homepage "https://github.com/johnsonlee/graphite"',
             f'  url "{url}graphite.jar"', f'  version "{version}"',
             f'  sha256 "{hashes["graphite.jar"]}"', '  license "Apache-2.0"',
             '', '  depends_on "openjdk@17"', '']
    for os_name, brew_os in (("darwin", "macos"), ("linux", "linux")):
        lines.append(f"  on_{brew_os} do")
        for arch, brew_arch in (("amd64", "intel"), ("arm64", "arm")):
            name = f"graphite-server-{version}-{os_name}-{arch}.tar.gz"
            lines += [f"    on_{brew_arch} do", '      resource "native-server" do',
                      f'        url "{url}{name}"', f'        sha256 "{hashes[name]}"',
                      "      end", "    end"]
        lines += ["  end", ""]
    lines += ['  def install', '    libexec.install "graphite.jar"',
              '    resource("native-server").stage do', '      libexec.install "graphite-server"',
              '      (libexec/"native-licenses").install "LICENSE", "licenses", "VERSION"', '    end',
              "    launcher = <<~'SH'"]
    lines += ["      " + line for line in launcher.splitlines()]
    lines += ["    SH", '    launcher = launcher.gsub("@JAVA@", "#{formula_opt_bin("openjdk@17")}/java".shellescape)',
              '                       .gsub("@JAR@", (libexec/"graphite.jar").to_s.shellescape)',
              '                       .gsub("@NATIVE@", (libexec/"graphite-server").to_s.shellescape)',
              '    (bin/"graphite").write launcher', '  end', '', '  test do',
              '    assert_match "Usage", shell_output("#{bin}/graphite --help")',
              '    assert_match "Usage", shell_output("#{bin}/graphite serve --help")',
              '  end', 'end', '']
    return "\n".join(lines)


def build_release(repo, resources, output, version, jars):
    if not re.fullmatch(r"[A-Za-z0-9._+-]+", version): raise ValueError("invalid version")
    if (resources / "VERSION").read_text() != version + "\n": raise ValueError("resource version mismatch")
    expected = "".join(f"{digest(resources/target/'graphite-server')}  {target}/graphite-server\n" for target in TARGETS)
    if (resources / "SHA256SUMS").read_text() != expected: raise ValueError("resource SHA256/target set mismatch")
    # Reuse the same stricter architecture/path validation as Gradle; copying is
    # confined to a generated output directory and never mutates the input.
    for jar in jars.values():
        with zipfile.ZipFile(jar) as archive:
            native_names = [name for name in archive.namelist() if name.startswith("graphite-native/") and not name.endswith("/")]
            wanted = ["graphite-native/SHA256SUMS", "graphite-native/VERSION"] + ["graphite-native/"+target+"/graphite-server" for target in TARGETS]
            if sorted(native_names) != sorted(wanted): raise ValueError("JAR native resource path set mismatch")
            if archive.read("graphite-native/VERSION") != (version+"\n").encode(): raise ValueError("JAR version mismatch")
            if archive.read("graphite-native/SHA256SUMS").decode() != expected: raise ValueError("JAR native manifest mismatch")
            for target in TARGETS:
                if hashlib.sha256(archive.read("graphite-native/"+target+"/graphite-server")).hexdigest() != digest(resources/target/"graphite-server"):
                    raise ValueError("JAR binary mismatch: " + target)
    if output.exists() and any(output.iterdir()): raise ValueError("release output directory must be empty")
    output.mkdir(parents=True, exist_ok=True)
    verified = output / "verified" / "graphite-native"
    subprocess.run(["go", "run", "scripts/native/build.go", "--version", version,
                    "--prebuilt", str(resources), "--output", str(verified)], cwd=repo, check=True)
    licenses = module_licenses(repo)
    common = {name: (data, 0o644) for name, data in licenses.items()}
    common["LICENSE"] = ((repo / "LICENSE").read_bytes(), 0o644)
    common["VERSION"] = ((version + "\n").encode(), 0o644)
    common["README.md"] = (b"Graphite native server. Run ./graphite-server serve --help.\nGraph building and offline query require the separately distributed graphite.jar and Java 17.\n", 0o644)
    archive_names = []
    for target in TARGETS:
        files = dict(common)
        files["graphite-server"] = ((verified / target / "graphite-server").read_bytes(), 0o755)
        filename = f"graphite-server-{version}-{target}.tar.gz"
        (output / filename).write_bytes(tar_bytes(files)); archive_names.append(filename)
    for name, jar in jars.items(): shutil.copyfile(jar, output / name)
    names = archive_names + list(jars)
    hashes = {name: digest(output/name) for name in sorted(names)}
    (output / "SHA256SUMS").write_text("".join(f"{value}  {name}\n" for name, value in hashes.items()))
    (output / "graphite.rb").write_text(formula(version, hashes, (repo / "scripts/native/graphite-launcher.sh.in").read_text()))
    return hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--query-jar", type=Path, required=True)
    parser.add_argument("--explore-jar", type=Path, required=True)
    args = parser.parse_args()
    hashes = build_release(args.repo.resolve(), args.resources.resolve(), args.output.resolve(), args.version,
                           {"graphite.jar": args.query_jar.resolve(), "graphite-explore.jar": args.explore_jar.resolve()})
    print(json.dumps(hashes, indent=2))


if __name__ == "__main__": main()
