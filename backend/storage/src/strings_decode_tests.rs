//! Independent Java serialization records exercise the public reader, including malformed input.
use super::*;

fn utf(out: &mut Vec<u8>, value: &str) {
    out.extend((value.len() as u16).to_be_bytes());
    out.extend(value.as_bytes());
}

fn descriptor(out: &mut Vec<u8>, name: &str, fields: &[(u8, &str)]) {
    out.push(0x72);
    utf(out, name);
    out.extend(0i64.to_be_bytes());
    out.push(2);
    out.extend((fields.len() as u16).to_be_bytes());
    for (kind, name) in fields {
        out.push(*kind);
        utf(out, name);
        if matches!(kind, b'L' | b'[') {
            out.push(0x74);
            utf(out, "Ljava/lang/Object;");
        }
    }
    out.extend([0x78, 0x70]);
}

fn encoded_array(class: &str, count: i32, content: &[u8]) -> Vec<u8> {
    let mut out = vec![0x75];
    descriptor(&mut out, class, &[]);
    out.extend(count.to_be_bytes());
    out.extend(content);
    out
}

fn wire(utf8: bool, count: i32, ratio: i32, array: &[u8]) -> Vec<u8> {
    let mut out = vec![0xac, 0xed, 0, 5, 0x73];
    descriptor(
        &mut out,
        "it.unimi.dsi.util.FrontCodedStringList",
        &[
            (b'Z', "utf8"),
            (
                b'L',
                if utf8 {
                    "byteFrontCodedList"
                } else {
                    "charFrontCodedList"
                },
            ),
        ],
    );
    out.extend([u8::from(utf8), 0x73]);
    descriptor(
        &mut out,
        if utf8 {
            "it.unimi.dsi.fastutil.bytes.ByteArrayFrontCodedList"
        } else {
            "it.unimi.dsi.fastutil.chars.CharArrayFrontCodedList"
        },
        &[(b'I', "n"), (b'I', "ratio"), (b'[', "array")],
    );
    out.extend(count.to_be_bytes());
    out.extend(ratio.to_be_bytes());
    out.extend(array);
    out
}

fn chars(values: &[u16]) -> Vec<u8> {
    encoded_array(
        "[C",
        values.len() as i32,
        &values
            .iter()
            .flat_map(|x| x.to_be_bytes())
            .collect::<Vec<_>>(),
    )
}
fn bytes(values: &[u8]) -> Vec<u8> {
    encoded_array("[B", values.len() as i32, values)
}

#[test]
fn corrupt_front_coding_returns_errors_in_both_load_modes() {
    let cases = [
        wire(false, -1, 1, &chars(&[])),
        wire(false, i32::MAX, 1, &chars(&[])),
        wire(false, 0, 0, &chars(&[])),
        wire(false, 0, -1, &chars(&[])),
        wire(false, 1, 1, &chars(&[0x8000])),
        wire(false, 1, 1, &chars(&[4, 97])),
        wire(false, 2, 2, &chars(&[1, 97, 1, 2, 98])),
        wire(true, 1, 1, &bytes(&[0xfe])),
        wire(true, 1, 1, &bytes(&[4, b'a'])),
        wire(true, 2, 2, &bytes(&[1, b'a', 1, 2, b'b'])),
        wire(true, 1, 1, &bytes(&[0x80, 0x80, 0x80, 0x80, 0x80, 0])),
        wire(false, 1, 1, &encoded_array("[C", -1, &[])),
        wire(false, 1, 1, &encoded_array("[C", i32::MAX, &[])),
        wire(
            false,
            1,
            1,
            &encoded_array("[Ljava/lang/Object;", i32::MAX, &[]),
        ),
    ];
    for (index, data) in cases.iter().enumerate() {
        assert!(
            StringTable::from_serialized(data).is_err(),
            "ordinary case {index}"
        );
        assert!(
            StringTable::from_serialized_for_declared_types(data).is_err(),
            "verified case {index}"
        );
    }
}

