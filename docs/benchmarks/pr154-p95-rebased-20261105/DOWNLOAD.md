# Raw benchmark evidence

All allowlisted raw samples and failures are preserved. Historical invalid measurements are retained with their limitations; see [package scope](package-scope.json). Product binaries and graph payloads are omitted.

Complete compressed stream SHA-256: `cb8092d00bc20ed6cbde6a36f94b7de330f3347768ec6651cb3f8ff22210547f`.

- [raw-evidence.tar.gz.part001](raw-evidence.tar.gz.part001?raw=1): 41,943,040 bytes; SHA-256 `f5e0af5d501d600fc7c54838b5fbe78b9a20b934ae880e834ba6587e7e4448da`
- [raw-evidence.tar.gz.part002](raw-evidence.tar.gz.part002?raw=1): 41,943,040 bytes; SHA-256 `cdb1faff49c11b104d67046b8c7d64d955afa69e381e50609d7bbedb41173e2e`
- [raw-evidence.tar.gz.part003](raw-evidence.tar.gz.part003?raw=1): 4,553,760 bytes; SHA-256 `89c1f5101ebb1de5bd8ed9d039831053b5f803c8f5fae14e1542c206b11adf4b`

Reassemble before extracting:

```sh
cat raw-evidence.tar.gz.part* > raw-evidence.tar.gz
tar -xzf raw-evidence.tar.gz
```

[Archive member checksums](raw-files.json) · [Public checksums](SHA256SUMS)
