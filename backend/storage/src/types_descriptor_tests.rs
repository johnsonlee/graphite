//! Independent GTY05 fixture writer: descriptors occur in the oracle, never in its string dictionary.
use super::*;
use crate::types::DeclaredTypes as Loaded;
use std::borrow::Cow;

struct DescriptorWire {
    bytes: Vec<u8>,
    strings: Vec<u8>,
    names: Vec<String>,
    types: Vec<usize>,
    pool_count: usize,
    signatures: Vec<usize>,
    field_count: usize,
    fields: Vec<usize>,
    method_count: usize,
    methods: Vec<usize>,
}
fn int(bytes: &mut Vec<u8>, value: i32) {
    bytes.extend(value.to_be_bytes());
}
fn fixture(class_name: &str) -> DescriptorWire {
    let mut names = [
        class_name,
        "Owner",
        "java.util.List",
        "int",
        "void",
        "T",
        "field",
        "echo",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect::<Vec<_>>();
    names.sort_by(|a, b| crate::strings::java_cmp(a, b));
    names.dedup();
    let id = |name: &str| names.iter().position(|v| v == name).unwrap() as i32;
    let strings = serialized(
        &names
            .iter()
            .map(|s| s.encode_utf16().collect())
            .collect::<Vec<_>>(),
    );
    let mut bytes = 0x47545905i32.to_be_bytes().to_vec();
    bytes.extend(Sha256::digest(b"metadata"));
    bytes.extend(Sha256::digest(&strings));
    int(&mut bytes, 10);
    let mut types = Vec::new();
    for (kind, name, tag, target, component, arguments) in [
        (0, id(class_name), 0, -1, -1, vec![]),        // 0 raw class
        (1, id("int"), 0, -1, -1, vec![]),             // 1 int
        (1, id("void"), 0, -1, -1, vec![]),            // 2 void
        (2, -1, 0, -1, 0, vec![]),                     // 3 one-dimensional raw class array
        (2, -1, 0, -1, 3, vec![]),                     // 4 two-dimensional raw class array
        (0, id("java.util.List"), 0, -1, -1, vec![]),  // 5 raw List
        (3, id("T"), 2, 0, -1, vec![]),                // 6 method variable
        (0, id("java.util.List"), 0, -1, -1, vec![6]), // 7 generic List<T>
        (0, id(class_name), 0, -1, -1, vec![]), // 8 semantically duplicate raw class at another ID
        (3, id("T"), 4, 1, -1, vec![]),         // 9 unresolved second method scope
    ] {
        types.push(bytes.len());
        bytes.extend([kind, 0, tag, 0]);
        for value in [name, target, -1, component, arguments.len() as i32] {
            int(&mut bytes, value);
        }
        for argument in arguments {
            int(&mut bytes, argument);
        }
    }
    let pool_count = bytes.len();
    int(&mut bytes, 3);
    let mut signatures = Vec::new();
    // Full return identity and array dimensions/order distinguish these three signatures.
    for (parameters, returns) in [(vec![4, 1, 0], 0), (vec![3, 1, 0], 0), (vec![4, 1, 0], 2)] {
        signatures.push(bytes.len());
        int(&mut bytes, parameters.len() as i32);
        for parameter in parameters {
            int(&mut bytes, parameter);
        }
        int(&mut bytes, returns);
    }
    let field_count = bytes.len();
    int(&mut bytes, 2);
    let mut fields = Vec::new();
    for raw in [5, 4] {
        fields.push(bytes.len());
        for value in [id("Owner"), id("field"), raw, 7] {
            int(&mut bytes, value);
        }
    }
    let method_count = bytes.len();
    int(&mut bytes, 3);
    let mut methods = Vec::new();
    for signature in 0..3 {
        methods.push(bytes.len());
        for value in [id("Owner"), id("echo"), signature, 1, 6, 7, 1, id("T")] {
            int(&mut bytes, value);
        }
        bytes.extend([2, 0, 0, 0]);
        for value in [signature, 1, 0] {
            int(&mut bytes, value);
        }
    }
    int(&mut bytes, 1); // one class with no formals
    for value in [id("Owner"), 0, 0, 0] {
        int(&mut bytes, value);
    }
    DescriptorWire {
        bytes,
        strings,
        names,
        types,
        pool_count,
        signatures,
        field_count,
        fields,
        method_count,
        methods,
    }
}
fn parse(wire: &DescriptorWire, bytes: &[u8]) -> Result<Loaded, TypeError> {
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    Loaded::parse_with_strings(bytes, b"metadata", &strings)
}
fn changed(wire: &DescriptorWire, offset: usize, value: i32) -> Result<Loaded, TypeError> {
    let mut bytes = wire.bytes.clone();
    bytes[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
    parse(wire, &bytes)
}
fn shared_v4(table: &MutableDeclaredTypes) -> (Vec<u8>, Vec<u8>) {
    // Independent old encoders supply the complete dictionary; this test-only encoder emits canonical04 scopes.
    let old = shared_wire(table);
    let id = |value: &str| old.dictionary.iter().position(|s| s == value).unwrap() as i32;
    let scope = |scope: &str| -> (u8, i32) {
        if scope.is_empty() {
            return (0, -1);
        }
        let (unresolved, scope) = scope
            .strip_prefix("unresolved:")
            .map_or((false, scope), |s| (true, s));
        for (row, key) in table.methods.keys().enumerate() {
            if scope == format!("method:{}#{}{}", key.0, key.1, key.2) {
                return (if unresolved { 4 } else { 2 }, row as i32);
            }
        }
        panic!("unexpected scope {scope}");
    };
    let mut bytes = 0x47545904i32.to_be_bytes().to_vec();
    bytes.extend(Sha256::digest(b"metadata"));
    bytes.extend(Sha256::digest(&old.strings));
    int(&mut bytes, table.types.len() as i32);
    for t in &table.types {
        let kind = ["class", "primitive", "array", "variable", "wildcard"]
            .iter()
            .position(|&v| v == t.kind.as_ref())
            .unwrap() as u8;
        let (tag, target) = scope(&t.scope);
        bytes.extend([kind, 0, tag, 0]);
        for value in [
            if t.name.is_empty() { -1 } else { id(&t.name) },
            target,
            t.owner.map_or(-1, |v| v as i32),
            t.component.map_or(-1, |v| v as i32),
            t.arguments.len() as i32,
        ] {
            int(&mut bytes, value);
        }
        for &arg in &t.arguments {
            int(&mut bytes, arg as i32);
        }
    }
    let parameters = |out: &mut Vec<u8>, formals: &[TypeParameter]| {
        int(out, formals.len() as i32);
        for p in formals {
            int(out, id(&p.name));
            let (tag, target) = scope(&p.scope);
            out.extend([tag, 0, 0, 0]);
            int(out, target);
            int(out, p.bounds.len() as i32);
            for &bound in &p.bounds {
                int(out, bound as i32);
            }
        }
    };
    int(&mut bytes, table.fields.len() as i32);
    for (key, &value) in &table.fields {
        for v in [id(&key.0), id(&key.1), id(&key.2), value as i32] {
            int(&mut bytes, v);
        }
    }
    int(&mut bytes, table.methods.len() as i32);
    for (key, method) in &table.methods {
        for v in [
            id(&key.0),
            id(&key.1),
            id(&key.2),
            method.parameters.len() as i32,
        ] {
            int(&mut bytes, v);
        }
        for &p in &method.parameters {
            int(&mut bytes, p as i32);
        }
        int(&mut bytes, method.returns as i32);
        parameters(&mut bytes, &method.type_parameters);
    }
    int(&mut bytes, table.classes.len() as i32);
    for (name, class) in &table.classes {
        int(&mut bytes, id(name));
        parameters(&mut bytes, &class.type_parameters);
        int(&mut bytes, class.superclass.map_or(-1, |v| v as i32));
        int(&mut bytes, class.interfaces.len() as i32);
        for &i in &class.interfaces {
            int(&mut bytes, i as i32);
        }
    }
    (bytes, old.strings)
}
#[test]
fn descriptorless_keys_preserve_full_erased_identity_scope_and_all_legacy_views() {
    let wire = fixture("泛型🚀.Object");
    assert!(wire.names.iter().all(|s| !s.starts_with('(')
        && !s.starts_with('[')
        && !s.ends_with(';')
        && !s.starts_with("method:")));
    let table = parse(&wire, &wire.bytes).unwrap();
    let base = "L泛型🚀/Object;";
    let first = format!("([[{base}I{base}){base}");
    let second = format!("([{base}I{base}){base}");
    let third = format!("([[{base}I{base})V");
    for descriptor in [&first, &second, &third] {
        let method = table.method_types("Owner", "echo", descriptor).unwrap();
        assert_eq!(method.parameters, [6]);
        assert_eq!(method.returns, 7);
        assert_eq!(
            method.type_parameters.iter().next().unwrap().scope,
            format!("method:Owner#echo{descriptor}")
        );
    }
    assert!(table
        .method_types("Owner", "echo", &format!("(I[[{base}{base}){base}"))
        .is_none());
    assert_eq!(
        table.type_expr(6).scope,
        format!("method:Owner#echo{first}")
    );
    assert_eq!(
        table.type_expr(9).scope,
        format!("unresolved:method:Owner#echo{second}")
    );
    assert_eq!(
        table.field_type("Owner", "field", "Ljava/util/List;"),
        Some(7)
    );
    assert_eq!(
        table.field_type("Owner", "field", &format!("[[{base}")),
        Some(7)
    );
    assert!(table
        .field_type("Owner", "field", &format!("[{base}"))
        .is_none());
    let entries = table.method_entries().collect::<Vec<_>>();
    assert!(matches!(entries[0].0[0], Cow::Borrowed("Owner")));
    assert!(matches!(entries[0].0[2], Cow::Owned(_)));
    assert_eq!(
        entries
            .iter()
            .map(|(key, _)| key[2].as_ref())
            .collect::<Vec<_>>(),
        [first.as_str(), second.as_str(), third.as_str()]
    );
    let owned = table.to_mutable();
    assert_eq!(table, Loaded::from(owned.clone()));
    for pooled in [false, true] {
        assert_eq!(
            table,
            Loaded::parse(&encode(&owned, pooled, &[]).bytes, b"metadata").unwrap()
        );
    }
    let v3 = shared_wire(&owned);
    assert_eq!(
        table,
        parse(
            &DescriptorWire {
                strings: v3.strings,
                ..fixture("unused")
            },
            &v3.bytes
        )
        .unwrap()
    );
    let (v4, strings) = shared_v4(&owned);
    assert_eq!(
        table,
        parse(
            &DescriptorWire {
                strings,
                ..fixture("unused")
            },
            &v4
        )
        .unwrap()
    );
}
#[test]
fn descriptorless_rejects_nonraw_cycles_void_and_invalid_signature_references_before_rendering() {
    let wire = fixture("java.lang.Object");
    for invalid in [2, 6, 7, 9] {
        assert!(changed(&wire, wire.fields[0] + 8, invalid).is_err());
    }
    for invalid in [2, 6, 7, 9] {
        assert!(changed(&wire, wire.signatures[0] + 4, invalid).is_err());
    }
    for invalid in [-1, 3, i32::MAX] {
        assert!(changed(&wire, wire.methods[0] + 8, invalid).is_err());
    }
    for invalid in [6, 7, 9] {
        assert!(changed(&wire, wire.signatures[0] + 16, invalid).is_err());
    }
    assert!(changed(&wire, wire.types[3] + 16, 3)
        .unwrap_err()
        .0
        .contains("cyclic"));
    assert!(changed(&wire, wire.types[3] + 16, 2).is_err()); // void[]
    assert!(changed(&wire, wire.types[0] + 12, 0).is_err()); // raw class cannot have owner
    for name in [
        "bad/name",
        "bad;name",
        "bad[name",
        ".bad",
        "bad.",
        "bad..name",
    ] {
        let bad = fixture(name);
        assert!(parse(&bad, &bad.bytes).is_err(), "{name}");
    }
    // Primitive-spelled class remains a class, not the primitive int.
    let special = fixture("int");
    assert!(parse(&special, &special.bytes)
        .unwrap()
        .method_types("Owner", "echo", "([[Lint;ILint;)Lint;")
        .is_some());
}
#[test]
fn descriptorless_rejects_semantic_signature_and_member_duplicates_across_distinct_type_ids() {
    let wire = fixture("java.lang.Object");
    // Signature1 becomes signature0 while its raw return uses the equivalent raw type8.
    let mut bytes = wire.bytes.clone();
    bytes[wire.signatures[1] + 4..wire.signatures[1] + 8].copy_from_slice(&4i32.to_be_bytes());
    bytes[wire.signatures[1] + 16..wire.signatures[1] + 20].copy_from_slice(&8i32.to_be_bytes());
    assert!(parse(&wire, &bytes)
        .unwrap_err()
        .0
        .contains("duplicate erased signature"));
    assert!(changed(&wire, wire.methods[1] + 8, 0)
        .unwrap_err()
        .0
        .contains("duplicate method"));
    let mut bytes = wire.bytes.clone();
    bytes[wire.fields[0] + 8..wire.fields[0] + 12].copy_from_slice(&0i32.to_be_bytes());
    bytes[wire.fields[1] + 8..wire.fields[1] + 12].copy_from_slice(&8i32.to_be_bytes());
    assert!(parse(&wire, &bytes)
        .unwrap_err()
        .0
        .contains("duplicate field"));
}
#[test]
fn descriptorless_rejects_all_truncations_bad_pool_counts_and_digest_mismatch() {
    let wire = fixture("java.lang.Object");
    for length in 0..wire.bytes.len() {
        assert!(
            parse(&wire, &wire.bytes[..length]).is_err(),
            "truncation {length}"
        );
    }
    for offset in [wire.pool_count, wire.field_count, wire.method_count] {
        for count in [-1, i32::MAX] {
            assert!(changed(&wire, offset, count).is_err());
        }
    }
    for offset in [4, 36] {
        let mut bytes = wire.bytes.clone();
        bytes[offset] ^= 1;
        assert!(parse(&wire, &bytes).is_err());
    }
    let mut bytes = wire.bytes.clone();
    bytes.push(0);
    assert!(parse(&wire, &bytes).is_err());
}
#[test]
fn descriptorless_method_scope_utf8_budget_is_checked_without_expanding_the_descriptor() {
    let wire = fixture(&format!("prefix.{}", "界".repeat(2000)));
    let mut bytes = wire.bytes.clone();
    let signature = wire.signatures[0];
    bytes[signature..signature + 4].copy_from_slice(&200i32.to_be_bytes());
    bytes.splice(
        signature + 4..signature + 16,
        (0..200).flat_map(|_| 0i32.to_be_bytes()),
    );
    assert!(parse(&wire, &bytes)
        .unwrap_err()
        .0
        .contains("projection budget"));
}

#[test]
fn descriptorless_compressed_signature_overflow_is_rejected_before_hashing_or_materialization() {
    let wire = fixture(&"x".repeat(32_000));
    let count = i32::MAX / 32_002 + 1;
    let signature = wire.signatures[0];
    let mut bytes = wire.bytes.clone();
    bytes[signature..signature + 4].copy_from_slice(&count.to_be_bytes());
    bytes.splice(
        signature + 4..signature + 16,
        (0..count).flat_map(|_| 0i32.to_be_bytes()),
    );
    assert!(parse(&wire, &bytes)
        .unwrap_err()
        .0
        .contains("representable text length"));
}

#[test]
fn descriptorless_directory_and_packed_loading_use_the_bound_shared_dictionary() {
    let wire = fixture("java.lang.Object");
    let root = std::env::temp_dir().join(format!(
        "graphite-types-descriptorless-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&root);
    let dir = root.join("source");
    std::fs::create_dir_all(&dir).unwrap();
    for name in crate::container::REQUIRED_ENTRIES {
        std::fs::write(dir.join(name), b"metadata").unwrap();
    }
    std::fs::write(dir.join("graph.strings"), &wire.strings).unwrap();
    std::fs::write(dir.join("graph.types"), &wire.bytes).unwrap();
    crate::types::tests::bind(&dir, &wire.bytes);
    let packed = root.join("fixture.graphite");
    crate::container::pack(&dir, &packed).unwrap();
    for path in [&dir, &packed] {
        let source = GraphSource::open(path).unwrap();
        assert!(Loaded::needs_shared_strings(&source).unwrap());
        assert_eq!(
            Loaded::load(&source).unwrap().unwrap(),
            parse(&wire, &wire.bytes).unwrap()
        );
    }
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn descriptorless_long_stream_hash_matches_borrowed_lookup_text_across_buffer_boundaries() {
    let wire = fixture(&format!("namespace.{}", "x".repeat(2_100)));
    let table = parse(&wire, &wire.bytes).unwrap();
    for (key, expected) in table.method_entries() {
        assert_eq!(
            table.method_types(&key[0], &key[1], &key[2]),
            Some(expected)
        );
    }
    for (key, expected) in table.field_entries() {
        assert_eq!(table.field_type(&key[0], &key[1], &key[2]), Some(expected));
    }
}

#[test]
fn descriptorless_text_summary_includes_generated_scope_joins_and_enum_words() {
    let wire = fixture("foo.Bar");
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let table = Loaded::parse_with_strings(&wire.bytes, b"metadata", &strings).unwrap();
    for id in 0..table.type_count() {
        let t = table.type_expr(id);
        for text in [table.render(id), t.scope.into_owned()] {
            let ends: Vec<_> = text
                .char_indices()
                .map(|(i, _)| i)
                .chain([text.len()])
                .collect();
            for &a in &ends {
                for &b in &ends {
                    if a <= b {
                        assert!(
                            table.generic_text_may_contain(&text[a..b]),
                            "{}",
                            &text[a..b]
                        );
                    }
                }
            }
        }
    }
    for text in [
        "kind=variable",
        "arguments=[]",
        "scope=method:Owner#echo",
        "unresolved:method",
        "Lfoo/Bar",
    ] {
        assert!(table.generic_text_may_contain(text), "{text}");
    }
    assert!(!table.generic_text_may_contain("ZZZAbsent"));
    let owned = Loaded::from(table.to_mutable());
    assert!(
        owned.generic_text_may_contain("ZZZAbsent"),
        "mutable backings cannot borrow a negative summary"
    );
}
