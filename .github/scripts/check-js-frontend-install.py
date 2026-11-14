#!/usr/bin/env python3
"""Install exact JS release assets through real curl, then build and read a saved graph."""
import argparse
from filecmp import cmp
from functools import partial
from http.server import HTTPServer, SimpleHTTPRequestHandler
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
from threading import Thread

SELECTORS = ("js", "ts", "es")


def check(root, cli, assets, version, target, source_frontend):
    # Only curl's destination and trust anchor change. The production URL, tag,
    # asset names, transfer, checksum verification and installation run unchanged.
    downloads = root / "mirror" / "johnsonlee/graphite/releases/download" / f"v{version}"
    downloads.mkdir(parents=True)
    asset = downloads / f"graphite-frontend-js-{target}"
    checksum = downloads / (asset.name + ".sha256")
    shutil.copyfile(assets / asset.name, asset)
    shutil.copyfile(assets / checksum.name, checksum)
    cert, key = root / "cert.pem", root / "key.pem"
    # macOS system LibreSSL and Linux OpenSSL both accept config-file extensions.
    config = root / "openssl.cnf"
    config.write_text("[req]\nprompt=no\ndistinguished_name=dn\nx509_extensions=cert\n"
                      "[dn]\nCN=github.com\n[cert]\nsubjectAltName=DNS:github.com\n")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-config", config,
                    "-keyout", key, "-out", cert], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)

    work = root / "work"
    work.mkdir()
    (work / "app.mjs").write_text('console.log("installed");\n[1].map(x => x + 1);\n')
    # Bun preloads normally execute before the application's own entry point.
    # Reading an artifact from this project must never run that code.
    (work / "bunfig.toml").write_text('preload = ["./preload.cjs"]\n')
    (work / "preload.cjs").write_text('require("node:fs").writeFileSync("preload-ran", "yes");\n')
    binary = work / "graphite"
    shutil.copy2(cli, binary)
    install_tools = root / "install-tools"
    install_tools.mkdir()
    curl = shutil.which("curl")
    if not curl:
        raise RuntimeError("The installation check requires curl")
    (install_tools / "curl").symlink_to(curl)
    runtime = root / "runtime"
    runtime.mkdir()

    def run(home, *args, path=runtime, success=True, extra_env=None):
        env = {"HOME": str(home), "PATH": str(path), **(extra_env or {})}
        result = subprocess.run([binary, *args], cwd=work, env=env,
                                capture_output=True, text=True, timeout=60)
        if (result.returncode == 0) != success:
            raise AssertionError(f"{args} exited {result.returncode}\n{result.stdout}\n{result.stderr}")
        return result

    handler = partial(SimpleHTTPRequestHandler, directory=root / "mirror")
    with HTTPServer(("127.0.0.1", 0), handler) as server:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for selector in SELECTORS:
                home = root / selector
                home.mkdir()
                (home / ".curlrc").write_text(
                    f'connect-to = "::127.0.0.1:{server.server_port}"\ncacert = "{cert}"\n')
                if selector == "js":
                    # A damaged download must not become discoverable and a retry can succeed.
                    with asset.open("ab") as damaged:
                        damaged.write(b"corrupt")
                    failure = run(home, "frontend", "install", selector, path=install_tools, success=False)
                    assert "checksum mismatch" in failure.stderr.lower(), failure.stderr
                    run(home, "frontend", "describe", selector, success=False)
                    shutil.copyfile(assets / asset.name, asset)
                run(home, "frontend", "install", selector, path=install_tools)
                version_dir = home / ".graphite/frontends/js" / version
                for alias in SELECTORS:
                    info = json.loads(run(home, "frontend", "describe", alias).stdout)
                    assert "es" in info["aliases"], info
                    assert info["version"] == version and info["kind"] == "executable", info
                    assert Path(info["path"]) == version_dir / asset.name, info
                assert list(version_dir.iterdir()) == [version_dir / asset.name], list(version_dir.iterdir())
                assert cmp(asset, version_dir / asset.name, shallow=False), "Installed executable differs from release"
        finally:
            server.shutdown()
            thread.join()

    reader = root / "reader"
    reader.mkdir()
    if source_frontend:
        # The reference runs under Node only on the producer machine. Consumer
        # subprocesses receive neither override and always run with an empty PATH.
        node = shutil.which("node")
        if not node:
            raise RuntimeError("Comparing against the development frontend requires Node")
        run(reader, "build", "--lang", "js", "app.mjs", "-o", "reference.graphite",
            extra_env={"GRAPHITE_FRONTEND_JS": str(source_frontend.resolve()), "GRAPHITE_NODE": node})

    sibling = work / "saved"
    sibling.mkdir()
    (sibling / "keep.txt").write_text("unrelated user data")
    saved = work / "saved.graphite"
    saved_digest = work / "saved.graphite.sha256"
    for selector in SELECTORS:
        home = root / selector
        graph = f"{selector}.graphite"
        run(home, "build", "--lang", selector, "app.mjs", "-o", graph)
        if source_frontend:
            assert cmp(work / "reference.graphite", work / graph, shallow=False), "Graph differs from Node frontend"
        # The saved graph can also be read by a consumer without any frontend.
        run(reader, "verify", graph)
        query = ("MATCH (c:CallSite) RETURN c.callee_class AS owner, c.callee_name AS name "
                 "ORDER BY owner, name")
        rows = json.loads(run(reader, "query", graph, query, "--format", "json").stdout)["rows"]
        assert rows == [{"owner": "external:typescript/lib.Array", "name": "map"},
                        {"owner": "external:typescript/lib.Console", "name": "log"}], rows
        flow = ("MATCH (s:StringConstant)-[r:DATAFLOW]->(c:CallSite) WHERE r.kind = 'PARAMETER_PASS' "
                "RETURN s.value AS value, c.callee_name AS target")
        rows = json.loads(run(reader, "query", graph, flow, "--format", "json").stdout)["rows"]
        assert rows == [{"value": "installed", "target": "log"}], rows
        assert not (work / "preload-ran").exists(), "Frontend executed a preload from the analyzed project"
        # Exercise default archive publication and rebuild through every alias,
        # without a runtime on PATH; the same-named directory is left untouched.
        (work / "rebuild.mjs").write_text(f'console.log("{selector}");\n')
        run(home, "build", "--lang", selector, "rebuild.mjs", "-o", "saved")
        rows = json.loads(run(reader, "query", "saved.graphite", flow, "--format", "json").stdout)["rows"]
        assert rows == [{"value": selector, "target": "log"}], rows
        run(reader, "verify", "saved.graphite")
        assert [p.name for p in sibling.iterdir()] == ["keep.txt"]
        assert (sibling / "keep.txt").read_text() == "unrelated user data"
        unpacked = f"saved-{selector}-unpacked"
        run(reader, "unpack", "saved.graphite", unpacked)
        directory_rows = json.loads(run(reader, "query", unpacked, flow, "--format", "json").stdout)["rows"]
        assert directory_rows == rows, directory_rows
        before = (saved.read_bytes(), saved_digest.read_bytes())
        (work / "rebuild.mjs").write_text('function broken( {')
        failed = run(home, "build", "--lang", selector, "rebuild.mjs", "-o", "saved", success=False)
        assert failed.returncode == 1 and "No parseable JavaScript artifacts remain" in failed.stderr, failed
        assert (saved.read_bytes(), saved_digest.read_bytes()) == before
        rows = json.loads(run(reader, "query", "saved.graphite", flow, "--format", "json").stdout)["rows"]
        assert rows == [{"value": selector, "target": "log"}], rows
        print(f"Verified clean install {selector}, offline build/rebuild with empty PATH, "
              "failed rebuild preservation, and saved Array.map / Console.log calls")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--target", required=True, help="Platform suffix of the native release executable")
    parser.add_argument("--source-frontend", type=Path, help="Also compare the packed graph with this Node entry")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="graphite-js-install-") as temp:
        check(Path(temp).resolve(), args.cli.resolve(), args.assets.resolve(), args.version, args.target, args.source_frontend)
