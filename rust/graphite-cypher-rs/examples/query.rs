use graphite_cypher::engine::{Executor, Source};
use graphite_cypher::materialize::materialize;
use graphite_storage::Graph;
use std::sync::Arc;
use std::time::Instant;

fn main() {
    let mut args = std::env::args().skip(1);
    let dir = args.next().expect("graph dir");
    let query = args.next().expect("query");
    let t = Instant::now();
    let g = Arc::new(Graph::load(std::path::Path::new(&dir)).expect("load"));
    eprintln!("loaded in {:?}", t.elapsed());
    let ex = Executor::single("standalone", g);
    let t = Instant::now();
    match ex.execute(&query, Some(1000)) {
        Ok(r) => {
            let elapsed = t.elapsed();
            eprintln!("query in {elapsed:?} -> {} rows", r.rows.len());
            println!("columns: {:?}", r.columns);
            for row in r.rows.iter().take(5) {
                let obj: serde_json::Map<String, serde_json::Value> = r
                    .columns
                    .iter()
                    .map(|c| {
                        (
                            c.clone(),
                            row.get(c)
                                .map(|v| materialize(v, &ex))
                                .unwrap_or(serde_json::Value::Null),
                        )
                    })
                    .collect();
                println!("{}", serde_json::to_string(&obj).unwrap());
            }
        }
        Err(e) => eprintln!("ERROR: {e}"),
    }
}
