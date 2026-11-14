//! Write a tiny persisted graph for HTTP/container correctness checks, never benchmarks.
fn main() {
    let output = std::env::args_os().nth(1).expect("output graph directory");
    let json = br#"{"version":1,"methods":[],"nodes":[{"id":0,"kind":"StringConstant","value":"docker-smoke"}],"edges":[]}"#;
    graphite_storage::interchange::import_json(json.as_slice(), std::path::Path::new(&output))
        .expect("write HTTP smoke fixture");
}
