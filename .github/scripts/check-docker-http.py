#!/usr/bin/env python3
"""Check real published-port access with the image's default and custom commands."""
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, timeout=30).strip()


def check(image, graph, arguments):
    container = docker(
        "run", "--detach", "--publish", "127.0.0.1::8080",
        "--mount", f"type=bind,src={graph},dst=/data,readonly", image, *arguments,
    )
    try:
        endpoint = docker("port", container, "8080/tcp")
        assert endpoint.startswith("127.0.0.1:"), endpoint
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 30
        while True:
            try:
                with opener.open(f"http://{endpoint}/api/graphs", timeout=2) as response:
                    payload = json.load(response)
                break
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if docker("inspect", "--format", "{{.State.Running}}", container) != "true":
                    raise RuntimeError("container exited before HTTP readiness")
                if time.monotonic() >= deadline:
                    raise RuntimeError("published port did not become reachable")
                time.sleep(0.25)
        assert payload["count"] == 1, payload
        assert [item["id"] for item in payload["graphs"]] == ["app"], payload
        print(f"Published HTTP port serves app: {arguments or 'default CMD'}")
    except BaseException:
        subprocess.run(["docker", "logs", container], timeout=10, check=False)
        raise
    finally:
        subprocess.run(["docker", "rm", "--force", container], timeout=30, check=True)


if __name__ == "__main__":
    image, graph = sys.argv[1:]
    graph = Path(graph).resolve(strict=True)
    check(image, graph, [])
    check(image, graph, ["--bind-address", "0.0.0.0", "--id", "app", "/data"])
