//! Reviewed, directed semantic transitions. This policy must be compiled from
//! the trusted controller source before a feature revision can use it.
//!
//! Digests cover exact query bytes and the complete canonical typed response,
//! including row order, duplicate rows, metadata and graph identity. The caller
//! must not project a response down to counts or selected properties first.
use serde::Serialize;
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Observation {
    pub case_id: String,
    pub base_query_sha256: String,
    pub head_query_sha256: String,
    pub base_response_sha256: String,
    pub head_response_sha256: String,
}

struct Rule<'a> {
    base_product_identity: &'a str,
    head_product_identity: &'a str,
    case_id: &'a str,
    base_query_sha256: &'a str,
    head_query_sha256: &'a str,
    base_response_sha256: &'a str,
    head_response_sha256: &'a str,
    reason: &'a str,
}

// No wildcards, external policy file, environment setting or CLI override.
// Land reviewed literal entries in the trusted base first, then rerun the
// feature comparison. Product identities exclude only the exact trusted policy
// files, so merging policy-only commits does not invalidate reviewed products.
// PR #174 (head e6f122d, base main 52df93e): deduplicated declared types with JVM generics.
// The three response digests were verified from run 39379876956's retained bodies and are
// unchanged on the code of this head (runs 40249949452, 40274371615, 40309014252 on 6d0104e and
// 9a0b5f7; e6f122d is that code squashed and rebased onto main 52df93e, #184 and #186, whose planner
// changes are on both sides); the identities moved with the review fixes
// (Attempt311) and the merge of #182. See the PR's acceptance section.
const RULES: &[Rule<'static>] = &[
    Rule {
        base_product_identity: "dda0f6d5b7393e5eb7cde4eec6fd954a4d72425517d0a299118ed6b4ace5d7b7",
        head_product_identity: "b94430b5ec2f971b4ec6037ed72841c688e08b7c9a7be12c11de22d72299732c",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "15da09067500c818f96a642fc123a7b706accda8604ca2242a2509863649b16c",
        head_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        reason: "PR #174: Field, Parameter and Return nodes carry generic_type and type_info (3,485,883 each); the 26 pre-existing key counts are unchanged",
    },
    Rule {
        base_product_identity: "dda0f6d5b7393e5eb7cde4eec6fd954a4d72425517d0a299118ed6b4ace5d7b7",
        head_product_identity: "b94430b5ec2f971b4ec6037ed72841c688e08b7c9a7be12c11de22d72299732c",
        case_id: "shape-label-scan-field",
        base_query_sha256: "61c28d12706cfe366212c4d65b2c50e528732a060951d740f0bd3eb9cae9f550",
        head_query_sha256: "61c28d12706cfe366212c4d65b2c50e528732a060951d740f0bd3eb9cae9f550",
        base_response_sha256: "9919b1ed8bb619dd7b038a065c2f139bdd030137b2b0a755732d11a5cc869bea",
        head_response_sha256: "caf86fbeccc186b9b34e6ef948353ea96d73148c6628535b00625ef52f267b1b",
        reason: "PR #174 (Attempt309): declared-only fields are numbered in (name, signature) order instead of SootUp's identity-hash set order; the same 200 (name, graph) rows, 57 reordered",
    },
    Rule {
        base_product_identity: "dda0f6d5b7393e5eb7cde4eec6fd954a4d72425517d0a299118ed6b4ace5d7b7",
        head_product_identity: "b94430b5ec2f971b4ec6037ed72841c688e08b7c9a7be12c11de22d72299732c",
        case_id: "shape-all-nodes",
        base_query_sha256: "ffa35341b8af522a820f76eed006980e6ac94406ccd21811dbd9ee635b6677b1",
        head_query_sha256: "ffa35341b8af522a820f76eed006980e6ac94406ccd21811dbd9ee635b6677b1",
        base_response_sha256: "d970e9a364104e9424bd1db998d805a9c706a0747bee0b12032502e093c4a88a",
        head_response_sha256: "c1738ba743cfc7a40ee52e2f5565f369ff0ade90bcd2dcac81f2b9dbcf14a09f",
        reason: "PR #174: generic_type and type_info added on 49 of the 200 nodes; every pre-existing property, value and provenance unchanged",
    },
    // PR #193, run 41738605110: static field references share their declaration node.
    Rule {
        base_product_identity: "c4ab3312cf9e2204d7cafebd885937be5dd97b5eecb85cb8d272321c38760a23",
        head_product_identity: "26a030f6e3be700397d2979d87cae0dc62d73d107ff15a11084600dd96c57989",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "ced37e75ba754d2c1fbaac5004708cda705b3183d8c5dbc4bcc8b7f411e71394",
        head_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        reason: "PR #193: Static fields now use the declaring-class node: FieldNode 733,412 -> 733,312; all other label counts unchanged",
    },
    Rule {
        base_product_identity: "c4ab3312cf9e2204d7cafebd885937be5dd97b5eecb85cb8d272321c38760a23",
        head_product_identity: "26a030f6e3be700397d2979d87cae0dc62d73d107ff15a11084600dd96c57989",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        head_response_sha256: "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        reason: "PR #193: Per-key counts reflect 100 fewer Field nodes: id, graphId, elementId, qualifiedId, type, name, class and static each -100; generic_type and type_info each -80",
    },
    Rule {
        base_product_identity: "c4ab3312cf9e2204d7cafebd885937be5dd97b5eecb85cb8d272321c38760a23",
        head_product_identity: "26a030f6e3be700397d2979d87cae0dc62d73d107ff15a11084600dd96c57989",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "c97af2647e6766def04cd1890516efc6be852758d35aa000652587a8ac9e13dc",
        head_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        reason: "PR #193: Coalescing field endpoints: IntConstant -> DATAFLOW -> FieldNode 42,499 -> 42,498; the other 39 returned buckets are unchanged",
    },
    Rule {
        base_product_identity: "c4ab3312cf9e2204d7cafebd885937be5dd97b5eecb85cb8d272321c38760a23",
        head_product_identity: "26a030f6e3be700397d2979d87cae0dc62d73d107ff15a11084600dd96c57989",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "db49759a02623072ab0fd4e5c980b8bd51fbd8c78d0c889c127126b33d0e4074",
        head_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        reason: "PR #193: Build-order renumbering after field merges: of 200 b.id rows, 27 decrease by one (6 android-00, 21 android-01); all other IDs and per-row graph provenance unchanged",
    },
    Rule {
        base_product_identity: "c4ab3312cf9e2204d7cafebd885937be5dd97b5eecb85cb8d272321c38760a23",
        head_product_identity: "26a030f6e3be700397d2979d87cae0dc62d73d107ff15a11084600dd96c57989",
        case_id: "shape-count-all",
        base_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        head_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        base_response_sha256: "d379a18c9a59c8afab70f4e8b889e84a1863b6e2dc74f29b552023b0cbc26969",
        head_response_sha256: "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        reason: "PR #193: 19,438,199 -> 19,438,099 nodes, matching the 100 removed Field nodes",
    },
    Rule {
        base_product_identity: "c4ab3312cf9e2204d7cafebd885937be5dd97b5eecb85cb8d272321c38760a23",
        head_product_identity: "26a030f6e3be700397d2979d87cae0dc62d73d107ff15a11084600dd96c57989",
        case_id: "shape-count-traversal",
        base_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        head_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        base_response_sha256: "d73fb737c486da3e59f951cbc41ab7c91bb801de8b88dc5155436b3d5f38cbc3",
        head_response_sha256: "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        reason: "PR #193: 21,081,154 -> 21,081,153 traversals, matching the one fewer IntConstant -> DATAFLOW -> FieldNode edge",
    },
    // After merging #194: all 73 full responses on each side match run 41738605110
    // byte for byte in run 41764623140. Bind the same six differences to that exact pair.
    Rule {
        base_product_identity: "73d1b5523acd3c03772ed3a336556638adeeb36dda89a16ea1e512376d961f96",
        head_product_identity: "28eabd41c03937e579d3c5117918a723c2d3634a15282fb48be3d54452d7e4a3",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "ced37e75ba754d2c1fbaac5004708cda705b3183d8c5dbc4bcc8b7f411e71394",
        head_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        reason: "PR #193: Static fields now use the declaring-class node: FieldNode 733,412 -> 733,312; all other label counts unchanged",
    },
    Rule {
        base_product_identity: "73d1b5523acd3c03772ed3a336556638adeeb36dda89a16ea1e512376d961f96",
        head_product_identity: "28eabd41c03937e579d3c5117918a723c2d3634a15282fb48be3d54452d7e4a3",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        head_response_sha256: "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        reason: "PR #193: Per-key counts reflect 100 fewer Field nodes: id, graphId, elementId, qualifiedId, type, name, class and static each -100; generic_type and type_info each -80",
    },
    Rule {
        base_product_identity: "73d1b5523acd3c03772ed3a336556638adeeb36dda89a16ea1e512376d961f96",
        head_product_identity: "28eabd41c03937e579d3c5117918a723c2d3634a15282fb48be3d54452d7e4a3",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "c97af2647e6766def04cd1890516efc6be852758d35aa000652587a8ac9e13dc",
        head_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        reason: "PR #193: Coalescing field endpoints: IntConstant -> DATAFLOW -> FieldNode 42,499 -> 42,498; the other 39 returned buckets are unchanged",
    },
    Rule {
        base_product_identity: "73d1b5523acd3c03772ed3a336556638adeeb36dda89a16ea1e512376d961f96",
        head_product_identity: "28eabd41c03937e579d3c5117918a723c2d3634a15282fb48be3d54452d7e4a3",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "db49759a02623072ab0fd4e5c980b8bd51fbd8c78d0c889c127126b33d0e4074",
        head_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        reason: "PR #193: Build-order renumbering after field merges: of 200 b.id rows, 27 decrease by one (6 android-00, 21 android-01); all other IDs and per-row graph provenance unchanged",
    },
    Rule {
        base_product_identity: "73d1b5523acd3c03772ed3a336556638adeeb36dda89a16ea1e512376d961f96",
        head_product_identity: "28eabd41c03937e579d3c5117918a723c2d3634a15282fb48be3d54452d7e4a3",
        case_id: "shape-count-all",
        base_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        head_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        base_response_sha256: "d379a18c9a59c8afab70f4e8b889e84a1863b6e2dc74f29b552023b0cbc26969",
        head_response_sha256: "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        reason: "PR #193: 19,438,199 -> 19,438,099 nodes, matching the 100 removed Field nodes",
    },
    Rule {
        base_product_identity: "73d1b5523acd3c03772ed3a336556638adeeb36dda89a16ea1e512376d961f96",
        head_product_identity: "28eabd41c03937e579d3c5117918a723c2d3634a15282fb48be3d54452d7e4a3",
        case_id: "shape-count-traversal",
        base_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        head_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        base_response_sha256: "d73fb737c486da3e59f951cbc41ab7c91bb801de8b88dc5155436b3d5f38cbc3",
        head_response_sha256: "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        reason: "PR #193: 21,081,154 -> 21,081,153 traversals, matching the one fewer IntConstant -> DATAFLOW -> FieldNode edge",
    },
    // After merging #197: all 73 full responses on each side match run 41738605110
    // byte for byte in run 41824456062. Bind the same six differences to that exact pair.
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "97e9ed053cde34a6607dc92858062c3b0a7a1346a2798fab584745b9c835c349",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "ced37e75ba754d2c1fbaac5004708cda705b3183d8c5dbc4bcc8b7f411e71394",
        head_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        reason: "PR #193: Static fields now use the declaring-class node: FieldNode 733,412 -> 733,312; all other label counts unchanged",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "97e9ed053cde34a6607dc92858062c3b0a7a1346a2798fab584745b9c835c349",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        head_response_sha256: "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        reason: "PR #193: Per-key counts reflect 100 fewer Field nodes: id, graphId, elementId, qualifiedId, type, name, class and static each -100; generic_type and type_info each -80",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "97e9ed053cde34a6607dc92858062c3b0a7a1346a2798fab584745b9c835c349",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "c97af2647e6766def04cd1890516efc6be852758d35aa000652587a8ac9e13dc",
        head_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        reason: "PR #193: Coalescing field endpoints: IntConstant -> DATAFLOW -> FieldNode 42,499 -> 42,498; the other 39 returned buckets are unchanged",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "97e9ed053cde34a6607dc92858062c3b0a7a1346a2798fab584745b9c835c349",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "db49759a02623072ab0fd4e5c980b8bd51fbd8c78d0c889c127126b33d0e4074",
        head_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        reason: "PR #193: Build-order renumbering after field merges: of 200 b.id rows, 27 decrease by one (6 android-00, 21 android-01); all other IDs and per-row graph provenance unchanged",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "97e9ed053cde34a6607dc92858062c3b0a7a1346a2798fab584745b9c835c349",
        case_id: "shape-count-all",
        base_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        head_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        base_response_sha256: "d379a18c9a59c8afab70f4e8b889e84a1863b6e2dc74f29b552023b0cbc26969",
        head_response_sha256: "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        reason: "PR #193: 19,438,199 -> 19,438,099 nodes, matching the 100 removed Field nodes",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "97e9ed053cde34a6607dc92858062c3b0a7a1346a2798fab584745b9c835c349",
        case_id: "shape-count-traversal",
        base_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        head_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        base_response_sha256: "d73fb737c486da3e59f951cbc41ab7c91bb801de8b88dc5155436b3d5f38cbc3",
        head_response_sha256: "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        reason: "PR #193: 21,081,154 -> 21,081,153 traversals, matching the one fewer IntConstant -> DATAFLOW -> FieldNode edge",
    },
    // After the dependency completeness fixes (4e078e1a): all 73 full responses on each
    // side match run 41738605110 byte for byte in run 41963895354. Bind that exact pair.
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "91c9cf4b5151841b7f502f0e808e1e3fd75f5760cbadf177536a8f131db70d7b",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "ced37e75ba754d2c1fbaac5004708cda705b3183d8c5dbc4bcc8b7f411e71394",
        head_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        reason: "PR #193: Static fields now use the declaring-class node: FieldNode 733,412 -> 733,312; all other label counts unchanged",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "91c9cf4b5151841b7f502f0e808e1e3fd75f5760cbadf177536a8f131db70d7b",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        head_response_sha256: "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        reason: "PR #193: Per-key counts reflect 100 fewer Field nodes: id, graphId, elementId, qualifiedId, type, name, class and static each -100; generic_type and type_info each -80",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "91c9cf4b5151841b7f502f0e808e1e3fd75f5760cbadf177536a8f131db70d7b",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "c97af2647e6766def04cd1890516efc6be852758d35aa000652587a8ac9e13dc",
        head_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        reason: "PR #193: Coalescing field endpoints: IntConstant -> DATAFLOW -> FieldNode 42,499 -> 42,498; the other 39 returned buckets are unchanged",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "91c9cf4b5151841b7f502f0e808e1e3fd75f5760cbadf177536a8f131db70d7b",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "db49759a02623072ab0fd4e5c980b8bd51fbd8c78d0c889c127126b33d0e4074",
        head_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        reason: "PR #193: Build-order renumbering after field merges: of 200 b.id rows, 27 decrease by one (6 android-00, 21 android-01); all other IDs and per-row graph provenance unchanged",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "91c9cf4b5151841b7f502f0e808e1e3fd75f5760cbadf177536a8f131db70d7b",
        case_id: "shape-count-all",
        base_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        head_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        base_response_sha256: "d379a18c9a59c8afab70f4e8b889e84a1863b6e2dc74f29b552023b0cbc26969",
        head_response_sha256: "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        reason: "PR #193: 19,438,199 -> 19,438,099 nodes, matching the 100 removed Field nodes",
    },
    Rule {
        base_product_identity: "67d70cb84af68438bc078b1bdce23b0a4febeb93b75b24d38d095c5c2123fecb",
        head_product_identity: "91c9cf4b5151841b7f502f0e808e1e3fd75f5760cbadf177536a8f131db70d7b",
        case_id: "shape-count-traversal",
        base_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        head_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        base_response_sha256: "d73fb737c486da3e59f951cbc41ab7c91bb801de8b88dc5155436b3d5f38cbc3",
        head_response_sha256: "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        reason: "PR #193: 21,081,154 -> 21,081,153 traversals, matching the one fewer IntConstant -> DATAFLOW -> FieldNode edge",
    },
    // After merging #199 and #201 (4f9988de): all 73 full responses on each side
    // match run 41738605110 byte for byte in run 42005295745. Bind that exact pair.
    Rule {
        base_product_identity: "8552e204e3779fb3fab872de70b1bc1633faa57b572c1648647c60bd9f643029",
        head_product_identity: "8bf93d3eb24e0dd42006a7e2f77a6082fd1b814b3f2d9a513ec40a7e88071b50",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "ced37e75ba754d2c1fbaac5004708cda705b3183d8c5dbc4bcc8b7f411e71394",
        head_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        reason: "PR #193: Static fields now use the declaring-class node: FieldNode 733,412 -> 733,312; all other label counts unchanged",
    },
    Rule {
        base_product_identity: "8552e204e3779fb3fab872de70b1bc1633faa57b572c1648647c60bd9f643029",
        head_product_identity: "8bf93d3eb24e0dd42006a7e2f77a6082fd1b814b3f2d9a513ec40a7e88071b50",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        head_response_sha256: "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        reason: "PR #193: Per-key counts reflect 100 fewer Field nodes: id, graphId, elementId, qualifiedId, type, name, class and static each -100; generic_type and type_info each -80",
    },
    Rule {
        base_product_identity: "8552e204e3779fb3fab872de70b1bc1633faa57b572c1648647c60bd9f643029",
        head_product_identity: "8bf93d3eb24e0dd42006a7e2f77a6082fd1b814b3f2d9a513ec40a7e88071b50",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "c97af2647e6766def04cd1890516efc6be852758d35aa000652587a8ac9e13dc",
        head_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        reason: "PR #193: Coalescing field endpoints: IntConstant -> DATAFLOW -> FieldNode 42,499 -> 42,498; the other 39 returned buckets are unchanged",
    },
    Rule {
        base_product_identity: "8552e204e3779fb3fab872de70b1bc1633faa57b572c1648647c60bd9f643029",
        head_product_identity: "8bf93d3eb24e0dd42006a7e2f77a6082fd1b814b3f2d9a513ec40a7e88071b50",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "db49759a02623072ab0fd4e5c980b8bd51fbd8c78d0c889c127126b33d0e4074",
        head_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        reason: "PR #193: Build-order renumbering after field merges: of 200 b.id rows, 27 decrease by one (6 android-00, 21 android-01); all other IDs and per-row graph provenance unchanged",
    },
    Rule {
        base_product_identity: "8552e204e3779fb3fab872de70b1bc1633faa57b572c1648647c60bd9f643029",
        head_product_identity: "8bf93d3eb24e0dd42006a7e2f77a6082fd1b814b3f2d9a513ec40a7e88071b50",
        case_id: "shape-count-all",
        base_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        head_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        base_response_sha256: "d379a18c9a59c8afab70f4e8b889e84a1863b6e2dc74f29b552023b0cbc26969",
        head_response_sha256: "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        reason: "PR #193: 19,438,199 -> 19,438,099 nodes, matching the 100 removed Field nodes",
    },
    Rule {
        base_product_identity: "8552e204e3779fb3fab872de70b1bc1633faa57b572c1648647c60bd9f643029",
        head_product_identity: "8bf93d3eb24e0dd42006a7e2f77a6082fd1b814b3f2d9a513ec40a7e88071b50",
        case_id: "shape-count-traversal",
        base_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        head_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        base_response_sha256: "d73fb737c486da3e59f951cbc41ab7c91bb801de8b88dc5155436b3d5f38cbc3",
        head_response_sha256: "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        reason: "PR #193: 21,081,154 -> 21,081,153 traversals, matching the one fewer IntConstant -> DATAFLOW -> FieldNode edge",
    },
];

