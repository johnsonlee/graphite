//! A canonical shared dictionary lets a GTY05 table bind nodes by string ID. Every answer
//! must equal the text path's, including the erased spellings a node uses for arrays.
use crate::node::{MethodDesc, Node, NodeKind};
use crate::types::DeclaredTypes;
use crate::strings::StringTable;
use sha2::{Digest, Sha256};

fn int(bytes: &mut Vec<u8>, value: i32) {
    bytes.extend(value.to_be_bytes());
}

struct IdWire {
    bytes: Vec<u8>,
    strings: Vec<u8>,
    names: Vec<String>,
}
impl IdWire {
    fn id(&self, name: &str) -> u32 {
        self.names.iter().position(|v| v == name).unwrap() as u32
    }
}

/// `Owner` declares `List<T> items`, `Cls[][] grid`, `int count` and three `echo` overloads;
/// the dictionary also spells the array types as nodes do, and one class is named `int`.
fn fixture(sorted: bool) -> IdWire {
    let mut names: Vec<String> = [
        "Cls", "Cls[]", "Cls[][]", "Owner", "java.util.List", "int", "int[]", "void", "T", "items",
        "grid", "count", "echo", "wide", "boolean", "missing",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect();
    names.sort_by(|a, b| crate::strings::java_cmp(a, b));
    names.dedup();
    if !sorted {
        names.swap(0, 1);
    }
    let id = |name: &str| names.iter().position(|v| v == name).unwrap() as i32;
    let strings = super::serialized(
        &names
            .iter()
            .map(|s| s.encode_utf16().collect())
            .collect::<Vec<_>>(),
    );
    let mut bytes = 0x47545905i32.to_be_bytes().to_vec();
    bytes.extend(Sha256::digest(b"metadata"));
    bytes.extend(Sha256::digest(&strings));
    // (kind, name, scope tag, scope target, component, arguments)
    let rows: Vec<(i32, i32, i32, i32, i32, Vec<i32>)> = vec![
        (0, id("Cls"), 0, -1, -1, vec![]),            // 0 raw Cls
        (1, id("int"), 0, -1, -1, vec![]),            // 1 int
        (1, id("void"), 0, -1, -1, vec![]),           // 2 void
        (2, -1, 0, -1, 0, vec![]),                    // 3 Cls[]
        (2, -1, 0, -1, 3, vec![]),                    // 4 Cls[][]
        (0, id("java.util.List"), 0, -1, -1, vec![]), // 5 raw List
        (3, id("T"), 1, 0, -1, vec![]),               // 6 class variable T
        (0, id("java.util.List"), 0, -1, -1, vec![6]), // 7 List<T>
        (0, id("Cls"), 0, -1, -1, vec![]),            // 8 duplicate raw Cls at another ID
        (2, -1, 0, -1, 1, vec![]),                    // 9 int[]
        (1, id("boolean"), 0, -1, -1, vec![]),        // 10 boolean
        (0, id("int"), 0, -1, -1, vec![]),            // 11 a class named like a primitive
    ];
    int(&mut bytes, rows.len() as i32);
    for (kind, name, tag, target, component, arguments) in &rows {
        bytes.extend([*kind as u8, 0, *tag as u8, 0]);
        for value in [*name, *target, -1, *component, arguments.len() as i32] {
            int(&mut bytes, value);
        }
        for argument in arguments {
            int(&mut bytes, *argument);
        }
    }
    // Signatures: (Cls[][], int, Cls)Cls; (Cls[], int, Cls)Cls; (Cls[][], int, Cls)void;
    // (int[])boolean through the duplicate raw row; eighteen ints -> void.
    let signatures: Vec<(Vec<i32>, i32)> = vec![
        (vec![4, 1, 8], 0),
        (vec![3, 1, 0], 0),
        (vec![4, 1, 0], 2),
        (vec![9], 10),
        (vec![1; 18], 2),
    ];
    int(&mut bytes, signatures.len() as i32);
    for (parameters, returns) in &signatures {
        int(&mut bytes, parameters.len() as i32);
        for parameter in parameters {
            int(&mut bytes, *parameter);
        }
        int(&mut bytes, *returns);
    }
    // Fields: items -> List<T>, grid (Cls[][]) -> 4, count (int) -> 1, a field typed by the
    // class named int -> 11.
    let fields: Vec<(&str, i32, i32)> = vec![
        ("items", 5, 7),
        ("grid", 4, 4),
        ("count", 1, 1),
        ("wide", 11, 11),
    ];
    int(&mut bytes, fields.len() as i32);
    for (name, raw, value) in &fields {
        for v in [id("Owner"), id(name), *raw, *value] {
            int(&mut bytes, v);
        }
    }
    // Methods: echo with the first three signatures, wide with the last two; all without formals.
    let methods: Vec<(&str, i32, Vec<i32>, i32)> = vec![
        ("echo", 0, vec![4, 1, 0], 0),
        ("echo", 1, vec![3, 1, 0], 0),
        ("echo", 2, vec![4, 1, 0], 2),
        ("wide", 3, vec![9], 10),
        ("wide", 4, vec![1; 18], 2),
    ];
    int(&mut bytes, methods.len() as i32);
    for (name, signature, parameters, returns) in &methods {
        for v in [id("Owner"), id(name), *signature] {
            int(&mut bytes, v);
        }
        int(&mut bytes, parameters.len() as i32);
        for parameter in parameters {
            int(&mut bytes, *parameter);
        }
        int(&mut bytes, *returns);
        int(&mut bytes, 0);
    }
    int(&mut bytes, 1);
    int(&mut bytes, id("Owner"));
    int(&mut bytes, 1);
    int(&mut bytes, id("T"));
    bytes.extend([1, 0, 0, 0]);
    int(&mut bytes, 0);
    int(&mut bytes, 0);
    int(&mut bytes, -1);
    int(&mut bytes, 0);
    IdWire {
        bytes,
        strings,
        names,
    }
}

fn load(wire: &IdWire) -> (DeclaredTypes, StringTable) {
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let table = DeclaredTypes::parse_with_strings(&wire.bytes, b"metadata", &strings).unwrap();
    (table, strings)
}

fn field(wire: &IdWire, name: &str, type_name: &str) -> Node {
    Node {
        id: 1,
        kind: NodeKind::Field {
            declaring_class: wire.id("Owner"),
            name: wire.id(name),
            field_type: wire.id(type_name),
            is_static: false,
        },
    }
}

fn method(wire: &IdWire, name: &str, parameters: &[&str], returns: &str) -> MethodDesc {
    MethodDesc {
        declaring_class: wire.id("Owner"),
        name: wire.id(name),
        parameter_types: parameters.iter().map(|p| wire.id(p)).collect(),
        return_type: wire.id(returns),
    }
}

fn parameter(method: MethodDesc, index: i32, param_type: &str, wire: &IdWire) -> Node {
    Node {
        id: 2,
        kind: NodeKind::Parameter {
            index,
            param_type: wire.id(param_type),
            method,
        },
    }
}

fn returns(method: MethodDesc) -> Node {
    Node {
        id: 3,
        kind: NodeKind::Return {
            method,
            actual_type: None,
        },
    }
}

#[test]
fn canonical_dictionary_binds_fields_parameters_and_returns_by_id_like_text() {
    let wire = fixture(true);
    let (table, strings) = load(&wire);
    assert!(strings.canonical_dictionary());
    // Fields, including the array spelling a node uses and the class spelled like a primitive.
    assert_eq!(table.node_type_id(&field(&wire, "items", "java.util.List"), &strings), Some(7));
    assert_eq!(table.node_type_id(&field(&wire, "grid", "Cls[][]"), &strings), Some(4));
    assert_eq!(table.node_type_id(&field(&wire, "count", "int"), &strings), Some(1));
    assert_eq!(table.field_type("Owner", "count", "I"), Some(1));
    // `int` spells the primitive; the class named `int` is reachable by text only.
    assert_eq!(table.node_type_id(&field(&wire, "wide", "int"), &strings), None);
    assert_eq!(table.field_type("Owner", "wide", "Lint;"), Some(11));
    assert_eq!(table.node_type_id(&field(&wire, "items", "T"), &strings), None);
    assert_eq!(table.node_type_id(&field(&wire, "grid", "Cls"), &strings), None);
    // Methods: the duplicate raw row behind signature 0 still binds `Cls` as a parameter.
    let echo = method(&wire, "echo", &["Cls[][]", "int", "Cls"], "Cls");
    let view = table.method(&echo, &strings).unwrap();
    assert_eq!((view.parameters, view.returns), (&[4usize, 1, 0][..], 0));
    assert_eq!(
        table.node_type_id(&parameter(echo.clone(), 1, "int", &wire), &strings),
        Some(1)
    );
    assert_eq!(table.node_type_id(&returns(echo.clone()), &strings), Some(0));
    let void = method(&wire, "echo", &["Cls[][]", "int", "Cls"], "void");
    assert_eq!(table.node_type_id(&returns(void), &strings), Some(2));
    // A node spelling of one dimension names raw row 3, which no `echo` signature holds:
    // the ID path loses no more than the text path does.
    // The one-dimension spelling a node carries for `Cls[]` binds the second overload.
    assert_eq!(table.method(&method(&wire, "echo", &["Cls[]", "int", "Cls"], "Cls"), &strings).unwrap().parameters, &[3usize, 1, 0][..]);
    assert!(table.method(&method(&wire, "echo", &["Cls", "int", "Cls"], "Cls"), &strings).is_none());
    assert!(table.method(&method(&wire, "echo", &["Cls[][]", "int"], "Cls"), &strings).is_none());
    assert!(table.method(&method(&wire, "echo", &["Cls[][]", "int", "T"], "Cls"), &strings).is_none());
    assert!(table.method(&method(&wire, "missing", &["Cls[][]", "int", "Cls"], "Cls"), &strings).is_none());
    // One parameter, an array of a primitive, and more parameters than the fixed buffer.
    let narrow = method(&wire, "wide", &["int[]"], "boolean");
    assert_eq!(table.method(&narrow, &strings).unwrap().returns, 10);
    let eighteen = vec!["int"; 18];
    let wide = method(&wire, "wide", &eighteen, "void");
    assert_eq!(table.method(&wide, &strings).unwrap().parameters.len(), 18);
    assert_eq!(table.node_type_id(&parameter(wide.clone(), 17, "int", &wire), &strings), Some(1));
    assert_eq!(table.node_type_id(&parameter(wide, 18, "int", &wire), &strings), None);
    // Text lookups keep answering through the IDs the texts stand for.
    assert_eq!(table.field_type("Owner", "grid", "[[LCls;"), Some(4));
    assert_eq!(table.field_type("Owner", "grid", "[LCls;"), None);
    assert_eq!(table.field_type("Nobody", "grid", "[[LCls;"), None);
    assert_eq!(table.method_types("Owner", "echo", "([[LCls;ILCls;)LCls;").unwrap().returns, 0);
    assert_eq!(table.method_types("Owner", "echo", "([[LCls;ILCls;)V").unwrap().returns, 2);
    assert!(table.method_types("Owner", "echo", "([[LCls;ILCls;").is_none());
    assert!(table.method_types("Owner", "echo", "([[LCls;ILCls;)LCls;;").is_none());
    assert!(table.method_types("Owner", "echo", "([[LCls;QLCls;)LCls;").is_none());
    assert!(table.method_types("Owner", "echo", "([[LCls;ILCls;)LMissing;").is_none());
}

#[test]
fn an_unsorted_dictionary_keeps_text_keys_and_answers_the_same() {
    let wire = fixture(false);
    let (table, strings) = load(&wire);
    assert!(!strings.canonical_dictionary());
    assert_eq!(table.node_type_id(&field(&wire, "items", "java.util.List"), &strings), Some(7));
    assert_eq!(table.node_type_id(&field(&wire, "grid", "Cls[][]"), &strings), Some(4));
    let echo = method(&wire, "echo", &["Cls[][]", "int", "Cls"], "Cls");
    assert_eq!(table.node_type_id(&returns(echo), &strings), Some(0));
    assert_eq!(table.field_type("Owner", "grid", "[[LCls;"), Some(4));
}

#[test]
fn duplicate_members_through_distinct_equal_rows_are_still_rejected() {
    let wire = fixture(true);
    let strings = StringTable::from_serialized_for_declared_types(&wire.strings).unwrap();
    let key = |name: &str, raw: i32, value: i32| {
        let mut key = Vec::new();
        for v in [wire.id("Owner") as i32, wire.id(name) as i32, raw, value] {
            int(&mut key, v);
        }
        key
    };
    let find = |bytes: &[u8], needle: &[u8]| bytes.windows(needle.len()).position(|w| w == needle).unwrap();
    // Re-key `grid` by raw row 0 (`LCls;`) and `count` as `grid` by the duplicate row 8, which
    // spells the same text: equal by ID through the canonical row, so still a duplicate.
    let mut bytes = wire.bytes.clone();
    let grid = find(&bytes, &key("grid", 4, 4));
    bytes[grid + 8..grid + 12].copy_from_slice(&0i32.to_be_bytes());
    let count = find(&bytes, &key("count", 1, 1));
    bytes[count + 4..count + 8].copy_from_slice(&(wire.id("grid") as i32).to_be_bytes());
    bytes[count + 8..count + 12].copy_from_slice(&8i32.to_be_bytes());
    let error = DeclaredTypes::parse_with_strings(&bytes, b"metadata", &strings).unwrap_err();
    assert!(error.0.contains("duplicate field"), "{error}");
}
