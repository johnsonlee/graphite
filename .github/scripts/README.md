# CI scripts

English · [简体中文](README.zh.md)

CI helpers are TypeScript, executed directly with Bun. The runtime version is pinned
in the `packageManager` field of `package.json`; `bun.lock` pins the development
dependencies. Scripts use built-in modules and the existing `gh`, Java and
binary-inspection tools, with no runtime npm dependencies.

| Directory | Responsibility |
| --- | --- |
| `benchmark/` | Publish the single benchmark report comment after validating the workflow and product identities. |
| `checks/` | Check actual CLI/JVM launch behavior and static Linux release binaries. |
| `release/` | Parse release tags and publish immutable releases with resumable draft uploads. |

The Rust controller in the repository's root `benchmark/` executes benchmarks,
checks correctness/OOM evidence, and generates reports. The TypeScript benchmark
helper handles GitHub comment transport.

Run the same validation as CI from this directory:

```sh
bun install --frozen-lockfile
bun run check
bun test
```

Type checking is separate from runtime execution. Tests use temporary fixtures and
stub GitHub transports; they do not publish releases or comments. `checks/` also runs
against the real Rust CLI, JVM frontend and Linux binary in the Rust workflow. JVM
configuration assertions live in `benchmark/jvm/` and use MXBeans inside the actual
frontend process; TypeScript prepares the check and compares saved graph outputs.
These integration tests require JDK 17.

The required unit-test job checks the PR's scripts. The benchmark workflow checks its
trusted controller snapshot's publisher. The comment workflow executes the publisher
from the trusted default-branch workflow revision and reads downloaded reports as data.
All of `.github/` remains covered by owner review.
