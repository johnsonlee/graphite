use super::*;

fn utf(out: &mut Vec<u8>, value: &str) {
    out.extend((value.len() as u16).to_be_bytes());
    out.extend(value.as_bytes());
}

fn reference(out: &mut Vec<u8>, handle: u32) {
    out.push(TC_REFERENCE);
    out.extend(handle.to_be_bytes());
}

fn header(out: &mut Vec<u8>, name: &str, fields: u16) {
    out.push(TC_CLASSDESC);
    utf(out, name);
    out.extend(0i64.to_be_bytes());
    out.push(2);
    out.extend(fields.to_be_bytes());
}

#[test]
fn field_signatures_and_class_descriptors_keep_reference_identity() {
    let mut data = Vec::new();
    header(&mut data, "Fields", 2);
    data.push(b'L');
    utf(&mut data, "first");
    data.push(TC_STRING);
    utf(&mut data, "Ljava/lang/Object;");
    data.push(b'L');
    utf(&mut data, "second");
    reference(&mut data, BASE_HANDLE + 1);
    data.extend([TC_ENDBLOCKDATA, TC_NULL]);
    reference(&mut data, BASE_HANDLE);
    let mut parser = Parser {
        data: &data,
        pos: 0,
        handles: Vec::new(),
        depth: 0,
    };
    let first = parser.class_desc().unwrap().unwrap();
    let second = parser.class_desc().unwrap().unwrap();
    assert!(Arc::ptr_eq(&first, &second));
    let a = first.fields[0].class_name.as_ref().unwrap();
    let b = first.fields[1].class_name.as_ref().unwrap();
    assert_eq!(a.as_ref(), "Ljava/lang/Object;");
    assert!(Arc::ptr_eq(a, b));
    let Handle::Value(original) = parser.handle(BASE_HANDLE + 1).unwrap() else {
        panic!("expected type-name string")
    };
    let Value::Str(original) = original.resolved() else {
        panic!("expected type-name string")
    };
    assert!(Arc::ptr_eq(a, original));
}

#[test]
fn instances_share_descriptor_names_and_field_keys_without_changing_values() {
    let mut data = vec![0xac, 0xed, 0, 5, TC_ARRAY];
    header(&mut data, "[Ljava/lang/Object;", 0);
    data.extend([TC_ENDBLOCKDATA, TC_NULL]);
    data.extend(2i32.to_be_bytes());
    data.push(TC_OBJECT);
    header(&mut data, "Record", 1);
    data.push(b'I');
    utf(&mut data, "value");
    data.extend([TC_ENDBLOCKDATA, TC_NULL]);
    data.extend(7i32.to_be_bytes());
    data.push(TC_OBJECT);
    reference(&mut data, BASE_HANDLE + 2);
    data.extend(9i32.to_be_bytes());
    let Value::ObjArray(instances) = read_object(&data).unwrap() else {
        panic!("expected array")
    };
    let (
        Value::Object {
            class: a,
            fields: af,
        },
        Value::Object {
            class: b,
            fields: bf,
        },
    ) = (&instances[0], &instances[1])
    else {
        panic!("expected objects")
    };
    assert!(Arc::ptr_eq(a, b));
    assert_eq!(a.as_ref(), "Record");
    assert!(Arc::ptr_eq(
        af.keys().next().unwrap(),
        bf.keys().next().unwrap()
    ));
    assert!(matches!(instances[0].field("value"), Some(Value::Int(7))));
    assert!(matches!(instances[1].field("value"), Some(Value::Int(9))));
}