fn digest(value: &str, bytes: usize) -> bool {
    value.len() == bytes * 2
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub fn evaluate(
    base_product_identity: &str,
    head_product_identity: &str,
    observations: &[Observation],
) -> Value {
    evaluate_rules(
        base_product_identity,
        head_product_identity,
        observations,
        RULES,
    )
}

fn evaluate_rules(
    base_product_identity: &str,
    head_product_identity: &str,
    observations: &[Observation],
    rules: &[Rule<'_>],
) -> Value {
    let mut errors = Vec::new();
    if !digest(base_product_identity, 32) || !digest(head_product_identity, 32) {
        errors.push(
            "Expected full lowercase base and candidate product SHA-256 identities".to_owned(),
        );
    }
    if observations.is_empty() {
        errors.push("No semantic observations supplied".to_owned());
    }
    let mut applicable = BTreeMap::new();
    for rule in rules.iter().filter(|r| {
        r.base_product_identity == base_product_identity
            && r.head_product_identity == head_product_identity
    }) {
        if rule.case_id.is_empty()
            || rule.reason.trim().is_empty()
            || ![
                rule.base_query_sha256,
                rule.head_query_sha256,
                rule.base_response_sha256,
                rule.head_response_sha256,
            ]
            .iter()
            .all(|value| digest(value, 32))
        {
            errors.push(format!(
                "Malformed compiled-in transition for {}",
                rule.case_id
            ));
        }
        if applicable.insert(rule.case_id, rule).is_some() {
            errors.push(format!(
                "Duplicate compiled-in transition for {}",
                rule.case_id
            ));
        }
    }
    let mut seen = BTreeSet::new();
    let mut cases = Vec::new();
    for observation in observations {
        let valid = !observation.case_id.is_empty()
            && [
                &observation.base_query_sha256,
                &observation.head_query_sha256,
                &observation.base_response_sha256,
                &observation.head_response_sha256,
            ]
            .iter()
            .all(|value| digest(value, 32));
        let unique = seen.insert(observation.case_id.as_str());
        let exact = observation.base_query_sha256 == observation.head_query_sha256
            && observation.base_response_sha256 == observation.head_response_sha256;
        let rule = applicable.get(observation.case_id.as_str());
        let matched = rule.is_some_and(|rule| {
            rule.base_query_sha256 == observation.base_query_sha256
                && rule.head_query_sha256 == observation.head_query_sha256
                && rule.base_response_sha256 == observation.base_response_sha256
                && rule.head_response_sha256 == observation.head_response_sha256
        });
        // An applicable expectation must match even if the observed case became
        // equal: changed/missing expected behavior is not the reviewed product.
        let approved = valid && unique && if rule.is_some() { matched } else { exact };
        let reason = if !valid {
            "Invalid case identity or SHA-256 digest"
        } else if !unique {
            "Duplicate case observation"
        } else if matched {
            rule.expect("matched rule").reason
        } else if rule.is_some() {
            "Observed query or complete typed response differs from reviewed expectation"
        } else if exact {
            "Queries and complete typed responses are identical"
        } else {
            "No compiled-in transition approves this directed source and case difference"
        };
        if !approved {
            errors.push(format!("{}: {reason}", observation.case_id));
        }
        cases.push(json!({
            "caseId":observation.case_id,"approved":approved,"exact":valid && unique && exact,
            "mode":if !approved {"rejected"} else if matched {"reviewed-transition"} else {"exact"},
            "reason":reason,"observation":observation,
        }));
    }
    for case in applicable.keys() {
        if !seen.contains(case) {
            errors.push(format!(
                "Missing observation for reviewed transition: {case}"
            ));
        }
    }
    let approved = errors.is_empty();
    json!({"schema":"graphite-semantic-transitions-v1","policy":"compiled-in",
        "baseProductIdentity":base_product_identity,"headProductIdentity":head_product_identity,"approved":approved,
        "exact":approved && cases.iter().all(|case| case["exact"] == true),
        "cases":cases,"errors":errors})
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};
    const BASE: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    const HEAD: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    fn hash(value: &str) -> String {
        Sha256::digest(value.as_bytes())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect()
    }
    fn observation(
        case: &str,
        base_query: &str,
        head_query: &str,
        base: &str,
        head: &str,
    ) -> Observation {
        Observation {
            case_id: case.into(),
            base_query_sha256: hash(base_query),
            head_query_sha256: hash(head_query),
            base_response_sha256: hash(base),
            head_response_sha256: hash(head),
        }
    }
    fn rule(observation: &Observation) -> Rule<'_> {
        Rule {
            base_product_identity: BASE,
            head_product_identity: HEAD,
            case_id: &observation.case_id,
            base_query_sha256: &observation.base_query_sha256,
            head_query_sha256: &observation.head_query_sha256,
            base_response_sha256: &observation.base_response_sha256,
            head_response_sha256: &observation.head_response_sha256,
            reason: "Reviewed node/schema expansion and its exact fixture discovery terms",
        }
    }
    fn intended() -> Vec<Observation> {
        vec![
            observation(
                "node-count",
                "MATCH(n) RETURN count(n)",
                "MATCH(n) RETURN count(n)",
                r#"{"columns":["count(n)"],"rows":[[42]],"rowCount":1}"#,
                r#"{"columns":["count(n)"],"rows":[[43]],"rowCount":1}"#,
            ),
            observation(
                "schema",
                "MATCH(n) RETURN keys(n)",
                "MATCH(n) RETURN keys(n)",
                r#"{"rows":[[["name"]]],"rowCount":1}"#,
                r#"{"rows":[[["name","declaredType"]]],"rowCount":1}"#,
            ),
            observation(
                "discovery-terms",
                "MATCH(n) WHERE n.name='Old' RETURN n",
                "MATCH(n) WHERE n.name='New' RETURN n",
                r#"{"rows":[[{"id":1,"name":"Old"}]],"graphIds":["a","b"]}"#,
                r#"{"rows":[[{"id":1,"name":"New"}]],"graphIds":["a","b"]}"#,
            ),
        ]
    }

    #[test]
    fn default_policy_accepts_equality_and_rejects_every_unreviewed_difference() {
        let same = observation("same", "RETURN 1", "RETURN 1", "[[1]]", "[[1]]");
        let result = evaluate(BASE, HEAD, &[same]);
        assert_eq!(result["approved"], true);
        assert_eq!(result["exact"], true);
        assert_eq!(result["baseProductIdentity"], BASE);
        assert_eq!(result["headProductIdentity"], HEAD);
        assert!(result.get("baseSha").is_none());
        assert!(result.get("headTree").is_none());
        for changed in intended() {
            assert_eq!(evaluate(BASE, HEAD, &[changed])["approved"], false);
        }
    }

    #[test]
    fn both_product_identities_require_full_lowercase_sha256_even_for_equal_results() {
        let same = observation("same", "RETURN 1", "RETURN 1", "[[1]]", "[[1]]");
        for invalid in [
            "".to_owned(),
            "*".to_owned(),
            "a".repeat(40),
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ] {
            for (base, head) in [(invalid.as_str(), HEAD), (BASE, invalid.as_str())] {
                let result = evaluate(base, head, std::slice::from_ref(&same));
                assert_eq!(result["approved"], false, "{base}/{head}");
                assert_eq!(result["exact"], false);
                assert!(!result["errors"].as_array().unwrap().is_empty());
            }
        }
    }

    #[test]
    fn exact_reviewed_counts_schema_and_changed_query_terms_are_approved() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        let mut observed = changes.clone();
        observed.push(observation(
            "unchanged",
            "RETURN 1",
            "RETURN 1",
            "[[1]]",
            "[[1]]",
        ));
        let result = evaluate_rules(BASE, HEAD, &observed, &rules);
        assert_eq!(result["approved"], true);
        assert_eq!(result["exact"], false);
        assert_eq!(result["cases"][0]["mode"], "reviewed-transition");
        assert_eq!(result["cases"][3]["mode"], "exact");
    }

    #[test]
    fn commit_metadata_does_not_change_a_product_bound_expectation() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        // Different commits can have the same approved product identity. Neither
        // commit metadata nor a complete Git tree participates in this key.
        let commits = [
            "cccccccccccccccccccccccccccccccccccccccc",
            "dddddddddddddddddddddddddddddddddddddddd",
        ];
        let results = commits.map(|_| evaluate_rules(BASE, HEAD, &changes, &rules));
        assert_eq!(results[0], results[1]);
        assert_eq!(results[0]["approved"], true);
    }

    #[test]
    fn wrong_base_wrong_head_product_and_reversed_direction_are_not_approved() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        assert_eq!(
            evaluate_rules(HEAD, HEAD, &changes, &rules)["approved"],
            false
        );
        assert_eq!(
            evaluate_rules(BASE, BASE, &changes, &rules)["approved"],
            false
        );
        let mut reverse = changes.clone();
        for row in &mut reverse {
            std::mem::swap(&mut row.base_query_sha256, &mut row.head_query_sha256);
            std::mem::swap(&mut row.base_response_sha256, &mut row.head_response_sha256);
        }
        assert_eq!(
            evaluate_rules(BASE, HEAD, &reverse, &rules)["approved"],
            false
        );
    }

    #[test]
    fn unapproved_extra_changes_query_drift_or_response_drift_fail() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        let mut extra = changes.clone();
        extra.push(observation(
            "unexpected",
            "RETURN n",
            "RETURN n",
            "[[1,1]]",
            "[[1]]",
        ));
        assert_eq!(
            evaluate_rules(BASE, HEAD, &extra, &rules)["approved"],
            false
        );
        let mut query = changes.clone();
        query[2].head_query_sha256 = hash("MATCH(n) RETURN n LIMIT 1");
        assert_eq!(
            evaluate_rules(BASE, HEAD, &query, &rules)["approved"],
            false
        );
        let mut body = changes.clone();
        body[2].head_response_sha256 =
            hash(r#"{"rows":[[{"id":1,"name":"New"}]],"graphIds":["a"]}"#);
        assert_eq!(evaluate_rules(BASE, HEAD, &body, &rules)["approved"], false);
    }

    #[test]
    fn missing_duplicate_and_malformed_observations_fail_closed() {
        let changes = intended();
        let rules = changes.iter().map(rule).collect::<Vec<_>>();
        assert_eq!(
            evaluate_rules(BASE, HEAD, &changes[..2], &rules)["approved"],
            false
        );
        let mut duplicate = changes.clone();
        duplicate.push(changes[0].clone());
        assert_eq!(
            evaluate_rules(BASE, HEAD, &duplicate, &rules)["approved"],
            false
        );
        let mut malformed = changes.clone();
        malformed[0].base_response_sha256 = "*".into();
        assert_eq!(
            evaluate_rules(BASE, HEAD, &malformed, &rules)["approved"],
            false
        );
        assert_eq!(evaluate(BASE, HEAD, &[])["approved"], false);
        assert_eq!(evaluate("*", HEAD, &changes)["approved"], false);
    }
}
