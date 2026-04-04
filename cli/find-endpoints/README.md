# graphite-cli-find-endpoints

Find HTTP endpoints from Spring MVC annotations and analyze their return type hierarchy via JVM bytecode analysis.

## Use Cases

- **API inventory**: Enumerate all HTTP endpoints in a Spring application from compiled bytecode
- **API schema generation**: Generate OpenAPI 3.0 spec from bytecode, including actual return type structures (resolves generics like `ApiResponse<PageData<User>>`)
- **API review**: Filter endpoints by path pattern or HTTP method for targeted analysis
- **Documentation**: Produce endpoint documentation without source code access

## CLI Interface

```
graphite-find-endpoints [OPTIONS] <input>
```

### Positional Arguments

| Argument | Description |
|----------|-------------|
| `<input>` | Input path: JAR, WAR, Spring Boot JAR, or directory containing `.class` files |

### Options

| Option | Description |
|--------|-------------|
| `-e`, `--endpoint` | Endpoint path pattern to match (supports `*`, `**`, `{param}` wildcards) |
| `-m`, `--method` | HTTP method filter: `GET`, `POST`, `PUT`, `DELETE`, `PATCH` |
| `--include` | Package prefixes to include, comma-separated |
| `--exclude` | Package prefixes to exclude, comma-separated |
| `--include-libs` | Include library JARs from `WEB-INF/lib` or `BOOT-INF/lib` |
| `--lib-filter` | Only load JARs matching these patterns, comma-separated |
| `-f`, `--format` | Output format: `text`, `schema` (OpenAPI 3.0 JSON) (default: `text`) |
| `-v`, `--verbose` | Enable verbose output |

### Endpoint Pattern Matching

| Pattern | Matches |
|---------|---------|
| `/users/*` | `/users/123`, `/users/abc` (single segment) |
| `/api/**` | `/api/users/123`, `/api/orders/456/items` (multiple segments) |
| `/users/{id}` | `/users/123` (path params treated as wildcards) |

## Examples

### List all endpoints

```bash
graphite-find-endpoints app.jar
```

### Filter by path and HTTP method

```bash
graphite-find-endpoints app.jar \
  -e '/api/users/**' \
  -m GET
```

### Generate OpenAPI spec

```bash
graphite-find-endpoints app.jar \
  -f schema \
  > openapi.json
```

### Analyze specific module in a WAR

```bash
graphite-find-endpoints app.war \
  --include com.example.api \
  --lib-filter 'business-*'
```

## Output

### Text format

```
Found 3 endpoint(s):

/api/users
  GET     /api/users
          -> UserController.list()
          Declared: ResponseEntity
          Actual:   ApiResponse<PageData<User>>
                    └── data: PageData<User>
                        ├── items: List<User>
                        │   └── User
                        │       ├── id: Long
                        │       ├── name: String
                        │       └── email: String
                        ├── page: int
                        └── total: long

  POST    /api/users
          -> UserController.create()
          Declared: ResponseEntity
          Actual:   ApiResponse<User>

/api/orders
  GET     /api/orders/{id}
          -> OrderController.getById()
          Declared: Order
          Actual:   Order

Summary: 3 endpoint(s)
```

### Schema format (OpenAPI 3.0)

```json
{
  "openapi": "3.0.3",
  "info": {
    "title": "API Documentation",
    "description": "Generated from bytecode analysis by Graphite",
    "version": "1.0.0"
  },
  "paths": {
    "/api/users": {
      "get": {
        "operationId": "UserController_list",
        "tags": ["UserController"],
        "responses": {
          "200": {
            "description": "Successful response",
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/ApiResponse" }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "ApiResponse": {
        "type": "object",
        "properties": {
          "data": { "$ref": "#/components/schemas/PageData" },
          "code": { "type": "integer", "format": "int32" },
          "message": { "type": "string" }
        }
      }
    }
  }
}
```

## Dependencies

| Library | Purpose |
|---------|---------|
| `picocli` | CLI argument parsing |
| `gson` | JSON / OpenAPI output formatting |
| `graphite-core` | Core graph model, query DSL, type hierarchy analysis |
| `graphite-sootup` | SootUp bytecode analysis backend |

## Analysis Pipeline

```
Input (JAR/WAR/dir)
  → JavaProjectLoader: bytecode → Graph (nodes + edges)
  → Graph.endpoints(): scan Spring MVC annotations (@GetMapping, @PostMapping, etc.)
  → Filter by path pattern and HTTP method
  → Graphite.query { findTypeHierarchy { ... } }: resolve actual return types
  → Format as text or OpenAPI 3.0 schema
```
