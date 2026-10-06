# graphite-schema

[English](README.md) | 简体中文

`graphite-schema` 是 Graphite 的 Rust 库，用来**描述和交换图记录，而不把某种编程语言的节点或类型系统写死在编解码器中**。它提供共享数据模型、结构校验和二进制编码／解码。

前端可以在输出的数据中描述新的节点类别或类型运算。即使读取器编译时还没有这些定义，它仍能检查字段、跟随引用、与其他文档合并，并重新保存，保留其中尚不认识的信息。这是新增语言时无需逐一修改通用 codec 的基础。

## 在架构中的位置

计划中的处理流程是：

```text
语言前端 → schema 记录 + 定义 → Rust 索引器 → 生产图
                    ↑
             graphite-schema
```

本 crate 实现 schema 记录及其独立交换格式的 codec。前端输出和生产索引器接入仍待实现；当前 JVM 构图、v1–v3 图读取器和 Cypher 引擎尚未使用此库。

例如，JVM 的 `List<User>` 和 Swift 的 `Array<User>` 都可以表示为类型应用，并显式引用各自的声明和实参。未来语言可以通过注册另一个定义增加不同的运算。codec 负责保留结构；判断两个类型是否兼容的规则由语言前端或分析器提供。

## 提供的能力

| API | 用途 |
| --- | --- |
| `Document`、`Definition`、`Field`、`Descriptor` | 定义具名 layout、字段、表、profile 和字符串字典 |
| `Record`、`Value`、`Reference` | 表示值和显式引用，支持嵌套记录及通过引用形成的环 |
| `Document::validate` | 校验 layout、字段类型、必填／null 规则、引用目标和资源限制 |
| `encode` / `decode` | 写入／读取确定性编码、带校验和的 `GSCHEMA` wire-version-1 容器 |
| `Document::record`、`field`、`records_of` | 按结构检查记录，即使不认识其语义名称 |
| `Document::remap` | 改写所有本地 ID，包括嵌套列表和记录中的引用 |
| `Document::merge` | 合并 ID 重叠的文档，保留独立行并拒绝冲突定义 |

定义具有带命名空间的名称和 revision。节点类别、operator、predicate 和 role 都是数据，不是语言专属的 Rust enum。有限的 `Descriptor` enum 表示整数、字符串、列表、记录和引用等物理值形式。

codec 区分字段缺失与显式 null，保留浮点数的精确位模式、列表的顺序及重复项，以及独立寻址的平行关系。本地 ID 是地址，不是语义身份：合并两个都叫 `T` 的声明，不会合并它们的行或作用域。

## 快速上手

此 crate 当前在 Graphite workspace 中开发。`backend/` 下的同级 crate 可以在 `Cargo.toml` 中添加以下依赖；其他位置需调整路径。此示例不假定已有 crates.io 发布版本。

```toml
[dependencies]
graphite-schema = { path = "../schema" }
```

下面的完整示例创建一个此前未知的类型运算，编码后再解码，然后在不安装语言插件的情况下读取字段。schema 定义和字符串字典随记录一起传递。

```rust
use graphite_schema::*;
use std::collections::BTreeMap;

fn main() -> Result<()> {
    let name = |local| QualifiedName::new("example.future", local);
    let document = Document {
        strings: BTreeMap::from([(0, "region-local".into())]),
        definitions: BTreeMap::from([(7, Definition {
            name: name("region-type"),
            revision: "1".into(),
            category: QualifiedName::new("core", "expression"),
            fields: vec![Field {
                name: name("mode"),
                descriptor: Descriptor::String,
                required: true,
                nullable: false,
                role: None,
            }],
        })]),
        tables: BTreeMap::from([(3, Table {
            name: name("types"),
            rows: BTreeMap::from([(42, Record {
                layout: 7,
                fields: vec![Some(Value::String(0))],
            })]),
        })]),
        ..Document::default()
    };

    let limits = Limits::default();
    let bytes = encode(&document, &limits)?;
    let restored = decode(&bytes, &limits)?;
    let record = restored.record(Reference { table: 3, row: 42 }).unwrap();
    let Some(Value::String(id)) = restored.field(record, &name("mode")) else {
        panic!("mode must reference the string dictionary");
    };
    assert_eq!(restored.strings[id], "region-local");
    assert_eq!(restored, document);
    Ok(())
}
```

需要改变 ID 时应使用 `IdMap`，而非手工修改数值。`remap` 要求字符串、layout、表和行的映射完整且无冲突；`merge` 会为结构合并分配映射。所有本地引用必须使用显式的 `Reference` 值。将 ID 藏在字符串或字节 blob 中会使通用重映射失效，违反生产者契约。

## 验证修改

在仓库根目录运行：

```bash
cargo test -p graphite-schema --locked
cargo clippy -p graphite-schema --all-targets --locked -- -D warnings
cargo doc -p graphite-schema --no-deps --open
```

英文 README 同时作为 crate 的 Rust API 介绍，因此其中的 Rust 示例会由 `cargo test` 编译并执行。本中文版使用相同的示例。测试覆盖：

- [未知扩展](tests/unknown_extensions.rs)：17 种陌生节点类别、257 种 predicate、作用域绑定、嵌套／循环引用、完整 ID 重映射以及 ID 重叠文档的合并。断言检查逻辑目标和完整值。
- [Wire 校验](tests/wire_validation.rs)：独立组装的二进制样例、精确值编码、带正确校验和的非法数据、截断和资源限制。
- [模型测试](src/model.rs)：schema 冲突、非法引用、映射错误、稀疏 ID 分配和一致的编码／解码预算边界。

## 当前边界

这是内存中的逻辑文档 codec，不是图数据库、语言解析器、类型求解器、Cypher 引擎或最终的 mmap／列式图存储格式。`GSCHEMA` wire version 1 与持久化图版本独立；它输出的字节不能交给现有图读取器。其他语言可以按相同契约实现，但目前尚未加入跨语言互操作测试。

默认限制为 64 MiB 编码字节、1,000,000 个累计 item 和 64 层内联深度；深度硬上限为 256。允许引用环，不会递归展开。字节预算不意味着总 RSS 被限制为同样的数值。合成测试证明正确性，不能证明吞吐或 100M 节点容量。生产规模测量需要后续前端／索引器接入，并使用真实 corpus。

精确的编码、校验和版本规则见[二进制契约](../../docs/schema-wire.zh.md)，整体架构见[通用 schema 方案](../../docs/graph-schema.zh.md)，后续前端及 corpus 工作见 [JVM 迁移计划](../../docs/jvm-generic-types.zh.md)。
