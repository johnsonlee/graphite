# Build an iOS application graph

The Apple frontend uses the existing compiler index store and Graph IR importer.
It does not require the universal schema library. The resulting graph contains
types, typed declarations, call sites and supported literal arguments, and can be
queried or served with the native CLI.

## Prepare the tools

Use macOS with the Xcode version required by the application, JDK 17+, Rust, and
Swift 6.1+ to compile the Apple frontend. Resolve the application's normal build
dependencies first, including its pinned CocoaPods/submodules where applicable.

When testing this branch from a normal repository clone, build all three components:

```bash
apple_host=$(rustc -vV | awk '$1 == "host:" { print $2 }')
cargo build --release --locked -p graphite-cli --target "$apple_host"
frontend/apple/swift.sh build -c release --jobs 4
./gradlew :query:shadowJar --no-daemon

graphite="$PWD/target/$apple_host/release/graphite"
export GRAPHITE_FRONTEND_APPLE="$PWD/frontend/apple/.build/release/graphite-frontend-apple"
export GRAPHITE_FRONTEND_JVM="$PWD/frontend/jvm/query/build/libs/graphite.jar"
"$graphite" frontend list
"$graphite" frontend describe apple
```

Use this explicit CLI path throughout. An older `graphite` on PATH may be a JVM
launcher that does not recognize `frontend` or Xcode inputs. The environment
variables above select the matching local frontend and importer without replacing
the global installation. If the system Swift is older than 6.1, select the installed
Swift toolchain before compiling the frontend.

## Build and query

```bash
"$graphite" build ~/src/MyApp -o ~/graphs/myapp.graphite \
  --scheme MyApp --destination 'generic/platform=iOS Simulator' \
  --configuration Debug --derived-data ~/graphs/MyApp-DerivedData

"$graphite" verify ~/graphs/myapp.graphite
"$graphite" query ~/graphs/myapp.graphite \
  "MATCH (m:Method) WHERE m.class = 'MyApp.AppDelegate' RETURN m.name, m.parameter_types, m.return_type" -f json
"$graphite" serve --graph "myapp:$HOME/graphs/myapp.graphite"
```

For a directory input, `Package.swift` takes precedence. Otherwise the CLI selects
its unique immediate `.xcworkspace`, or its unique immediate `.xcodeproj` when no
workspace exists. Multiple candidates produce an error listing the paths; select
the intended workspace/project explicitly. `--lang jvm` retains explicit JVM routing.

Use the graph's actual declaring type in the query: not every application has an
`AppDelegate`, and imported Objective-C types can have different qualifications.
Inspect the returned methods, signatures and calls expected from the application's
source, in addition to verifying the container's checksums.

For a large application, the importer heap can be selected explicitly. The Signal
validation used `JAVA_TOOL_OPTIONS=-Xmx8g` for the build command, including import;
8 GiB is the maximum permitted JVM heap, rather than a requirement for small apps.

The frontend builds the chosen scheme with compiler indexing enabled and code
signing disabled, emits IR, imports it through the JVM frontend, and packs the
result. A single shared scheme can be selected automatically; multiple schemes
require `--scheme`. Simulator builds avoid requiring a signing identity.

## Reuse an existing build

```bash
"$graphite" build ~/src/MyApp/MyApp.xcworkspace -o ~/graphs/myapp.graphite \
  --skip-build --derived-data ~/graphs/MyApp-DerivedData
```

The DerivedData must belong to the same source checkout and intended build. Index
records contain source paths; copying a checkout without rebuilding does not update
those records. When none of the selected source files has indexed occurrences, the
frontend fails before creating or replacing the IR. Check the checkout, scheme,
configuration and DerivedData path, then rebuild. Sources excluded from a target
need not all have occurrences; a declaration-only graph can legitimately have no
call-site nodes.

## Current analysis boundary

Nonliteral arguments retain their source text and position; this is not complete
assignment, field or return-value dataflow. Objective-C expression literals and Apple
resource extraction remain additional analysis work. C/Objective-C declaration
enrichment is a lightweight parser, including C++ language-linkage blocks, rather
than a complete preprocessor. Conditional macro-dependent types can remain unknown.
See [the validation report](apple-frontend-validation.md) for retained evidence and
the separately reported performance limitations. Building one application here is a
correctness check, not performance acceptance.
