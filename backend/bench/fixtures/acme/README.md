# acme: a small application fixture for the C4 component level

Six classes in five packages under `com.acme.shop`, with a `main` in `api` and
cross-package calls (`api -> orders -> billing -> inventory`, everything using
`common`). Built as a persisted graph it infers as an application with a runtime
container and several components whose call weights become component relationships,
which a library graph (such as the one built from graphite-core) never produces.

Build it the way CI does:

```bash
javac -d /tmp/acme-classes $(find backend/bench/fixtures/acme/src -name '*.java')
(cd /tmp/acme-classes && jar cfe /tmp/acme.jar com.acme.shop.api.Main com)
java -jar frontend/jvm/query/build/libs/graphite.jar build /tmp/acme.jar -o /tmp/acme-graph --include com.acme
```

`backend/bench/parity.py` compares every C4 level and format on it as graph id `acme`.