#[test]
fn valid_fastutil_multibyte_lengths_and_prefixes_keep_their_exact_text() {
    // ByteArrayFrontCodedList.writeInt writes -(digit + 1) for continuation digits.
    let mut flat = vec![0xfe, 2]; // leader length 130
    flat.extend(vec![b'a'; 130]);
    flat.extend([1, 0xfe, 1, b'b']); // suffix 1, common prefix 129
    let data = wire(true, 2, 2, &bytes(&flat));
    for table in [
        StringTable::from_serialized(&data).unwrap(),
        StringTable::from_serialized_for_declared_types(&data).unwrap(),
    ] {
        assert_eq!(table.len(), 2);
        assert_eq!(table.get(0), "a".repeat(130));
        assert_eq!(table.get(1), format!("{}b", "a".repeat(129)));
    }
    let mut units = vec![0x8000, 0x8000]; // UTF-16 leader length 32768
    units.extend(vec![b'z' as u16; 32768]);
    let table = StringTable::from_serialized(&wire(false, 1, 1, &chars(&units))).unwrap();
    assert_eq!(table.get(0), "z".repeat(32768));
}

#[test]
fn truncated_serialized_arrays_and_reset_recursion_are_checked() {
    for class in ["[C", "[I", "[J", "[D", "[Ljava/lang/Object;"] {
        for length in [-1, i32::MAX] {
            let mut stream = vec![0xac, 0xed, 0, 5];
            stream.extend(encoded_array(class, length, &[]));
            assert!(read_object(&stream).is_err(), "{class}, length {length}");
        }
    }
    let mut resets = vec![0xac, 0xed, 0, 5];
    resets.extend(vec![0x79; 256]);
    resets.push(0x70);
    assert!(read_object(&resets).is_err());
    // Reset inside a class annotation invalidates the reserved handle. It must not index
    // into a cleared handle vector after the annotation has finished.
    let mut reset_class = vec![0xac, 0xed, 0, 5, 0x73, 0x72];
    utf(&mut reset_class, "Broken");
    reset_class.extend(0i64.to_be_bytes());
    reset_class.extend([2, 0, 0, 0x79, 0x70, 0x78, 0x70]);
    assert!(read_object(&reset_class).is_err());
}

fn reference(out: &mut Vec<u8>, handle: u32) {
    out.push(0x71);
    out.extend(handle.to_be_bytes());
}

fn reference_ladder(levels: i32) -> Vec<u8> {
    let base = 0x7e0000;
    let mut out = vec![0xac, 0xed, 0, 5, 0x75];
    descriptor(&mut out, "[Ljava/lang/Object;", &[]);
    out.extend(levels.to_be_bytes());
    for index in 0..levels {
        out.push(0x75);
        reference(&mut out, base);
        out.extend((if index == 0 { 0i32 } else { 2i32 }).to_be_bytes());
        if index > 0 {
            reference(&mut out, base + index as u32 + 1);
            reference(&mut out, base + index as u32 + 1);
        }
    }
    out
}

#[test]
fn repeated_object_references_retain_aliases_instead_of_expanding_a_tree() {
    let data = reference_ladder(12);
    assert_eq!(data.len(), 274);
    let root = read_object(&data).unwrap();
    let Value::ObjArray(arrays) = &root else {
        panic!("expected root array")
    };
    assert_eq!(arrays.len(), 12);
    for array in &arrays[1..] {
        let Value::ObjArray(pair) = array else {
            panic!("expected child array")
        };
        let (Value::Reference(left), Value::Reference(right)) = (&pair[0], &pair[1]) else {
            panic!("expected shared aliases")
        };
        assert!(Arc::ptr_eq(left, right));
    }
    // Count unique physical Values, without recursively expanding the two aliases. The old
    // deep-clone reader materialized 8179 nodes for these same 274 bytes.
    let mut pending = vec![&root];
    let mut seen = std::collections::HashSet::new();
    while let Some(value) = pending.pop() {
        if !seen.insert(value as *const Value) {
            continue;
        }
        match value {
            Value::Reference(target) => pending.push(target),
            Value::ObjArray(values) => pending.extend(values),
            _ => {}
        }
    }
    assert_eq!(seen.len(), 66);
}

#[test]
fn referenced_graph_depth_is_bounded_even_for_shallow_serialized_arrays() {
    // The serialized syntax has only a root array and sibling arrays. Their aliases still
    // form a deep object graph, whose eventual Arc destruction must remain stack-bounded.
    assert!(read_object(&reference_ladder(127)).is_ok());
    let error = read_object(&reference_ladder(128)).unwrap_err();
    assert!(error
        .to_string()
        .contains("object graph nesting exceeds 128"));
}

