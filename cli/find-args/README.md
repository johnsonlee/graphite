# graphite-cli-find-args

Find constant values passed as arguments to specified methods via JVM bytecode analysis.

## Use Cases

- **AB test inventory**: Scan all call sites of `AbClient.getOption(int)` to collect every experiment ID in the codebase
- **Feature flag audit**: Find all string keys passed to `FeatureFlags.isEnabled(String)`
- **Configuration review**: Enumerate constants passed to `Config.getInt(String)`, `Config.getString(String)`, etc.
- **Propagation analysis**: Trace how constants flow through variables, fields, and return values before reaching the target method

## CLI Interface

```
graphite-find-args [OPTIONS] <input>
```

### Positional Arguments

| Argument | Description |
|----------|-------------|
| `<input>` | Input path: JAR, WAR, Spring Boot JAR, or directory containing `.class` files |

### Options

| Option | Description |
|--------|-------------|
| `-c`, `--class` | **(required)** Fully qualified class name (e.g., `com.example.AbClient`) |
| `-m`, `--method` | **(required)** Method name (e.g., `getOption`) |
| `-r`, `--regex` | Treat `-c` and `-m` as regex patterns |
| `-p`, `--param-types` | Parameter types, comma-separated (e.g., `java.lang.Integer,java.lang.String`) |
| `-i`, `--arg-index` | Argument indices to analyze, 0-based, comma-separated (default: `0`) |
| `--include` | Package prefixes to include, comma-separated |
| `--exclude` | Package prefixes to exclude, comma-separated |
| `--include-libs` | Include library JARs from `WEB-INF/lib` or `BOOT-INF/lib` (default: auto-detect) |
| `--lib-filter` | Only load JARs matching these patterns, comma-separated (e.g., `modular-*,business-*`) |
| `-f`, `--format` | Output format: `text`, `json` (default: `text`) |
| `--show-path` | Show propagation paths for each constant value |
| `--min-depth` | Only show results with propagation depth >= N (default: `0`) |
| `--max-path-depth` | Only show results with propagation depth <= N (default: `100`) |
| `-v`, `--verbose` | Enable verbose output |

## Examples

### Find all AB experiment IDs

```bash
graphite-find-args app.jar \
  -c com.example.AbClient \
  -m getOption \
  -p java.lang.Integer \
  -i 0
```

### Find feature flag keys with regex

```bash
graphite-find-args app.jar \
  -c '.*FeatureFlag.*' \
  -m 'isEnabled|getValue' \
  -r
```

### JSON output with propagation paths

```bash
graphite-find-args app.jar \
  -c com.example.AbClient \
  -m getOption \
  -f json \
  --show-path
```

## Output

### Text format

```
Found 3 argument constant(s) for com.example.AbClient.getOption (arg 0):

  1001
  Type: int
  Occurrences: 2
  Propagation depth: 0
    - CheckoutService.processOrder():42
    - CartService.getCart():78

  1002
  Type: int
  Occurrences: 1
  Propagation depth: 2
    - PaymentService.charge():15 [depth=2]

Summary:
  Unique values: 2
  Total occurrences: 3
  Max propagation depth: 2
  Avg propagation depth: 0.7
```

### JSON format

```json
{
  "targetClass": "com.example.AbClient",
  "targetMethod": "getOption",
  "argumentIndices": [0],
  "totalOccurrences": 3,
  "statistics": {
    "maxPropagationDepth": 2,
    "avgPropagationDepth": 0.67,
    "complexPaths": 1
  },
  "uniqueValues": [
    {
      "type": "int",
      "value": 1001,
      "depthRange": { "min": 0, "max": 0 },
      "occurrences": [...]
    }
  ]
}
```

## Dependencies

| Library | Purpose |
|---------|---------|
| `picocli` | CLI argument parsing |
| `gson` | JSON output formatting |
| `graphite-core` | Core graph model and query DSL |
| `graphite-sootup` | SootUp bytecode analysis backend |

## Analysis Pipeline

```
Input (JAR/WAR/dir)
  → JavaProjectLoader: bytecode → Graph (nodes + edges)
  → Graphite.query { findArgumentConstants { ... } }
  → Backward slice: trace each argument to its constant origin
  → Filter by propagation depth
  → Format and output
```
