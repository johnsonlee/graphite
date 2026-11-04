#!/usr/bin/env python3
"""Install exact JS release assets through real curl, then build and read a saved graph."""
import argparse
from functools import partial
from http.server import HTTPServer, SimpleHTTPRequestHandler
import json
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
from threading import Thread

ARCHIVE = "graphite-frontend-js.tgz"
SELECTORS = ("js", "ts", "es")


def check(root, cli, assets, version):
    # Only curl's destination and trust anchor change. The production URL, tag,
    # asset names, transfer, checksum verification and unpacking run unchanged.
    downloads = root / "mirror" / "johnsonlee/graphite/releases/download" / f"v{version}"
    downloads.mkdir(parents=True)
    archive = downloads / ARCHIVE
    checksum = downloads / (ARCHIVE + ".sha256")
    shutil.copyfile(assets / archive.name, archive)
    shutil.copyfile(assets / checksum.name, checksum)
    cert, key = root / "cert.pem", root / "key.pem"
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                    "-subj", "/CN=github.com", "-addext", "subjectAltName=DNS:github.com",
                    "-keyout", key, "-out", cert], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)

    work = root / "work"
    work.mkdir()
    (work / "app.mjs").write_text('console.log("installed");\n[1].map(x => x + 1);\n')
    binary = work / "graphite"
    shutil.copy2(cli, binary)
    install_tools = root / "install-tools"
    install_tools.mkdir()
    for name in ("curl", "node"):
        executable = shutil.which(name)
        if not executable:
            raise RuntimeError(f"The installation check requires {name} on PATH")
        (install_tools / name).symlink_to(executable)
    runtime = root / "runtime"
    runtime.mkdir()
    (runtime / "node").symlink_to(install_tools / "node")

    def run(home, *args, path=install_tools, success=True):
        result = subprocess.run([binary, *args], cwd=work, env={"HOME": str(home), "PATH": str(path)},
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
                    with archive.open("ab") as damaged:
                        damaged.write(b"corrupt")
                    failure = run(home, "frontend", "install", selector, success=False)
                    assert "checksum mismatch" in failure.stderr.lower(), failure.stderr
                    run(home, "frontend", "describe", selector, success=False)
                    shutil.copyfile(assets / archive.name, archive)
                run(home, "frontend", "install", selector)
                for alias in SELECTORS:
                    info = json.loads(run(home, "frontend", "describe", alias).stdout)
                    assert "es" in info["aliases"], info
                    assert info["version"] == version and info["kind"] == "node", info
                    assert Path(info["path"]).is_relative_to(home / ".graphite/frontends/js" / version), info
        finally:
            server.shutdown()
            thread.join()

    for selector in SELECTORS:
        home = root / selector
        graph = f"{selector}.graphite"
        run(home, "build", "--lang", selector, "app.mjs", "-o", graph, path=runtime)
        # New process, empty home, no Node/npm or frontend on PATH.
        reader = root / "reader"
        reader.mkdir(exist_ok=True)
        run(reader, "verify", graph, path=reader)
        query = ("MATCH (c:CallSite) RETURN c.callee_class AS owner, c.callee_name AS name "
                 "ORDER BY owner, name")
        rows = json.loads(run(reader, "query", graph, query, "--format", "json", path=reader).stdout)["rows"]
        assert rows == [{"owner": "external:typescript/lib.Array", "name": "map"},
                        {"owner": "external:typescript/lib.Console", "name": "log"}], rows
        flow = ("MATCH (s:StringConstant)-[r:DATAFLOW]->(c:CallSite) WHERE r.kind = 'PARAMETER_PASS' "
                "RETURN s.value AS value, c.callee_name AS target")
        rows = json.loads(run(reader, "query", graph, flow, "--format", "json", path=reader).stdout)["rows"]
        assert rows == [{"value": "installed", "target": "log"}], rows
        print(f"Verified clean install {selector}, offline build, and saved Array.map / Console.log calls")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="graphite-js-install-") as temp:
        check(Path(temp).resolve(), args.cli.resolve(), args.assets.resolve(), args.version)