#[test]
fn class_field_type_names_and_field_values_accept_java_references() {
    let base = 0x7e0000;
    let mut out = vec![0xac, 0xed, 0, 5, 0x73, 0x72];
    utf(&mut out, "Fields");
    out.extend(0i64.to_be_bytes());
    out.extend([2, 0, 2]);
    out.push(b'L');
    utf(&mut out, "first");
    out.push(0x74);
    utf(&mut out, "Ljava/lang/Object;");
    out.push(b'L');
    utf(&mut out, "second");
    reference(&mut out, base + 1); // shared field type-name string
    out.extend([0x78, 0x70]);
    out.push(0x74);
    utf(&mut out, "value");
    reference(&mut out, base + 3); // shared field value
    let root = read_object(&out).unwrap();
    assert!(matches!(root.field("first"), Some(Value::Str(value)) if value.as_ref() == "value"));
    assert!(matches!(root.field("second"), Some(Value::Str(value)) if value.as_ref() == "value"));
}

#[test]
fn enum_names_and_enum_values_accept_java_references() {
    let base = 0x7e0000;
    let mut out = vec![0xac, 0xed, 0, 5, 0x75];
    descriptor(&mut out, "[Ljava/lang/Object;", &[]);
    out.extend(3i32.to_be_bytes());
    out.push(0x74);
    utf(&mut out, "VALUE");
    out.push(0x7e);
    descriptor(&mut out, "ExampleEnum", &[]);
    reference(&mut out, base + 2); // enum name aliases the first array entry
    reference(&mut out, base + 4); // the enum itself is also shared
    let root = read_object(&out).unwrap();
    let Value::ObjArray(values) = root else {
        panic!("expected array")
    };
    assert_eq!(values.len(), 3);
    assert!(values
        .iter()
        .all(|value| matches!(value.resolved(), Value::Str(name) if name.as_ref() == "VALUE")));
}

#[test]
fn string_tables_decode_shared_primitive_array_segments_without_nested_expansion() {
    // wire() assigns the first primitive segment handle after its outer/list/array descriptors.
    for (utf8, segment) in [(true, bytes(&[1, b'a'])), (false, chars(&[1, '中' as u16]))] {
        let mut content = segment;
        reference(&mut content, 0x7e0009);
        let array = encoded_array("[Ljava/lang/Object;", 2, &content);
        let data = wire(utf8, 2, 1, &array);
        for table in [
            StringTable::from_serialized(&data).unwrap(),
            StringTable::from_serialized_for_declared_types(&data).unwrap(),
        ] {
            assert_eq!(table.len(), 2);
            assert_eq!(table.get(0), if utf8 { "a" } else { "中" });
            assert_eq!(table.get(0), table.get(1));
        }
    }
    // A segment must resolve directly to the expected primitive array; an object-array graph
    // is a type error, not something to recursively concatenate or expand.
    let array = encoded_array(
        "[Ljava/lang/Object;",
        1,
        &encoded_array("[Ljava/lang/Object;", 0, &[]),
    );
    assert!(StringTable::from_serialized(&wire(true, 1, 1, &array)).is_err());
}

fn referenced_superclasses(levels: i32) -> Vec<u8> {
    let base = 0x7e0000;
    let mut out = vec![0xac, 0xed, 0, 5, 0x75];
    descriptor(&mut out, "[Ljava/lang/Object;", &[]);
    out.extend(levels.to_be_bytes());
    for index in 0..levels {
        out.extend([0x76, 0x72]); // a Class value followed by its new descriptor
        utf(&mut out, &format!("Class{index}"));
        out.extend(0i64.to_be_bytes());
        out.extend([2, 0, 0, 0x78]);
        if index == 0 {
            out.push(0x70);
        } else {
            reference(&mut out, base + 2 + (index as u32 - 1) * 2);
        }
    }
    out
}

#[test]
fn superclass_references_cannot_bypass_the_owned_chain_depth_limit() {
    assert!(read_object(&referenced_superclasses(128)).is_ok());
    let Err(error) = read_object(&referenced_superclasses(129)) else {
        panic!("unbounded superclass chain accepted")
    };
    assert!(error.to_string().contains("superclass nesting exceeds 128"));
}
