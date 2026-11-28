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

#[derive(Clone, Copy)]
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
    // After merging the #203 disk preflight (a7759713): all 73 full responses on each
    // side match run 41738605110 byte for byte in run 42025460124. Bind that exact pair.
    Rule {
        base_product_identity: "ee51b83cf6456e3f35093bec1fc3dbec75e3a32712c30a10eeda6712b1c5f086",
        head_product_identity: "91c5f098cd78ea13d9e57d28f7e475a60fb9c83b23b37c3d584361ed85656307",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "ced37e75ba754d2c1fbaac5004708cda705b3183d8c5dbc4bcc8b7f411e71394",
        head_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        reason: "PR #193: Static fields now use the declaring-class node: FieldNode 733,412 -> 733,312; all other label counts unchanged",
    },
    Rule {
        base_product_identity: "ee51b83cf6456e3f35093bec1fc3dbec75e3a32712c30a10eeda6712b1c5f086",
        head_product_identity: "91c5f098cd78ea13d9e57d28f7e475a60fb9c83b23b37c3d584361ed85656307",
        case_id: "schema-key-histogram",
        base_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        head_query_sha256: "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
        base_response_sha256: "ce4f62ac23534f10a947559830b356a29f1af4e75faaa3c6a4ef288b4fdaed41",
        head_response_sha256: "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        reason: "PR #193: Per-key counts reflect 100 fewer Field nodes: id, graphId, elementId, qualifiedId, type, name, class and static each -100; generic_type and type_info each -80",
    },
    Rule {
        base_product_identity: "ee51b83cf6456e3f35093bec1fc3dbec75e3a32712c30a10eeda6712b1c5f086",
        head_product_identity: "91c5f098cd78ea13d9e57d28f7e475a60fb9c83b23b37c3d584361ed85656307",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "c97af2647e6766def04cd1890516efc6be852758d35aa000652587a8ac9e13dc",
        head_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        reason: "PR #193: Coalescing field endpoints: IntConstant -> DATAFLOW -> FieldNode 42,499 -> 42,498; the other 39 returned buckets are unchanged",
    },
    Rule {
        base_product_identity: "ee51b83cf6456e3f35093bec1fc3dbec75e3a32712c30a10eeda6712b1c5f086",
        head_product_identity: "91c5f098cd78ea13d9e57d28f7e475a60fb9c83b23b37c3d584361ed85656307",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "db49759a02623072ab0fd4e5c980b8bd51fbd8c78d0c889c127126b33d0e4074",
        head_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        reason: "PR #193: Build-order renumbering after field merges: of 200 b.id rows, 27 decrease by one (6 android-00, 21 android-01); all other IDs and per-row graph provenance unchanged",
    },
    Rule {
        base_product_identity: "ee51b83cf6456e3f35093bec1fc3dbec75e3a32712c30a10eeda6712b1c5f086",
        head_product_identity: "91c5f098cd78ea13d9e57d28f7e475a60fb9c83b23b37c3d584361ed85656307",
        case_id: "shape-count-all",
        base_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        head_query_sha256: "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
        base_response_sha256: "d379a18c9a59c8afab70f4e8b889e84a1863b6e2dc74f29b552023b0cbc26969",
        head_response_sha256: "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        reason: "PR #193: 19,438,199 -> 19,438,099 nodes, matching the 100 removed Field nodes",
    },
    Rule {
        base_product_identity: "ee51b83cf6456e3f35093bec1fc3dbec75e3a32712c30a10eeda6712b1c5f086",
        head_product_identity: "91c5f098cd78ea13d9e57d28f7e475a60fb9c83b23b37c3d584361ed85656307",
        case_id: "shape-count-traversal",
        base_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        head_query_sha256: "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
        base_response_sha256: "d73fb737c486da3e59f951cbc41ab7c91bb801de8b88dc5155436b3d5f38cbc3",
        head_response_sha256: "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        reason: "PR #193: 21,081,154 -> 21,081,153 traversals, matching the one fewer IntConstant -> DATAFLOW -> FieldNode edge",
    },
    // PR #226 (head 002413a, base main c38905b), run 43808397749: class literals are ClassConstant nodes.
    // First bound to c69f543 on d4dd8ee (run 43729611853, #227); #225 moved both identities and
    // left all three response digests unchanged.
    Rule {
        base_product_identity: "5ad958fdddc70af949b8c0cf1c2594b412ced7134000c8aa2850adfd0c396aeb",
        head_product_identity: "981eeea3494460f75577260a8beb9296aceafa3c64e37ba96f3e055ff1c0ca75",
        case_id: "schema-label-histogram",
        base_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        head_query_sha256: "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
        base_response_sha256: "a2c5135bd200cf53b9b407d80bd20ec78c47ddcebc9c3d70b90a74f73a058866",
        head_response_sha256: "af893672357699830c1a555c8cb0586e68a18310c3171bb14db7db4d819ed6c7",
        reason: "PR #226: class literals become ClassConstant nodes instead of IntConstant(0): IntConstant 157,407 -> 126,774 and a new ClassConstant row of 30,633; the total is conserved and every other label is unchanged",
    },
    Rule {
        base_product_identity: "5ad958fdddc70af949b8c0cf1c2594b412ced7134000c8aa2850adfd0c396aeb",
        head_product_identity: "981eeea3494460f75577260a8beb9296aceafa3c64e37ba96f3e055ff1c0ca75",
        case_id: "schema-relationship-histogram",
        base_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        head_query_sha256: "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
        base_response_sha256: "04a30e6d7c605601217eac9b14669eb85e1fe29f81bd6ad126d1284c01c7696b",
        head_response_sha256: "b989ecf27a50d74f82af0b16739dbdc613823db7a7ddb2f9dc7fe7f3df4c1557",
        reason: "PR #226: IntConstant -> CallSite DATAFLOW -35,112 and IntConstant -> Local DATAFLOW -10,881 return as ClassConstant buckets with those counts, pushing Float -> Return (385) and Null -> CallSite CONTROL_FLOW (194) out of the top 40",
    },
    Rule {
        base_product_identity: "5ad958fdddc70af949b8c0cf1c2594b412ced7134000c8aa2850adfd0c396aeb",
        head_product_identity: "981eeea3494460f75577260a8beb9296aceafa3c64e37ba96f3e055ff1c0ca75",
        case_id: "shape-label-scan-constant",
        base_query_sha256: "36eb396d87edf3494167d7a2b05232be8a9109fa8c7cb3f6445974175bb91328",
        head_query_sha256: "36eb396d87edf3494167d7a2b05232be8a9109fa8c7cb3f6445974175bb91328",
        base_response_sha256: "0333b582aaa1e2078454000d844b0cb46c05fdccdb19e9dd9e9a8c76bf3d29db",
        head_response_sha256: "e5733b9b2410aa383ad45bac0f4c9886057e59a4caa2b4699f5f66fe7ee7112c",
        reason: "PR #226: Constant rows that read 0 for a class literal now read its JVM descriptor (for example Ljavax/xml/validation/SchemaFactory;)",
    },
    // PR #229 (head 7588730, base main 70f908c), run 46122244274: each build numbers its nodes from 1
    // (NodeId.numbered). The same 73 observations, with the same four response digests, on its earlier
    // heads c428401 (run 46091056203), 2d1a62b (run 46094928338) and on runs 45833728909 and
    // 46001831978; only the head's product identity moved with its later commits, and it is bound here.
    Rule {
        base_product_identity: "981eeea3494460f75577260a8beb9296aceafa3c64e37ba96f3e055ff1c0ca75",
        head_product_identity: "6f85545f52178f35a17b3c91ba71f11502924ea3c2c1ff4abcb9096e496a944e",
        case_id: "shape-id-lookup",
        base_query_sha256: "169f3d9eab075cdc2f38aecaa86a06cc37422b307cd16ad20247ec1a86a870a7",
        head_query_sha256: "169f3d9eab075cdc2f38aecaa86a06cc37422b307cd16ad20247ec1a86a870a7",
        base_response_sha256: "ff67961c60f35d835568256b882974c02f12312cc95aec950d919282587f0af6",
        head_response_sha256: "0389b15588eeb74fee5beea7dd9d8e671465883ce83bf1f940a6e955894a331f",
        reason: "PR #229: each build numbers its nodes from 1 (NodeId.numbered); base shards continued one process-wide counter, so only fixture-android-00 held id 4101 and now each of the 64 graphs does; fixture-android-00's row is unchanged",
    },
    Rule {
        base_product_identity: "981eeea3494460f75577260a8beb9296aceafa3c64e37ba96f3e055ff1c0ca75",
        head_product_identity: "6f85545f52178f35a17b3c91ba71f11502924ea3c2c1ff4abcb9096e496a944e",
        case_id: "shape-traverse-filtered",
        base_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        head_query_sha256: "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
        base_response_sha256: "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
        head_response_sha256: "448d1bad5acc64dfd5859735c6914ab3b3062a66fb7eba453b1b831e3d3647e7",
        reason: "PR #229: each build numbers its nodes from 1; the same 200 nodes of the same two graphs, fixture-android-00's ids unchanged and every fixture-android-01 id 360266 lower, the id range fixture-android-00 used of the base's shared counter",
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

/// The approval of #229's node-numbering transitions against the observations they were
/// reviewed on, so that a copied identity or digest, or a lost rule, fails here and not first
/// on #229's gate. The fixture lives in this file because it is the policy file product
/// identities exclude: anywhere else it would move `main`'s identity and void the rules.
#[cfg(test)]
mod pr229_approval {
    use super::*;

    /// main 70f908c and #229 7588730, as run 46122244274 computed them.
    const BASE: &str = "981eeea3494460f75577260a8beb9296aceafa3c64e37ba96f3e055ff1c0ca75";
    const HEAD: &str = "6f85545f52178f35a17b3c91ba71f11502924ea3c2c1ff4abcb9096e496a944e";
    /// #229's earlier heads c428401 (run 46091056203), db80380 and 2d1a62b (run 46094928338, the pair
    /// this approval was first bound to): the same observations, not approved.
    const OLD_HEADS: [&str; 3] = [
        "05205f46e2eeddf814b656e017ae92796339e088179e9857505300cdca43bf07",
        "5f4a0a23dc8c0247784894ac07c1e23ca51e3dd9c46e96d6db94de2d70eb4a8a",
        "eba350a9a104a1ea4597428d76b6e1f34c3d30b6909f9bc9225845136546c51c",
    ];
    const REVIEWED: [&str; 2] = ["shape-id-lookup", "shape-traverse-filtered"];

    /// Every observation of run 46122244274's `results/native/semantic-transitions.json` (run
    /// 46094928338's are the same 73, row for row), in
    /// its order: (case, base query, head query, base response, head response) SHA-256.
    const PR229_OBSERVATIONS: &[(&str, &str, &str, &str, &str)] = &[
        (
            "zero/global-wide-four-properties",
            "a62e2ed9c84ac8e4278f26ab2f448757fb73f353c4eaf4995dd89b823eafd5e8",
            "a62e2ed9c84ac8e4278f26ab2f448757fb73f353c4eaf4995dd89b823eafd5e8",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
        ),
        (
            "zero/global-wide-class-pair",
            "ef4c26381ee3ca2c22c7292db0841cfee24107e8f077b9566947de7f3685390c",
            "ef4c26381ee3ca2c22c7292db0841cfee24107e8f077b9566947de7f3685390c",
            "f597900ae41e4839319ee07d12f70a3c105a0a407b485045e97db9e475ecc7e7",
            "f597900ae41e4839319ee07d12f70a3c105a0a407b485045e97db9e475ecc7e7",
        ),
        (
            "zero/global-wide-name-pair",
            "4a48b6deb2b4ef6c0dd39f795e2a6aa5c460decd41deaeb1ce50d0af8e435e10",
            "4a48b6deb2b4ef6c0dd39f795e2a6aa5c460decd41deaeb1ce50d0af8e435e10",
            "3d53bcde29c959f8f73b1df88018b16aa5eaa7e1e62e234feaf32b91f94ce786",
            "3d53bcde29c959f8f73b1df88018b16aa5eaa7e1e62e234feaf32b91f94ce786",
        ),
        (
            "zero/global-wide-caller-class",
            "5f32ffe3fb97dcd13a79848547ff658647011e8b030e2e5c1e6452867d48f11b",
            "5f32ffe3fb97dcd13a79848547ff658647011e8b030e2e5c1e6452867d48f11b",
            "2e35c700a3fe23e36508a9c9330f9907f713394b3ee5a6f5042e9c334b5a61ad",
            "2e35c700a3fe23e36508a9c9330f9907f713394b3ee5a6f5042e9c334b5a61ad",
        ),
        (
            "zero/global-wide-callee-class",
            "b412eba1d5bd22e7213dd5763f16b4d2a40a8e62fda5d895b81abe306a385936",
            "b412eba1d5bd22e7213dd5763f16b4d2a40a8e62fda5d895b81abe306a385936",
            "9a53ae277917baf2e8140b45859e5e65c8f1640bb0656c386adb97eab51ab5bf",
            "9a53ae277917baf2e8140b45859e5e65c8f1640bb0656c386adb97eab51ab5bf",
        ),
        (
            "zero/global-wide-provenance",
            "3053f76c30ef6b34fce6665a2ab104bad26223f9133374648cbb041a0d146c95",
            "3053f76c30ef6b34fce6665a2ab104bad26223f9133374648cbb041a0d146c95",
            "dcc601db94fd4092ea5e7c1c0370a00a772b719753bf7d59cce60982ab5ce93d",
            "dcc601db94fd4092ea5e7c1c0370a00a772b719753bf7d59cce60982ab5ce93d",
        ),
        (
            "zero/global-wide-aliased",
            "527ad441f0549171fd87e2bf859b2308597c9f288c18261665c6448c6f6c7628",
            "527ad441f0549171fd87e2bf859b2308597c9f288c18261665c6448c6f6c7628",
            "ee873bb558633d65ca304aaea53e7d1155e60455c010854333dc0285a841eb56",
            "ee873bb558633d65ca304aaea53e7d1155e60455c010854333dc0285a841eb56",
        ),
        (
            "zero/global-wide-parameterized-as-literal",
            "a62e2ed9c84ac8e4278f26ab2f448757fb73f353c4eaf4995dd89b823eafd5e8",
            "a62e2ed9c84ac8e4278f26ab2f448757fb73f353c4eaf4995dd89b823eafd5e8",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
        ),
        (
            "zero/global-wide-wrapped-case-insensitive",
            "2e7f93cc0c6eec576d9d944ee20c919cb84b2808b8ba325548774313454391aa",
            "2e7f93cc0c6eec576d9d944ee20c919cb84b2808b8ba325548774313454391aa",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
        ),
        (
            "zero/global-wide-wrapped-case-insensitive-distinct",
            "622509a701664d150f7b13727b708ba964e18fa724881aac7c843e82bde147d7",
            "622509a701664d150f7b13727b708ba964e18fa724881aac7c843e82bde147d7",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
            "8d47567d000c33d01c01ab0e3fbf0f0df948e38e4d66db731f68c7776468f760",
        ),
        (
            "targeted/global-wide-four-properties",
            "7aeaa1dfea2f54c6e04ce314d525f66a1c982efa3a0b8a0fd58d03b0a7cc7701",
            "7aeaa1dfea2f54c6e04ce314d525f66a1c982efa3a0b8a0fd58d03b0a7cc7701",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
        ),
        (
            "targeted/global-wide-class-pair",
            "c33e6c0c08430e24c4ca2964a5190f8a37d4ff0b4f78f22bf00dbf5b094b7fbf",
            "c33e6c0c08430e24c4ca2964a5190f8a37d4ff0b4f78f22bf00dbf5b094b7fbf",
            "8d444d282ea26cef382a74f9ba265ca085733859c39f520bc2d9997314114387",
            "8d444d282ea26cef382a74f9ba265ca085733859c39f520bc2d9997314114387",
        ),
        (
            "targeted/global-wide-name-pair",
            "de8626633e1fd4dcf762378eb1253e970a0b1bff702af5b2ae7a9953399376da",
            "de8626633e1fd4dcf762378eb1253e970a0b1bff702af5b2ae7a9953399376da",
            "089d4c11a4433d8aec3934517f5d9924c554c5605b49795bcc85093ea2a0cb65",
            "089d4c11a4433d8aec3934517f5d9924c554c5605b49795bcc85093ea2a0cb65",
        ),
        (
            "targeted/global-wide-caller-class",
            "c9c91490c15858149b5a58b5e4f43f8eca87968afa840986db99014b7944a920",
            "c9c91490c15858149b5a58b5e4f43f8eca87968afa840986db99014b7944a920",
            "9c7ad79e2d449e38ed7734146556ccd8f1d98a1ea829b129fbee12161c31d639",
            "9c7ad79e2d449e38ed7734146556ccd8f1d98a1ea829b129fbee12161c31d639",
        ),
        (
            "targeted/global-wide-callee-class",
            "8f4498c2adbdaa8f5440eb9f24a26394eaab17a0b1a188989fa725df13a98992",
            "8f4498c2adbdaa8f5440eb9f24a26394eaab17a0b1a188989fa725df13a98992",
            "88f3138c2e15f1cbf19104d7efb146636712feb69d79fff15444160ca5bda749",
            "88f3138c2e15f1cbf19104d7efb146636712feb69d79fff15444160ca5bda749",
        ),
        (
            "targeted/global-wide-provenance",
            "5aa97e0c6166666786be117e31790a1e30409d75bf87399287b3fc20327297dd",
            "5aa97e0c6166666786be117e31790a1e30409d75bf87399287b3fc20327297dd",
            "2a21d51116c1a3ba236c55862231c77cc9f7492074e20e3ee9720d60695ccbe6",
            "2a21d51116c1a3ba236c55862231c77cc9f7492074e20e3ee9720d60695ccbe6",
        ),
        (
            "targeted/global-wide-aliased",
            "83c20a970dcd7896eaeec945b36451899075a7914eb1295e221497fe05698ba4",
            "83c20a970dcd7896eaeec945b36451899075a7914eb1295e221497fe05698ba4",
            "7708cbd8c168060e6e8c1bfc5d14b6dc8e381ff109e2139d331c935bc60601da",
            "7708cbd8c168060e6e8c1bfc5d14b6dc8e381ff109e2139d331c935bc60601da",
        ),
        (
            "targeted/global-wide-parameterized-as-literal",
            "7aeaa1dfea2f54c6e04ce314d525f66a1c982efa3a0b8a0fd58d03b0a7cc7701",
            "7aeaa1dfea2f54c6e04ce314d525f66a1c982efa3a0b8a0fd58d03b0a7cc7701",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
        ),
        (
            "targeted/global-wide-wrapped-case-insensitive",
            "edebb9d7124e6cada82afac83073ef8b0764f2bea86916e9837fb873e9183414",
            "edebb9d7124e6cada82afac83073ef8b0764f2bea86916e9837fb873e9183414",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
        ),
        (
            "targeted/global-wide-wrapped-case-insensitive-distinct",
            "bf01a2a49ca94096b77ce775ef11826c1b6e40d1360c7111204c39f01469287d",
            "bf01a2a49ca94096b77ce775ef11826c1b6e40d1360c7111204c39f01469287d",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
            "710d123fa3d5a6595f990122b12a89489111654d17b08c7a96dde80219b7ebcd",
        ),
        (
            "dense/global-wide-four-properties",
            "7a41ab8af71576ca9e872e408a216229430c490a2a75a8d98aa238d7112ebe49",
            "7a41ab8af71576ca9e872e408a216229430c490a2a75a8d98aa238d7112ebe49",
            "bfa9ff18fe7ac280409448431ff9929243be209724717949fe41346de9539de6",
            "bfa9ff18fe7ac280409448431ff9929243be209724717949fe41346de9539de6",
        ),
        (
            "dense/global-wide-class-pair",
            "df6505ac9046688c21d330141017178d1a6bf86671867ea2238e2a0b989570ea",
            "df6505ac9046688c21d330141017178d1a6bf86671867ea2238e2a0b989570ea",
            "825735a34a9c5019454fd7502133abfa0e5923148e06945d4457d10042e39c1e",
            "825735a34a9c5019454fd7502133abfa0e5923148e06945d4457d10042e39c1e",
        ),
        (
            "dense/global-wide-name-pair",
            "9bee68d1375446a2a3a49ea8ce5cb524602c143950717720b13c8ed711818c8d",
            "9bee68d1375446a2a3a49ea8ce5cb524602c143950717720b13c8ed711818c8d",
            "a9ec20223924f2d9deb48c334860a5da48f7f444405a6af8dc502e87be0fa30d",
            "a9ec20223924f2d9deb48c334860a5da48f7f444405a6af8dc502e87be0fa30d",
        ),
        (
            "dense/global-wide-caller-class",
            "7c55514c67383c0e4170b6961553036bc893f2ba150a6fad504c6e6cea8c8234",
            "7c55514c67383c0e4170b6961553036bc893f2ba150a6fad504c6e6cea8c8234",
            "26a1cb97db4f5c4e3b23c68a9cf4b7098271b2cacbd632eb532218cd6efd64cb",
            "26a1cb97db4f5c4e3b23c68a9cf4b7098271b2cacbd632eb532218cd6efd64cb",
        ),
        (
            "dense/global-wide-callee-class",
            "a90ae1b807d6addb89e11ec96205e07ae4a9f910673a6daf7059c1b533cd043e",
            "a90ae1b807d6addb89e11ec96205e07ae4a9f910673a6daf7059c1b533cd043e",
            "1ba0b29f47383ec3aab4b9be9ffaf7077ff4cfcf7736161966e4b8157e0e542c",
            "1ba0b29f47383ec3aab4b9be9ffaf7077ff4cfcf7736161966e4b8157e0e542c",
        ),
        (
            "dense/global-wide-provenance",
            "e7c1d4089cb756636e9b73ec2ac891d60e3dce26e237f4f2985a943e347c5826",
            "e7c1d4089cb756636e9b73ec2ac891d60e3dce26e237f4f2985a943e347c5826",
            "8434f7588bf2d1db41cc477eb3241fc5ea8e52e5c48e00e115f2db81a11f2f6a",
            "8434f7588bf2d1db41cc477eb3241fc5ea8e52e5c48e00e115f2db81a11f2f6a",
        ),
        (
            "dense/global-wide-aliased",
            "9ff0b9a62177ec4599cbfae0b60af87ffe23b5a894ad37a46bfa5328f86a69d4",
            "9ff0b9a62177ec4599cbfae0b60af87ffe23b5a894ad37a46bfa5328f86a69d4",
            "31481f6bd51b038a871fb8a3bfffa2ca0e73c385ccf35dc41d4cb405624df0de",
            "31481f6bd51b038a871fb8a3bfffa2ca0e73c385ccf35dc41d4cb405624df0de",
        ),
        (
            "dense/global-wide-parameterized-as-literal",
            "7a41ab8af71576ca9e872e408a216229430c490a2a75a8d98aa238d7112ebe49",
            "7a41ab8af71576ca9e872e408a216229430c490a2a75a8d98aa238d7112ebe49",
            "bfa9ff18fe7ac280409448431ff9929243be209724717949fe41346de9539de6",
            "bfa9ff18fe7ac280409448431ff9929243be209724717949fe41346de9539de6",
        ),
        (
            "dense/global-wide-wrapped-case-insensitive",
            "c694284650250788593ed94a73dd8330c1ceef5c2bb1f782fad6e368db527c00",
            "c694284650250788593ed94a73dd8330c1ceef5c2bb1f782fad6e368db527c00",
            "fa63ff17fbdc8e40964c8449a620f4a985029ba769a29e0d23698d7198292c2d",
            "fa63ff17fbdc8e40964c8449a620f4a985029ba769a29e0d23698d7198292c2d",
        ),
        (
            "dense/global-wide-wrapped-case-insensitive-distinct",
            "f12c63bd0251fe4ade8df81bd787fdf0eace1ca13c9a36483086c0d9689a1e26",
            "f12c63bd0251fe4ade8df81bd787fdf0eace1ca13c9a36483086c0d9689a1e26",
            "8eb0b12edef31b61cf65fe0b1c0fecfaf24f78fbaf4e8c00526d4c55695a7142",
            "8eb0b12edef31b61cf65fe0b1c0fecfaf24f78fbaf4e8c00526d4c55695a7142",
        ),
        (
            "localized-early/global-wide-four-properties",
            "607aa55d80fa9ccb9f13e5b9107f14f6cba16e7f863c862cd5d53f1c23059f8e",
            "607aa55d80fa9ccb9f13e5b9107f14f6cba16e7f863c862cd5d53f1c23059f8e",
            "2cba549f1d330975949e4d57301cd38dfb7ada1853a728234f5103fd3d7cf4ed",
            "2cba549f1d330975949e4d57301cd38dfb7ada1853a728234f5103fd3d7cf4ed",
        ),
        (
            "localized-middle/global-wide-four-properties",
            "4302637c07bc770cb08aadcff9c2d05b3312f014291a4ecac2ef15301220944a",
            "4302637c07bc770cb08aadcff9c2d05b3312f014291a4ecac2ef15301220944a",
            "ae27d3a8fa344547f879365f1d5e74505b3556b73d99636b33b9d62990672670",
            "ae27d3a8fa344547f879365f1d5e74505b3556b73d99636b33b9d62990672670",
        ),
        (
            "localized-late/global-wide-four-properties",
            "3f2bfcd26d97a468911e9a5e37cf43b027c3baf23e58b93140db62bc18ee70b8",
            "3f2bfcd26d97a468911e9a5e37cf43b027c3baf23e58b93140db62bc18ee70b8",
            "b24a9dd91581255a212fc0ff51a62c4e9c4b56a9782610a6a0ab6c40a71f3e49",
            "b24a9dd91581255a212fc0ff51a62c4e9c4b56a9782610a6a0ab6c40a71f3e49",
        ),
        (
            "broad-all-64/global-wide-four-properties",
            "7a41ab8af71576ca9e872e408a216229430c490a2a75a8d98aa238d7112ebe49",
            "7a41ab8af71576ca9e872e408a216229430c490a2a75a8d98aa238d7112ebe49",
            "bfa9ff18fe7ac280409448431ff9929243be209724717949fe41346de9539de6",
            "bfa9ff18fe7ac280409448431ff9929243be209724717949fe41346de9539de6",
        ),
        (
            "schema-label-histogram",
            "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
            "07ae98d5ad31558407b0da905e76bbc696f89e229c91584711536fa38708de25",
            "af893672357699830c1a555c8cb0586e68a18310c3171bb14db7db4d819ed6c7",
            "af893672357699830c1a555c8cb0586e68a18310c3171bb14db7db4d819ed6c7",
        ),
        (
            "schema-key-histogram",
            "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
            "aac00c0011b00c13fd5ec9bebd1bc6a52f94e6eaa823668d19e35af9f25c3c8c",
            "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
            "882fdcb99b731cc8ba239aaa640a4d22803ea8f29d5d2b28c11b79fb5f9aad79",
        ),
        (
            "schema-relationship-histogram",
            "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
            "f3363bdea15a4ac2e7e892cd232e97aba7d1bd755f8bd6373bf06cbce776e46a",
            "b989ecf27a50d74f82af0b16739dbdc613823db7a7ddb2f9dc7fe7f3df4c1557",
            "b989ecf27a50d74f82af0b16739dbdc613823db7a7ddb2f9dc7fe7f3df4c1557",
        ),
        (
            "shape-wide-contains",
            "93876fd68d6103ec49088d957d5b2ed4e16967ff5d6a31754ea673d0034c6bb3",
            "93876fd68d6103ec49088d957d5b2ed4e16967ff5d6a31754ea673d0034c6bb3",
            "4c3e3554b917a8c153c4e2fd6e765d469c35c44b579ec3df4ddb5d537dfb43b4",
            "4c3e3554b917a8c153c4e2fd6e765d469c35c44b579ec3df4ddb5d537dfb43b4",
        ),
        (
            "shape-label-scan-callsite",
            "ef3fc90c8f8245ade483b1319504b7e05761870e686ec771fc8ca4a32ee8b22b",
            "ef3fc90c8f8245ade483b1319504b7e05761870e686ec771fc8ca4a32ee8b22b",
            "b08ea209275519942554cacd405379991ce408f101bb1f47e7b5587bd4e00813",
            "b08ea209275519942554cacd405379991ce408f101bb1f47e7b5587bd4e00813",
        ),
        (
            "shape-label-scan-method",
            "a4ab145c29a44c9c73521f428689997a587818dcf860d28614d2483aeb853895",
            "a4ab145c29a44c9c73521f428689997a587818dcf860d28614d2483aeb853895",
            "99e72de3708125c3712e8a0e8ee5bbb8aec67bac1a104959bff4b51c4c244241",
            "99e72de3708125c3712e8a0e8ee5bbb8aec67bac1a104959bff4b51c4c244241",
        ),
        (
            "shape-label-scan-constant",
            "36eb396d87edf3494167d7a2b05232be8a9109fa8c7cb3f6445974175bb91328",
            "36eb396d87edf3494167d7a2b05232be8a9109fa8c7cb3f6445974175bb91328",
            "e5733b9b2410aa383ad45bac0f4c9886057e59a4caa2b4699f5f66fe7ee7112c",
            "e5733b9b2410aa383ad45bac0f4c9886057e59a4caa2b4699f5f66fe7ee7112c",
        ),
        (
            "shape-label-scan-field",
            "61c28d12706cfe366212c4d65b2c50e528732a060951d740f0bd3eb9cae9f550",
            "61c28d12706cfe366212c4d65b2c50e528732a060951d740f0bd3eb9cae9f550",
            "caf86fbeccc186b9b34e6ef948353ea96d73148c6628535b00625ef52f267b1b",
            "caf86fbeccc186b9b34e6ef948353ea96d73148c6628535b00625ef52f267b1b",
        ),
        (
            "shape-all-nodes",
            "ffa35341b8af522a820f76eed006980e6ac94406ccd21811dbd9ee635b6677b1",
            "ffa35341b8af522a820f76eed006980e6ac94406ccd21811dbd9ee635b6677b1",
            "c1738ba743cfc7a40ee52e2f5565f369ff0ade90bcd2dcac81f2b9dbcf14a09f",
            "c1738ba743cfc7a40ee52e2f5565f369ff0ade90bcd2dcac81f2b9dbcf14a09f",
        ),
        (
            "shape-equality",
            "c43182394f50246b2e9c89319e8f166065491b976966d716f9b2b7c879bff260",
            "c43182394f50246b2e9c89319e8f166065491b976966d716f9b2b7c879bff260",
            "a3e45c4cb769726835dd5818bc112e13668d9a00b0debcbc73d81e54000dd521",
            "a3e45c4cb769726835dd5818bc112e13668d9a00b0debcbc73d81e54000dd521",
        ),
        (
            "shape-inequality",
            "220a040b7e8b1d70fb8f38185f0fd7bfa838ae906f8d945af4ba3398630c684d",
            "220a040b7e8b1d70fb8f38185f0fd7bfa838ae906f8d945af4ba3398630c684d",
            "35978460009380a9f275e66134c5e4013692c4af4935210746dfbac6ba99cc05",
            "35978460009380a9f275e66134c5e4013692c4af4935210746dfbac6ba99cc05",
        ),
        (
            "shape-id-lookup",
            "169f3d9eab075cdc2f38aecaa86a06cc37422b307cd16ad20247ec1a86a870a7",
            "169f3d9eab075cdc2f38aecaa86a06cc37422b307cd16ad20247ec1a86a870a7",
            "ff67961c60f35d835568256b882974c02f12312cc95aec950d919282587f0af6",
            "0389b15588eeb74fee5beea7dd9d8e671465883ce83bf1f940a6e955894a331f",
        ),
        (
            "shape-in-list",
            "ee43ec33151492de0a432600d1b22750f534e6a2940f235a15840b5887a57e80",
            "ee43ec33151492de0a432600d1b22750f534e6a2940f235a15840b5887a57e80",
            "11ca0bac7ec82cf5a796842efbe0dd2e2bcb09cd4537fb31c6e8b4c812b2dc94",
            "11ca0bac7ec82cf5a796842efbe0dd2e2bcb09cd4537fb31c6e8b4c812b2dc94",
        ),
        (
            "shape-null-check",
            "7600ed77337a5ce0060e5030848b905cdd6a0d7970b25942cca4592fc0c66300",
            "7600ed77337a5ce0060e5030848b905cdd6a0d7970b25942cca4592fc0c66300",
            "35978460009380a9f275e66134c5e4013692c4af4935210746dfbac6ba99cc05",
            "35978460009380a9f275e66134c5e4013692c4af4935210746dfbac6ba99cc05",
        ),
        (
            "shape-regex",
            "dbb2006f6ec42c960f06b6bf16cad9d7a4f2f93c0b21153799485e12e35fea95",
            "dbb2006f6ec42c960f06b6bf16cad9d7a4f2f93c0b21153799485e12e35fea95",
            "0dfac0769bf0050b6e84e2ed0d2c6aea7da633ec9d446ad67a4f111fb5d947e9",
            "0dfac0769bf0050b6e84e2ed0d2c6aea7da633ec9d446ad67a4f111fb5d947e9",
        ),
        (
            "shape-one-hop",
            "fe48eaa25cafe412b60316026609aa73a6035358ba4fa88cb578f55c86ceda5b",
            "fe48eaa25cafe412b60316026609aa73a6035358ba4fa88cb578f55c86ceda5b",
            "1e2339e4baa2a762e6e523469278f292a00b272c84340efcd567196e327630e8",
            "1e2339e4baa2a762e6e523469278f292a00b272c84340efcd567196e327630e8",
        ),
        (
            "shape-one-hop-typed",
            "bf6f5042d8cf8a082930407b2eb4ec09b8d5008ddbb9a0ed6df5444317fb4a1f",
            "bf6f5042d8cf8a082930407b2eb4ec09b8d5008ddbb9a0ed6df5444317fb4a1f",
            "f03d60a48b7f7590553b205073011b9c87f508b209580a338381ce0a608dc053",
            "f03d60a48b7f7590553b205073011b9c87f508b209580a338381ce0a608dc053",
        ),
        (
            "shape-two-hop",
            "2fc55556115e8aa917aa4638706649cb645453c06d0c17f0ac39350b60202d23",
            "2fc55556115e8aa917aa4638706649cb645453c06d0c17f0ac39350b60202d23",
            "96504878cff79f22a0318fe1c8e6da870535f0592bee3c593c57eb9fb44b4d59",
            "96504878cff79f22a0318fe1c8e6da870535f0592bee3c593c57eb9fb44b4d59",
        ),
        (
            "shape-incoming",
            "7fa551be7b95270423109861b0cb77b358417bc9cd734eaaf13006d64604a40d",
            "7fa551be7b95270423109861b0cb77b358417bc9cd734eaaf13006d64604a40d",
            "98b0c96c63849091b5dd0f2399d3a931bfa05fbbe787629041c42e90eb8b22f1",
            "98b0c96c63849091b5dd0f2399d3a931bfa05fbbe787629041c42e90eb8b22f1",
        ),
        (
            "shape-var-length",
            "bbca77bd13a3c89dd634a3c4818730ffc1d4a1203bcfce37bfcbec4764dae24c",
            "bbca77bd13a3c89dd634a3c4818730ffc1d4a1203bcfce37bfcbec4764dae24c",
            "7222dc22354bd2001062501f9395837ed148a3f207d255c9322ae5f96cd88287",
            "7222dc22354bd2001062501f9395837ed148a3f207d255c9322ae5f96cd88287",
        ),
        (
            "shape-path",
            "3f07a6904220fc88bcaad6d93152d16f3c10a6f6bf7ad7f1f718f3ca03e93ddb",
            "3f07a6904220fc88bcaad6d93152d16f3c10a6f6bf7ad7f1f718f3ca03e93ddb",
            "29302046aeaf6dfcaadbfd0d6e9923f6e2a6c99773efc6b8b79a9eba097b9b9b",
            "29302046aeaf6dfcaadbfd0d6e9923f6e2a6c99773efc6b8b79a9eba097b9b9b",
        ),
        (
            "shape-traverse-filtered",
            "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
            "115eee701b50832840734f1d17e85159c34e2915ffa3c9af247908157496598d",
            "8931dd2e91cc80779b6aaf0ab3fc8d9b800d51e55e5a585eb2a20b7a56e7a870",
            "448d1bad5acc64dfd5859735c6914ab3b3062a66fb7eba453b1b831e3d3647e7",
        ),
        (
            "shape-count-all",
            "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
            "586fa3f5cb7871402ce88be4dc31639cdd552857589b6a998c8be6002b7d0db5",
            "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
            "0bf8a659f9dba13fc7953d50fed7c72a27e49b731bbf5745d7f9151147aea7bc",
        ),
        (
            "shape-count-label",
            "c610b36404891c86d63224dcdc151687ef70f50828493c03013ffe046c487ddc",
            "c610b36404891c86d63224dcdc151687ef70f50828493c03013ffe046c487ddc",
            "86c04aa6b72bcbeae689190f5076f9139ea0c479fcdcba03629a0a078f2b1f62",
            "86c04aa6b72bcbeae689190f5076f9139ea0c479fcdcba03629a0a078f2b1f62",
        ),
        (
            "shape-group-count",
            "7f71983908cab00265b8280df5a69a87e94aa2a7fa926d2798c99bcb25fea9a8",
            "7f71983908cab00265b8280df5a69a87e94aa2a7fa926d2798c99bcb25fea9a8",
            "243ad94a738bb9f4b6632d15e945ab4d091839a75a92c603cd24c867b861736b",
            "243ad94a738bb9f4b6632d15e945ab4d091839a75a92c603cd24c867b861736b",
        ),
        (
            "shape-distinct",
            "d61e543f7fc136a73d16b035c114634cea576eb2e81cb676de247132f16562ea",
            "d61e543f7fc136a73d16b035c114634cea576eb2e81cb676de247132f16562ea",
            "14a33c734a703069a77080d733418a23fbe1b6bff6a6bccc7c5444e9e9618ba4",
            "14a33c734a703069a77080d733418a23fbe1b6bff6a6bccc7c5444e9e9618ba4",
        ),
        (
            "shape-collect",
            "920a011a3f0fd70b86c8e96978ec8828fd67b07c4b21f46b1ae0dce3adac6261",
            "920a011a3f0fd70b86c8e96978ec8828fd67b07c4b21f46b1ae0dce3adac6261",
            "2f32327eb8de6fd78df2f9afe1103dafcf0d49f2b06733cb58b3a18f3d1229dc",
            "2f32327eb8de6fd78df2f9afe1103dafcf0d49f2b06733cb58b3a18f3d1229dc",
        ),
        (
            "shape-count-traversal",
            "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
            "0c0eb528ce456f3b8a996edfec5f51ada004216adf66e0261d67696a81ec6801",
            "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
            "1fd1901075bd8b865cbbb9da03147a619373f9ade33c574c301cf03f239878a7",
        ),
        (
            "shape-order-by",
            "88cb04da13ed7fcb5a8eb263eda499cad1bedb7808f5cf3eef0d6826e0e29744",
            "88cb04da13ed7fcb5a8eb263eda499cad1bedb7808f5cf3eef0d6826e0e29744",
            "6270291bcd5b883b6f9212dcb906d573e2a2ba861d76cebe4d9dd44514714b4a",
            "6270291bcd5b883b6f9212dcb906d573e2a2ba861d76cebe4d9dd44514714b4a",
        ),
        (
            "shape-order-by-desc",
            "5027d879d533d800096e2d9fbaeecb7a409f5a8744a27401dc97f82cb2f17ad0",
            "5027d879d533d800096e2d9fbaeecb7a409f5a8744a27401dc97f82cb2f17ad0",
            "92397c7279182fe6255c57e0424cb13cbc48e8bb6b980b84e903b0dd54b1196a",
            "92397c7279182fe6255c57e0424cb13cbc48e8bb6b980b84e903b0dd54b1196a",
        ),
        (
            "shape-skip",
            "2a7f57057d9f6182cd0b65fc3ced3f7a4bb1952cffcc074bae52f551c4abc680",
            "2a7f57057d9f6182cd0b65fc3ced3f7a4bb1952cffcc074bae52f551c4abc680",
            "f298adbb48b11632ca4165bd570f8dd71ba7c15ad56bc33f24281cec335f0a1b",
            "f298adbb48b11632ca4165bd570f8dd71ba7c15ad56bc33f24281cec335f0a1b",
        ),
        (
            "shape-function-calls",
            "7cc12fdd157088ab19b33a3d6b3c735b0e990668e9eb535388edf239f0885fcc",
            "7cc12fdd157088ab19b33a3d6b3c735b0e990668e9eb535388edf239f0885fcc",
            "ec6831a04511289ea9cc4fc10458e93b9cec3611f3d1e5bc81c34eb55cdd54d5",
            "ec6831a04511289ea9cc4fc10458e93b9cec3611f3d1e5bc81c34eb55cdd54d5",
        ),
        (
            "shape-case-expr",
            "a239e4bd5ce026f72684b26c46cb4c9e101cc800ef31fe48a5fab4b1cb73509a",
            "a239e4bd5ce026f72684b26c46cb4c9e101cc800ef31fe48a5fab4b1cb73509a",
            "690044fc509f6770e5e0ac2a6ee876d75f20ffa1b6bd184c653fe232d92408f2",
            "690044fc509f6770e5e0ac2a6ee876d75f20ffa1b6bd184c653fe232d92408f2",
        ),
        (
            "shape-coalesce",
            "059f17f974f39b2c1bc00f1fe3782592e0b67f83a2b38f29a25b366ef431022e",
            "059f17f974f39b2c1bc00f1fe3782592e0b67f83a2b38f29a25b366ef431022e",
            "759336d1863731e4db4f90c796289cc3475e57e23b1edabb5bf419325ad002a1",
            "759336d1863731e4db4f90c796289cc3475e57e23b1edabb5bf419325ad002a1",
        ),
        (
            "shape-labels-fn",
            "958b0c85ff86238ef3cbba0891181b49bc9b6782936597d4e746d02d87d11917",
            "958b0c85ff86238ef3cbba0891181b49bc9b6782936597d4e746d02d87d11917",
            "8e60a3b10577ce1e14d20885f32c16de588475b8eb0154e6493471711114693c",
            "8e60a3b10577ce1e14d20885f32c16de588475b8eb0154e6493471711114693c",
        ),
        (
            "shape-with-filter",
            "1100d048a0d279d87643fd15810de59ad71187854262f28e9a14571dbe7ac26d",
            "1100d048a0d279d87643fd15810de59ad71187854262f28e9a14571dbe7ac26d",
            "51830d2cc6a7bd7374526a98cea5e39ae263c79e2735ce9cdc9497c9171e9119",
            "51830d2cc6a7bd7374526a98cea5e39ae263c79e2735ce9cdc9497c9171e9119",
        ),
        (
            "shape-with-aggregate",
            "be7b22e552251ee59d665d048dc80bab6a3beb13634f31a375dffc0ba04b1d32",
            "be7b22e552251ee59d665d048dc80bab6a3beb13634f31a375dffc0ba04b1d32",
            "bd258a2aa48391c6b415680b023e5d6a86f052d9887be11e54aacd5298b90d81",
            "bd258a2aa48391c6b415680b023e5d6a86f052d9887be11e54aacd5298b90d81",
        ),
        (
            "shape-cartesian",
            "c4e256ff0ca5177f28608ede80ddceb5090cd36052a228756ee7b3e3c124009d",
            "c4e256ff0ca5177f28608ede80ddceb5090cd36052a228756ee7b3e3c124009d",
            "048f7d54ac9b351386d3d5771017e09908bca6c4bdb0a59f1144efd98af686a6",
            "048f7d54ac9b351386d3d5771017e09908bca6c4bdb0a59f1144efd98af686a6",
        ),
        (
            "shape-union",
            "a61e7b84e438e36530b5357b395bb8144e49a95aa0dc50e1f0e1b66844d441f8",
            "a61e7b84e438e36530b5357b395bb8144e49a95aa0dc50e1f0e1b66844d441f8",
            "b9d03fc6d73ea9182e717c78fed119008ed272b347fe51b9817aafa02d286852",
            "b9d03fc6d73ea9182e717c78fed119008ed272b347fe51b9817aafa02d286852",
        ),
    ];

    fn observations() -> Vec<Observation> {
        PR229_OBSERVATIONS
            .iter()
            .map(|&(case, base_query, head_query, base, head)| Observation {
                case_id: case.into(),
                base_query_sha256: base_query.into(),
                head_query_sha256: head_query.into(),
                base_response_sha256: base.into(),
                head_response_sha256: head.into(),
            })
            .collect()
    }

    fn errors(result: &Value) -> Vec<String> {
        result["errors"]
            .as_array()
            .unwrap()
            .iter()
            .map(|error| error.as_str().unwrap().to_owned())
            .collect()
    }

    /// Flips the last hex digit of a digest: still a well-formed SHA-256, another value.
    fn altered(digest: &str) -> String {
        let (head, last) = digest.split_at(digest.len() - 1);
        format!("{head}{}", if last == "0" { "1" } else { "0" })
    }

    #[test]
    fn the_fixture_is_the_run_seventy_three_cases_two_of_them_different() {
        let observations = observations();
        assert_eq!(observations.len(), 73);
        let unique: BTreeSet<_> = observations.iter().map(|o| o.case_id.as_str()).collect();
        assert_eq!(unique.len(), 73);
        let different: Vec<_> = observations
            .iter()
            .filter(|o| {
                o.base_query_sha256 != o.head_query_sha256
                    || o.base_response_sha256 != o.head_response_sha256
            })
            .map(|o| o.case_id.as_str())
            .collect();
        assert_eq!(different, REVIEWED);
        assert!(observations
            .iter()
            .all(|o| o.base_query_sha256 == o.head_query_sha256));
    }

    #[test]
    fn the_reviewed_pair_approves_two_transitions_and_seventy_one_exact_cases() {
        let result = evaluate(BASE, HEAD, &observations());
        assert_eq!(result["approved"], true, "{:?}", errors(&result));
        assert_eq!(result["exact"], false);
        let cases = result["cases"].as_array().unwrap();
        let reviewed: Vec<_> = cases
            .iter()
            .filter(|case| case["mode"] == "reviewed-transition")
            .map(|case| case["caseId"].as_str().unwrap())
            .collect();
        assert_eq!(reviewed, REVIEWED);
        assert_eq!(
            cases.iter().filter(|case| case["mode"] == "exact").count(),
            71
        );
        assert!(cases.iter().all(|case| case["approved"] == true));
    }

    #[test]
    fn another_identity_pair_approves_nothing() {
        let mut pairs = vec![(HEAD, BASE), (BASE, BASE)];
        for old in OLD_HEADS {
            pairs.extend([(BASE, old), (old, HEAD)]);
        }
        for (base, head) in pairs {
            let result = evaluate(base, head, &observations());
            assert_eq!(result["approved"], false, "{base} -> {head}");
            let errors = errors(&result);
            assert_eq!(errors.len(), 2, "{errors:?}");
            for (error, case) in errors.iter().zip(REVIEWED) {
                assert_eq!(
                    error,
                    &format!(
                        "{case}: No compiled-in transition approves this directed source and case difference"
                    )
                );
            }
        }
    }

    #[test]
    fn a_changed_query_or_response_is_not_the_reviewed_transition() {
        type Field = fn(&mut Observation) -> &mut String;
        let fields: [Field; 4] = [
            |o| &mut o.base_query_sha256,
            |o| &mut o.head_query_sha256,
            |o| &mut o.base_response_sha256,
            |o| &mut o.head_response_sha256,
        ];
        for case in REVIEWED {
            for field in fields {
                let mut observations = observations();
                let observation = observations.iter_mut().find(|o| o.case_id == case).unwrap();
                let value = field(observation);
                *value = altered(value);
                let result = evaluate(BASE, HEAD, &observations);
                assert_eq!(result["approved"], false, "{case}");
                assert_eq!(
                    errors(&result),
                    [format!(
                        "{case}: Observed query or complete typed response differs from reviewed expectation"
                    )]
                );
            }
        }
        // A reviewed case that is missing, and an exact case that changes, fail too.
        let without: Vec<_> = observations()
            .into_iter()
            .filter(|o| o.case_id != REVIEWED[0])
            .collect();
        assert_eq!(
            errors(&evaluate(BASE, HEAD, &without)),
            [format!(
                "Missing observation for reviewed transition: {}",
                REVIEWED[0]
            )]
        );
        let mut drifted = observations();
        drifted[0].head_response_sha256 = altered(&drifted[0].head_response_sha256);
        let result = evaluate(BASE, HEAD, &drifted);
        assert_eq!(result["approved"], false);
        assert_eq!(errors(&result).len(), 1);
    }

    #[test]
    fn each_rule_is_needed() {
        let ours = |rule: &Rule<'_>| {
            rule.base_product_identity == BASE && rule.head_product_identity == HEAD
        };
        assert_eq!(RULES.iter().filter(|rule| ours(rule)).count(), 2);
        for case in REVIEWED {
            let rules: Vec<Rule<'_>> = RULES
                .iter()
                .copied()
                .filter(|rule| !(ours(rule) && rule.case_id == case))
                .collect();
            let result = evaluate_rules(BASE, HEAD, &observations(), &rules);
            assert_eq!(result["approved"], false, "{case}");
            assert_eq!(
                errors(&result),
                [format!(
                    "{case}: No compiled-in transition approves this directed source and case difference"
                )]
            );
        }
    }
}
