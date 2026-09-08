# Existing Java quote-rewrite error-index controls

These77 independent actual-Java17 cases track a preexisting compatibility gap:
`.*\Qfoo\Ebar\E.*` has Java error index9, while the preexisting Go matcher was
observed to report13. The proposed quoted-contains fast path rejects this shape;
this is not a performance-optimization regression. The parent609-case corpus
and its captures remain unchanged.

The matrix varies quoted ASCII letters, metacharacters, initial digits, leading
backslashes, Unicode BMP/supplementary characters and lone surrogates. It adds
invalid escape/class/group tails, escaped prefixes, multiple quotes, empty and
unclosed quotes, comments mode, actual TAB versus literal backslash-t, hex/octal
prefix interactions and EOF syntax errors. Two original Java runs have complete
identical outputs:74 compile errors and3 legal controls.

Examples recorded directly from Java17:

| Pattern | Index / result |
| --- | --- |
| `.*\Qfoo\Ebar\E.*` | 9, illegal escape |
| `\Q\E[` | 0 |
| `\Q\E\Q1\E[` | 4 |
| `\x\Q1\E[` | 2, hexadecimal escape |
| `\0\Q1\E[` | 2, octal escape |
| `(?x)\Q #\E[` | 8 |
| `\Q!!!\E[` | 6 |
| `\Q!!!!!!!!\E[` | 16 |
| `\Q😀\E(` | 2 |
| `\Q😀\E\Q1.\E[` | 7 |
| `\Qfoo` | compiles; empty text does not match |
| `\Q\E` | compiles; empty text matches |

Error messages retain the original pattern plus its caret formatting, while
reported indices reflect Java's quote-rewritten parsing behavior. Do not assume
a raw UTF-16 source offset from these numbers. `patternUTF16`, `textUTF16` and
`errorUTF16` preserve surrogate and TAB details without lossy JSON decoding.

Reproduce into new external output directories:

```sh
python3 docs/go-server-baseline/native-regex-quoted-contains/quote-index-controls/prepare.py
python3 docs/go-server-baseline/native-regex-quoted-contains/quote-index-controls/run.py /tmp/quote-index-fresh-a
python3 docs/go-server-baseline/native-regex-quoted-contains/quote-index-controls/run.py /tmp/quote-index-fresh-b
python3 docs/go-server-baseline/native-regex-quoted-contains/quote-index-controls/verify.py
```

The unchanged parent Java helper executes `Pattern.compile` then `matches`, with
an empty public-Cypher list. No graph is loaded and no public query is executed.
Receipts bind the actual Java17 executable/runtime modules/JVM library, helper
source/class, pinned main JAR and exact case inputs. All runs are correctness
only; no synthetic timing or performance claim is produced. This directory has
its own explicit manifest and does not rewrite the parent oracle manifest.
