use super::*;
use std::collections::hash_map::DefaultHasher;
use std::hash::BuildHasherDefault;

struct Wire {
    bytes: Vec<u8>,
    text_offsets: Vec<usize>,
    reference_offsets: Vec<usize>,
    strings: usize,
    sections: Vec<(&'static str, usize, usize, usize)>,
}

struct Encoder {
    bytes: Vec<u8>,
    dictionary: IndexSet<Arc<str>>,
    pooled: bool,
    text_offsets: Vec<usize>,
    reference_offsets: Vec<usize>,
}
impl Encoder {
    fn int(&mut self, value: i32) {
        self.bytes.extend(value.to_be_bytes());
    }
    fn text(&mut self, value: &Arc<str>) {
        self.text_offsets.push(self.bytes.len());
        if self.pooled {
            let (id, _) = self.dictionary.insert_full(value.clone());
            self.int(id as i32);
        } else {
            self.int(value.len() as i32);
            self.bytes.extend(value.as_bytes());
        }
    }
    fn reference(&mut self, value: i32) {
        self.reference_offsets.push(self.bytes.len());
        self.int(value);
    }
    fn ids(&mut self, ids: &[usize]) {
        self.int(ids.len() as i32);
        for id in ids {
            self.reference(*id as i32);
        }
    }
    fn parameters(&mut self, parameters: &[TypeParameter]) {
        self.int(parameters.len() as i32);
        for parameter in parameters {
            self.text(&parameter.name);
            self.text(&parameter.scope);
            self.ids(&parameter.bounds);
        }
    }
    fn key(&mut self, key: &MemberKey) {
        for text in [&key.0, &key.1, &key.2] {
            self.text(text);
        }
    }
}

// An independent test-only writer for both documented wire formats.
fn encode(table: &DeclaredTypes, pooled: bool, extra_dictionary: &[&[u8]]) -> Wire {
    let mut body = Encoder {
        bytes: vec![],
        dictionary: IndexSet::new(),
        pooled,
        text_offsets: vec![],
        reference_offsets: vec![],
    };
    body.int(table.types.len() as i32);
    for ty in &table.types {
        for text in [&ty.kind, &ty.name, &ty.scope] {
            body.text(text);
        }
        body.reference(ty.owner.map_or(-1, |id| id as i32));
        body.reference(ty.component.map_or(-1, |id| id as i32));
        body.text(&ty.variance);
        body.ids(&ty.arguments);
    }
    let mut sections = Vec::new();
    let count_offset = body.bytes.len();
    body.int(table.fields.len() as i32);
    for (key, id) in &table.fields {
        let start = body.bytes.len();
        body.key(key);
        body.reference(*id as i32);
        if sections.is_empty() {
            sections.push(("field", count_offset, start, body.bytes.len()));
        }
    }
    let count_offset = body.bytes.len();
    body.int(table.methods.len() as i32);
    for (key, method) in &table.methods {
        let start = body.bytes.len();
        body.key(key);
        body.ids(&method.parameters);
        body.reference(method.returns as i32);
        body.parameters(&method.type_parameters);
        if sections.len() == 1 {
            sections.push(("method", count_offset, start, body.bytes.len()));
        }
    }
    let count_offset = body.bytes.len();
    body.int(table.classes.len() as i32);
    for (name, class) in &table.classes {
        let start = body.bytes.len();
        body.text(name);
        body.parameters(&class.type_parameters);
        body.reference(class.superclass.map_or(-1, |id| id as i32));
        body.ids(&class.interfaces);
        if sections.len() == 2 {
            sections.push(("class", count_offset, start, body.bytes.len()));
        }
    }
    let mut bytes = (if pooled { 0x47545902i32 } else { 0x47545901i32 })
        .to_be_bytes()
        .to_vec();
    bytes.extend(Sha256::digest(b"metadata"));
    if pooled {
        bytes.extend(((body.dictionary.len() + extra_dictionary.len()) as i32).to_be_bytes());
        for text in body
            .dictionary
            .iter()
            .map(|s| s.as_bytes())
            .chain(extra_dictionary.iter().copied())
        {
            bytes.extend((text.len() as i32).to_be_bytes());
            bytes.extend(text);
        }
    }
    let prefix = bytes.len();
    bytes.extend(body.bytes);
    Wire {
        bytes,
        text_offsets: body.text_offsets.into_iter().map(|i| prefix + i).collect(),
        reference_offsets: body
            .reference_offsets
            .into_iter()
            .map(|i| prefix + i)
            .collect(),
        strings: body.dictionary.len() + extra_dictionary.len(),
        sections: sections
            .into_iter()
            .map(|(name, count, start, end)| (name, prefix + count, prefix + start, prefix + end))
            .collect(),
    }
}

fn full_table() -> DeclaredTypes {
    let mut table = DeclaredTypes::parse(&super::tests::fixture(), b"metadata").unwrap();
    table.types.push(TypeExpr {
        kind: "class".into(),
        name: "java.util.List$类型🚀".into(),
        scope: "".into(),
        owner: Some(0),
        component: None,
        variance: "".into(),
        arguments: vec![1],
    });
    table.types.push(TypeExpr {
        kind: "primitive".into(),
        name: "void".into(),
        scope: "".into(),
        owner: None,
        component: None,
        variance: "".into(),
        arguments: vec![],
    });
    table
        .methods
        .values_mut()
        .next()
        .unwrap()
        .type_parameters
        .push(TypeParameter {
            name: "U".into(),
            scope: "method:Example#method".into(),
            bounds: vec![4],
        });
    for (descriptor, returns) in [("()Ljava/util/List;", 0), ("()Ljava/lang/Comparable;", 4)] {
        table.methods.insert(
            ("Example".into(), "bridge".into(), descriptor.into()),
            MethodTypes {
                parameters: vec![],
                returns,
                type_parameters: vec![],
            },
        );
    }
    table.methods.insert(
        ("Example".into(), "<init>".into(), "()V".into()),
        MethodTypes {
            parameters: vec![],
            returns: 6,
            type_parameters: vec![],
        },
    );
    table.fields.insert(
        (
            "类型🚀".into(),
            "nested".into(),
            "Ljava/util/List$类型🚀;".into(),
        ),
        5,
    );
    table.classes.insert(
        "类型🚀".into(),
        ClassTypes {
            type_parameters: vec![],
            superclass: Some(0),
            interfaces: vec![4],
        },
    );
    table
}

#[test]
fn v1_v2_complete_tables_renders_and_shared_text_identity_agree() {
    let expected = full_table();
    for pooled in [false, true] {
        let wire = encode(&expected, pooled, &[]);
        let actual = DeclaredTypes::parse(&wire.bytes, b"metadata").unwrap();
        assert_eq!(actual, expected);
        assert_eq!(
            actual.fields.keys().collect::<Vec<_>>(),
            expected.fields.keys().collect::<Vec<_>>()
        );
        for id in 0..expected.types.len() {
            assert_eq!(actual.render(id), expected.render(id));
        }
        assert_eq!(actual.render(5), "java.util.List<? super T>.类型🚀<T>");
        assert_eq!(
            actual
                .method_types("Example", "bridge", "()Ljava/util/List;")
                .unwrap()
                .returns,
            0
        );
        assert_eq!(
            actual
                .method_types("Example", "bridge", "()Ljava/lang/Comparable;")
                .unwrap()
                .returns,
            4
        );
        assert_eq!(
            actual
                .method_types("Example", "<init>", "()V")
                .unwrap()
                .returns,
            6
        );
        assert_eq!(
            actual.field_type("Example", "first", "Ljava/util/List;"),
            Some(0)
        );
        assert_eq!(
            actual.field_type("Example", "first", "Ljava/lang/Object;"),
            None
        );
        let first = actual.fields.first().unwrap().0;
        let class_name = actual.classes.first().unwrap().0;
        assert!(Arc::ptr_eq(&first.0, class_name));
        assert!(Arc::ptr_eq(
            &actual.types[1].scope,
            &actual.classes["Example"].type_parameters[0].scope
        ));
        assert!(Arc::ptr_eq(
            &actual.types[0].scope,
            &actual.types[0].variance
        ));
    }
}

#[test]
fn dictionaries_validate_unused_text_and_distinguish_equal_hash_names() {
    let table = full_table();
    let valid = encode(
        &table,
        true,
        &[b"Aa", b"BB", "é".as_bytes(), "e\u{301}".as_bytes()],
    );
    assert_eq!(
        DeclaredTypes::parse(&valid.bytes, b"metadata").unwrap(),
        table
    );
    for extra in [
        vec![b"".as_slice()],
        vec![b"unused".as_slice(), b"unused".as_slice()],
    ] {
        let wire = encode(&table, true, &extra);
        assert!(DeclaredTypes::parse(&wire.bytes, b"metadata")
            .unwrap_err()
            .0
            .contains("duplicate dictionary"));
    }
    for bad in [
        &[0xff][..],
        &[0xc0, 0x80],
        &[0xed, 0xa0, 0x80],
        &[0xf4, 0x90, 0x80, 0x80],
        &[0xe2, 0x82],
    ] {
        let wire = encode(&table, true, &[bad]);
        assert!(DeclaredTypes::parse(&wire.bytes, b"metadata")
            .unwrap_err()
            .0
            .contains("UTF-8"));
    }
}

#[test]
fn all_string_id_positions_and_all_partial_inputs_are_checked() {
    let table = full_table();
    let wire = encode(&table, true, &[]);
    for offset in &wire.text_offsets {
        for value in [-1, wire.strings as i32, i32::MAX] {
            let mut bytes = wire.bytes.clone();
            bytes[*offset..*offset + 4].copy_from_slice(&value.to_be_bytes());
            assert!(
                DeclaredTypes::parse(&bytes, b"metadata")
                    .unwrap_err()
                    .0
                    .contains("string ID"),
                "text {offset}"
            );
        }
    }
    for pooled in [false, true] {
        let wire = encode(&table, pooled, &[]);
        for end in 0..wire.bytes.len() {
            assert!(
                DeclaredTypes::parse(&wire.bytes[..end], b"metadata").is_err(),
                "version {pooled}, prefix {end}"
            );
        }
        for offset in &wire.reference_offsets {
            let mut bytes = wire.bytes.clone();
            bytes[*offset..*offset + 4].copy_from_slice(&(table.types.len() as i32).to_be_bytes());
            assert!(DeclaredTypes::parse(&bytes, b"metadata")
                .unwrap_err()
                .0
                .contains("reference"));
        }
        let mut trailing = wire.bytes;
        trailing.push(0);
        assert!(DeclaredTypes::parse(&trailing, b"metadata")
            .unwrap_err()
            .0
            .contains("trailing"));
    }
    for offset in [36, 40] {
        for value in [-1i32, i32::MAX] {
            let mut bytes = wire.bytes.clone();
            bytes[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
            assert!(DeclaredTypes::parse(&bytes, b"metadata").is_err());
        }
    }
}

#[test]
fn both_versions_preserve_cycle_shape_depth_and_projection_budget_validation() {
    for pooled in [false, true] {
        let mut cycle = full_table();
        cycle.types[0].arguments = vec![0];
        assert!(
            DeclaredTypes::parse(&encode(&cycle, pooled, &[]).bytes, b"metadata")
                .unwrap_err()
                .0
                .contains("cyclic")
        );
        let mut shape = full_table();
        shape.types[1].scope = "".into();
        assert!(
            DeclaredTypes::parse(&encode(&shape, pooled, &[]).bytes, b"metadata")
                .unwrap_err()
                .0
                .contains("invalid variable")
        );
        let mut depth = full_table();
        for _ in 0..257 {
            depth.types.push(TypeExpr {
                kind: "array".into(),
                name: "".into(),
                scope: "".into(),
                owner: None,
                component: Some(depth.types.len() - 1),
                variance: "".into(),
                arguments: vec![],
            });
        }
        assert!(
            DeclaredTypes::parse(&encode(&depth, pooled, &[]).bytes, b"metadata")
                .unwrap_err()
                .0
                .contains("nesting")
        );
        let mut dag = full_table();
        for _ in 0..18 {
            let previous = dag.types.len() - 1;
            dag.types.push(TypeExpr {
                kind: "class".into(),
                name: "Pair".into(),
                scope: "".into(),
                owner: None,
                component: None,
                variance: "".into(),
                arguments: vec![previous, previous],
            });
        }
        assert!(
            DeclaredTypes::parse(&encode(&dag, pooled, &[]).bytes, b"metadata")
                .unwrap_err()
                .0
                .contains("projection budget")
        );
    }
}

#[test]
fn borrowed_member_hash_is_identical_and_collision_lookup_compares_every_field() {
    #[derive(Default)]
    struct CollisionHasher;
    impl Hasher for CollisionHasher {
        fn write(&mut self, _: &[u8]) {}
        fn finish(&self) -> u64 {
            1
        }
    }
    let mut map = IndexMap::<MemberKey, usize, BuildHasherDefault<CollisionHasher>>::default();
    for (i, name) in ["", "Aa", "BB", "泛型🚀\0"].iter().enumerate() {
        let key: MemberKey = ("Owner".repeat(40).into(), (*name).into(), "()V".into());
        let borrowed = BorrowedMemberKey(key.0.as_ref(), key.1.as_ref(), key.2.as_ref());
        let mut owned_hash = DefaultHasher::new();
        let mut borrowed_hash = DefaultHasher::new();
        key.hash(&mut owned_hash);
        borrowed.hash(&mut borrowed_hash);
        assert_eq!(owned_hash.finish(), borrowed_hash.finish());
        map.insert(key, i);
    }
    for (i, name) in ["", "Aa", "BB", "泛型🚀\0"].iter().enumerate() {
        assert_eq!(
            map.get(&BorrowedMemberKey(&"Owner".repeat(40), name, "()V")),
            Some(&i)
        );
    }
    assert_eq!(map.get(&BorrowedMemberKey("Other", "Aa", "()V")), None);
    assert_eq!(
        map.get(&BorrowedMemberKey(&"Owner".repeat(40), "Aa", "()I")),
        None
    );
}

#[test]
fn independent_tables_keep_pools_graph_local_and_partial_members_exact() {
    let expected = full_table();
    let first = DeclaredTypes::parse(&encode(&expected, false, &[]).bytes, b"metadata").unwrap();
    let mut second =
        DeclaredTypes::parse(&encode(&expected, true, &[]).bytes, b"metadata").unwrap();
    assert!(!Arc::ptr_eq(&first.types[0].name, &second.types[0].name));
    second.types[1].name = "Other".into();
    second
        .fields
        .shift_remove(&BorrowedMemberKey("Example", "first", "Ljava/util/List;"));
    assert_eq!(
        first.field_type("Example", "first", "Ljava/util/List;"),
        Some(0)
    );
    assert_eq!(
        second.field_type("Example", "first", "Ljava/util/List;"),
        None
    );
    assert_eq!(
        second.field_type("Example", "second", "Ljava/util/List;"),
        Some(0)
    );
    assert_eq!(first.render(3), "java.util.List<? super T>[]");
    assert_eq!(second.render(3), "java.util.List<? super Other>[]");
}

#[test]
fn duplicate_member_and_class_bindings_are_rejected_in_both_versions() {
    for pooled in [false, true] {
        let wire = encode(&full_table(), pooled, &[]);
        assert_eq!(wire.sections.len(), 3);
        for (name, count_offset, start, end) in &wire.sections {
            let mut bytes = wire.bytes.clone();
            let count =
                i32::from_be_bytes(bytes[*count_offset..*count_offset + 4].try_into().unwrap());
            bytes[*count_offset..*count_offset + 4].copy_from_slice(&(count + 1).to_be_bytes());
            let duplicate = bytes[*start..*end].to_vec();
            bytes.splice(*end..*end, duplicate);
            assert!(DeclaredTypes::parse(&bytes, b"metadata")
                .unwrap_err()
                .0
                .contains(&format!("duplicate {name}")));
        }
    }
}

#[test]
fn both_wire_versions_load_from_bound_directories_and_packed_sources() {
    let root = std::env::temp_dir().join(format!("graphite-types-v2-pack-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    let dir = root.join("source");
    std::fs::create_dir_all(&dir).unwrap();
    for name in crate::container::REQUIRED_ENTRIES {
        std::fs::write(dir.join(name), b"metadata").unwrap();
    }
    let expected = full_table();
    for pooled in [false, true] {
        let bytes = encode(&expected, pooled, &[]).bytes;
        std::fs::write(dir.join("graph.types"), &bytes).unwrap();
        std::fs::write(dir.join("forward.properties"), "nodes=0\narcs=0\n").unwrap();
        assert!(DeclaredTypes::load(&GraphSource::open(&dir).unwrap())
            .unwrap()
            .is_none());
        super::tests::bind(&dir, &bytes);
        assert_eq!(
            DeclaredTypes::load(&GraphSource::open(&dir).unwrap())
                .unwrap()
                .unwrap(),
            expected
        );
        let packed = root.join(format!("version-{}.graphite", if pooled { 2 } else { 1 }));
        crate::container::pack(&dir, &packed).unwrap();
        assert_eq!(
            DeclaredTypes::load(&GraphSource::open(&packed).unwrap())
                .unwrap()
                .unwrap(),
            expected
        );
        std::fs::write(dir.join("graph.types"), &bytes[..bytes.len() - 1]).unwrap();
        assert!(DeclaredTypes::load(&GraphSource::open(&dir).unwrap())
            .unwrap_err()
            .0
            .contains("digest mismatch"));
    }
    std::fs::remove_dir_all(root).unwrap();
}
