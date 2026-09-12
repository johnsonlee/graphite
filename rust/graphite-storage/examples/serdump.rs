use graphite_storage::javaser::{read_object, Value};
fn show(v: &Value, depth: usize) {
    let pad = "  ".repeat(depth);
    match v {
        Value::Object { class, fields } => {
            println!("{pad}Object {class}");
            for (k, v) in fields {
                print!("{pad}  .{k} = ");
                show(v, depth + 2);
            }
        }
        Value::CharArray(c) => println!("CharArray[{}]", c.len()),
        Value::ByteArray(c) => println!("ByteArray[{}]", c.len()),
        Value::IntArray(c) => println!("IntArray[{}]", c.len()),
        Value::LongArray(c) => println!("LongArray[{}]", c.len()),
        Value::ObjArray(items) => {
            println!("ObjArray[{}]", items.len());
            for it in items.iter().take(3) {
                print!("{pad}    ");
                show(it, depth + 3);
            }
        }
        other => println!("{other:?}"),
    }
}
fn main() {
    let data = std::fs::read(std::env::args().nth(1).unwrap()).unwrap();
    match read_object(&data) {
        Ok(v) => show(&v, 0),
        Err(e) => println!("ERR {e}"),
    }
}
