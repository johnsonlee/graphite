use graphite_storage::node::*;
use graphite_storage::Graph;
use std::time::Instant;

fn main() {
    let dir = std::env::args().nth(1).expect("graph dir");
    let t = Instant::now();
    let g = Graph::load(std::path::Path::new(&dir)).expect("load");
    println!("loaded in {:?}", t.elapsed());
    println!(
        "nodes={} capacity={} edges={} methods={} strings={} callsites={}",
        g.node_count(),
        g.node_capacity(),
        g.edge_count(),
        g.method_count(),
        g.strings.len(),
        g.count_by_tag(TAG_CALL_SITE_NODE)
    );
    for tag in 0..16u8 {
        println!(
            "  tag {tag} {} = {}",
            tag_type_name(tag),
            g.count_by_tag(tag)
        );
    }
    // Decode every node to validate the record parser.
    let t = Instant::now();
    let mut n = 0usize;
    for id in g.all_ids() {
        let node = g.node(id).expect("node");
        assert_eq!(node.id, id);
        n += 1;
    }
    println!("decoded {n} nodes in {:?}", t.elapsed());
    // Show a few call sites
    for &id in g.ids_by_tag(TAG_CALL_SITE_NODE).iter().take(3) {
        let node = g.node(id).unwrap();
        if let NodeKind::CallSite {
            caller,
            callee,
            line,
            ..
        } = &node.kind
        {
            println!(
                "  cs {id}: {} -> {} line={:?} out={} in={}",
                caller.signature(&g.strings),
                callee.signature(&g.strings),
                line,
                g.out_degree(id),
                g.in_degree(id)
            );
            for e in g.outgoing(id).take(5) {
                println!("     -> {} {} {:?}", e.to, e.rest_type(), e.kind_name());
            }
        }
    }
    println!(
        "identity ok = {:?}",
        g.strings
            .identity()
            .map(|i| *i == g.strings.compute_identity())
    );
}
