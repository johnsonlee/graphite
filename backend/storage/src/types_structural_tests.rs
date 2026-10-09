//! GTY04 bytes are written independently here, without the production parser or JVM writer.
use super::*;
use crate::types::DeclaredTypes as Loaded;
use std::borrow::Cow;

const DESCRIPTOR: &str = "(Ljava/lang/Object;Ljava/lang/Object;)V";
struct StructuralWire {
    bytes: Vec<u8>,
    strings: Vec<u8>,
    dictionary: Vec<String>,
    types: Vec<usize>,
    formals: Vec<usize>,
}
fn int(out: &mut Vec<u8>, value: i32) {
    out.extend(value.to_be_bytes());
}
fn fixture(owner: &str) -> StructuralWire {
    let mut dictionary = [
        owner,
        "java.lang.Object",
        "java.util.List",
        "void",
        "T",
        "U",
        "V",
        "field",
        "Ljava/util/List;",
        "echo",
        DESCRIPTOR,
    ]
    .into_iter()
    .map(str::to_owned)
    .collect::<Vec<_>>();
    dictionary.sort_by(|a, b| crate::strings::java_cmp(a, b));
    dictionary.dedup();
    let sid = |name: &str| dictionary.iter().position(|v| v == name).unwrap() as i32;
    let strings = serialized(
        &dictionary
            .iter()
            .map(|s| s.encode_utf16().collect())
            .collect::<Vec<_>>(),
    );
    let mut bytes = 0x47545904i32.to_be_bytes().to_vec();
    bytes.extend(Sha256::digest(b"metadata"));
    bytes.extend(Sha256::digest(&strings));
    int(&mut bytes, 9);
    let mut types = Vec::new();
    // kind, variance, scope tag, name, scope row, owner, component, arguments.
    for (kind, variance, scope, name, row, owner, component, args) in [
        (0, 0, 0, sid("java.lang.Object"), -1, -1, -1, vec![]),
        (1, 0, 0, sid("void"), -1, -1, -1, vec![]),
        (3, 0, 1, sid("T"), 0, -1, -1, vec![]),
        (3, 0, 2, sid("T"), 0, -1, -1, vec![]),
        (0, 0, 0, sid("java.util.List"), -1, -1, -1, vec![2]),
        (2, 0, 0, -1, -1, -1, 4, vec![]),
        (4, 1, 0, -1, -1, -1, 5, vec![]),
        (3, 0, 3, sid("U"), 0, -1, -1, vec![]),
        (3, 0, 4, sid("V"), 0, -1, -1, vec![]),
    ] {
        types.push(bytes.len());
        bytes.extend([kind, variance, scope, 0]);
        for value in [name, row, owner, component, args.len() as i32] {
            int(&mut bytes, value);
        }
        for arg in args {
            int(&mut bytes, arg);
        }
    }
    int(&mut bytes, 1); // field section
    for value in [sid(owner), sid("field"), sid("Ljava/util/List;"), 4] {
        int(&mut bytes, value);
    }
    int(&mut bytes, 1); // method section
    for value in [sid(owner), sid("echo"), sid(DESCRIPTOR), 2, 2, 3, 1, 2] {
        int(&mut bytes, value);
    }
    let mut formals = Vec::new();
    for (name, tag, row, bounds) in [("T", 2, 0, vec![0]), ("V", 0, -1, vec![])] {
        formals.push(bytes.len());
        int(&mut bytes, sid(name));
        bytes.extend([tag, 0, 0, 0]);
        int(&mut bytes, row);
        int(&mut bytes, bounds.len() as i32);
        for bound in bounds {
            int(&mut bytes, bound);
        }
    }
    int(&mut bytes, 1); // class section
    int(&mut bytes, sid(owner));
    int(&mut bytes, 1);
    formals.push(bytes.len());
    int(&mut bytes, sid("T"));
    bytes.extend([1, 0, 0, 0]);
    for value in [0, 1, 0, 0, 0] {
        int(&mut bytes, value);
    }
    StructuralWire {
        bytes,
        strings,
        dictionary,
        types,
        formals,
    }
}
fn load(wire: &StructuralWire) -> Loaded {
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    Loaded::parse_with_strings(&wire.bytes, b"metadata", &strings).unwrap()
}
fn rejects(wire: &StructuralWire, offset: usize, replacement: &[u8]) {
    let mut bytes = wire.bytes.clone();
    bytes[offset..offset + replacement.len()].copy_from_slice(replacement);
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    assert!(
        Loaded::parse_with_strings(&bytes, b"metadata", &strings).is_err(),
        "offset {offset}, replacement {replacement:?}"
    );
}
#[test]
fn structural_wire_omits_enum_and_scope_strings_and_resolves_forward_declaration_rows() {
    let wire = fixture("泛型🚀.Owner");
    for text in [
        "class",
        "primitive",
        "variable",
        "array",
        "wildcard",
        "extends",
        "class:泛型🚀.Owner",
    ] {
        assert!(!wire.dictionary.iter().any(|v| v == text));
    }
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let table = Loaded::parse_with_strings(&wire.bytes, b"metadata", &strings).unwrap();
    assert_eq!(table.type_count(), 9);
    assert_eq!(table.type_expr(2).scope, "class:泛型🚀.Owner");
    assert_eq!(
        table.type_expr(3).scope,
        format!("method:泛型🚀.Owner#echo{DESCRIPTOR}")
    );
    assert_eq!(table.type_expr(7).scope, "unresolved:class:泛型🚀.Owner");
    assert_eq!(
        table.type_expr(8).scope,
        format!("unresolved:method:泛型🚀.Owner#echo{DESCRIPTOR}")
    );
    assert!(matches!(table.type_expr(2).scope, Cow::Owned(_)));
    assert!(matches!(table.type_expr(0).scope, Cow::Borrowed("")));
    let id = wire
        .dictionary
        .iter()
        .position(|v| v == "java.util.List")
        .unwrap();
    assert_eq!(table.type_expr(4).name.as_ptr(), strings.get(id).as_ptr());
    assert_eq!(table.render(6), "? extends java.util.List<T>[]");
    assert_eq!(
        table.field_type("泛型🚀.Owner", "field", "Ljava/util/List;"),
        Some(4)
    );
    let method = table
        .method_types("泛型🚀.Owner", "echo", DESCRIPTOR)
        .unwrap();
    assert_eq!(method.parameters, [2, 3]);
    assert_eq!(method.returns, 1);
    let formals = method.type_parameters.iter().collect::<Vec<_>>();
    assert_eq!(formals[0].name, "T");
    assert_eq!(formals[0].scope, table.type_expr(3).scope);
    assert_eq!(formals[0].bounds, [0]);
    assert_eq!(formals[1].scope, "");
    let classes = table.class_entries().collect::<Vec<_>>();
    assert_eq!(classes[0].0, "泛型🚀.Owner");
    assert_eq!(
        classes[0].1.type_parameters.iter().next().unwrap().scope,
        "class:泛型🚀.Owner"
    );
    assert_eq!(table, Loaded::from(table.to_mutable()));
    let own = table.to_mutable();
    for pooled in [false, true] {
        let old = encode(&own, pooled, &[]);
        assert_eq!(table, Loaded::parse(&old.bytes, b"metadata").unwrap());
    }
    let v3 = shared_wire(&own);
    assert_eq!(
        table,
        Loaded::parse_with_strings(
            &v3.bytes,
            b"metadata",
            &StringTable::from_serialized_for_declared_types(&v3.strings).unwrap()
        )
        .unwrap()
    );
}
#[test]
fn structural_wire_rejects_tags_reserved_bytes_and_out_of_range_forward_scopes() {
    let wire = fixture("Owner");
    for (relative, byte) in [(0, 5), (1, 4), (2, 5), (3, 1)] {
        rejects(&wire, wire.types[2] + relative, &[byte]);
    }
    for row in [-2i32, -1, 1, i32::MAX] {
        rejects(&wire, wire.types[2] + 8, &row.to_be_bytes());
    }
    rejects(&wire, wire.types[0] + 8, &0i32.to_be_bytes()); // none must use -1
    rejects(&wire, wire.types[0] + 4, &(-2i32).to_be_bytes());
    rejects(
        &wire,
        wire.types[0] + 4,
        &(wire.dictionary.len() as i32).to_be_bytes(),
    );
    rejects(&wire, wire.types[0] + 4, &(-1i32).to_be_bytes()); // class needs a name
    rejects(&wire, wire.types[2] + 2, &[0]); // variable scope cannot disappear
    for &formal in &wire.formals {
        rejects(&wire, formal + 4, &[5]);
        for reserved in 5..=7 {
            rejects(&wire, formal + reserved, &[1]);
        }
        rejects(&wire, formal + 8, &1i32.to_be_bytes());
    }
    rejects(&wire, wire.types[5] + 16, &5i32.to_be_bytes()); // self-cycle
    rejects(&wire, wire.types[6] + 1, &[0]); // wildcard needs variance
}
#[test]
fn structural_wire_requires_both_digests_and_rejects_every_truncation() {
    let wire = fixture("Owner");
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    assert!(Loaded::parse(&wire.bytes, b"metadata").is_err());
    for offset in [4, 35, 36, 67] {
        rejects(&wire, offset, &[wire.bytes[offset] ^ 1]);
    }
    for length in 0..wire.bytes.len() {
        assert!(
            Loaded::parse_with_strings(&wire.bytes[..length], b"metadata", &strings).is_err(),
            "accepted truncation at {length}"
        );
    }
    let mut trailing = wire.bytes.clone();
    trailing.push(0);
    assert!(Loaded::parse_with_strings(&trailing, b"metadata", &strings).is_err());
    let ordinary = StringTable::from_serialized(&wire.strings).unwrap();
    assert!(Loaded::parse_with_strings(&wire.bytes, b"metadata", &ordinary).is_err());
}
#[test]
fn structural_directory_and_container_loading_selects_verified_shared_strings() {
    let wire = fixture("Owner");
    let root =
        std::env::temp_dir().join(format!("graphite-types-structural-{}", std::process::id()));
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
        assert_eq!(Loaded::load(&source).unwrap().unwrap(), load(&wire));
    }
    std::fs::remove_dir_all(root).unwrap();
}
#[test]
fn structural_projection_budget_counts_synthesized_scope_utf8_bytes() {
    let wire = fixture(&"界".repeat(2000));
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let mut bytes = wire.bytes.clone();
    let arguments = wire.types[4] + 20;
    // The small type DAG expands the same scoped variable 200 times.
    bytes[arguments..arguments + 4].copy_from_slice(&200i32.to_be_bytes());
    bytes.splice(
        arguments + 4..arguments + 8,
        (0..200).flat_map(|_| 2i32.to_be_bytes()),
    );
    assert!(Loaded::parse_with_strings(&bytes, b"metadata", &strings)
        .unwrap_err()
        .0
        .contains("projection budget"));
}
