# graphite-schema

[English](README.md) | 简体中文

`graphite-schema` 是 Graphite 的 Rust 库，用来**描述和交换图记录，而不把某种编程语言的节点或类型系统写死在编解码器中**。它提供共享数据模型、结构校验、二进制编码／解码，以及按索引访问 schema 记录的 mmap reader。

前端可以在输出的数据中描述新的节点类别或类型运算。即使读取器编译时还没有这些定义，它仍能检查字段、跟随引用、与其他文档合并，并重新保存，保留其中尚不认识的信息。这是新增语言时无需逐一修改通用 codec 的基础。

## 在架构中的位置

计划中的处理流程是：

```text
语言前端 → schema 记录 + 定义 → Rust 索引器 → 生产图
                    ↑
             graphite-schema
```

本 crate 实现 schema 记录、独立交换 codec，以及支持 mmap 按需读取的索引格式。前端输出和生产索引器接入仍待实现；当前 JVM 构图、v1–v3 图读取器和 Cypher 引擎尚未使用此库。

例如，JVM 的 `List<User>` 和 Swift 的 `Array<User>` 都可以表示为类型应用，并显式引用各自的声明和实参。未来语言可以通过注册另一个定义增加不同的运算。codec 负责保留结构；判断两个类型是否兼容的规则由语言前端或分析器提供。

## 提供的能力

| API | 用途 |
| --- | --- |
| `Document`、`Definition`、`Field`、`Descriptor` | 定义具名 layout、字段、表、profile 和字符串字典 |
| `Record`、`Value`、`Reference` | 表示值和显式引用，支持嵌套记录及通过引用形成的环 |
| `Document::validate` | 校验 layout、字段类型、必填／null 规则、引用目标和资源限制 |
| `encode` / `decode` | 用 `GSCHEMA/1` 写入／读取完整内存文档 |
| `encode_mapped` | 从已校验的文档写出带索引的 `GSCHEMA/2` 字节 |
| `MappedDocument::from_bytes` / `open` | 读取索引字节或 mmap 文件，不全量解码记录和字符串 |
| `MappedDocument::record` / `string` | 按 ID 解码单条记录或借用单个字符串，访问时校验 |
| `MappedDocument::string_ids` / `records` | 直接从映射的目录枚举 ID 和 layout |
| `MappedDocument::verify_all` | 显式逐个校验全部 payload |
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

下面的完整示例创建一个此前未知的类型运算，编码后通过索引读取器访问字段，无需安装语言插件。schema 定义和字符串字典随记录一起传递。

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
    let bytes = encode_mapped(&document, &limits)?;
    let mapped = MappedDocument::from_bytes(bytes, MappedLimits::default())?;
    let record = mapped.record(Reference { table: 3, row: 42 })?.unwrap();
    let Some(Value::String(id)) = mapped.metadata().field(&record, &name("mode")) else {
        panic!("mode must reference the string dictionary");
    };
    assert_eq!(mapped.string(*id)?, Some("region-local"));
    assert!(mapped.metadata().strings.is_empty());
    assert!(mapped.metadata().tables[&3].rows.is_empty());
    mapped.verify_all()?;
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

- [映射访问](tests/mapped_access.rs)：独立 wire fixture、未知 layout、稀疏 ID、按需校验，以及使用不可访问内存页证明打开和指定读取不访问无关 payload 的 Unix 子进程测试。
- [存储接入](../storage/tests/schema_mapped_source.rs)：目录与 STORED ZIP 条目直接使用现有共享 mmap，无复制或解包。

## mmap 访问及当前边界

`MappedDocument<B>` 持有任意 `B: AsRef<[u8]>` backing。`from_bytes` 可以接收借用切片或已有的映射区间，包括 Graphite storage 层提供的目录文件或未压缩 `.graphite` ZIP entry 区间；它不复制 backing 字节。示例传入 `Vec<u8>`，使用相同的索引访问 API，但这一步本身不会建立 OS 内存映射。

`unsafe MappedDocument::open(path, limits)` 使用 `memmap2` 映射独立文件。调用者必须保证整个映射存活期间文件不会被修改或截断，包括其他进程的操作。这是文件 mmap 的安全契约，schema 校验无法强制保证。

打开时只解码预算内的 schema 元数据，并扫描持久化目录，检查 ID 排序、layout 和范围；不解码或计算字符串／记录 payload 的校验和，也不构造随行数增长的堆上索引。`record` 对持久化目录做二分查找，只在独立预算内解码选中的记录；`string` 检查选中的 UTF-8 payload 并返回借用的 `&str`。引用通过目录检查目标是否存在，不解码目标。未知 layout 走相同路径。未访问 payload 的损坏在访问时或显式执行 `verify_all` 时发现，不一定在打开时发现。

`metadata()` 包含定义、profile 和具名表，表的行映射和字符串字典均为空；数据通过 mapped accessor 访问。writer 仍接收完整内存 `Document`，用 `Limits.max_bytes` 限制整个输出（默认 64 MiB），尚不是流式 corpus 索引器。

`MappedLimits` 将元数据预算、单条记录预算与总文件字节数、字符串数、行数限制分开。默认 `Limits` 的 64 MiB 字节预算不会作为 mapped 图全部 payload 的总上限。限制约束工作量和分配，不等于进程总 RSS 上限。引用环不会触发递归展开。

此 crate 不是图数据库、语言解析器、类型求解器或 Cypher 引擎。两个 wire 版本都独立于持久化图版本，不能直接交给现有图读取器。生产 writer／loader／query 接入和旧 corpus 迁移仍待完成。跨语言互操作测试、真实 corpus 的加载 RSS／查询测量也尚未完成；正确性 fixture 不能证明吞吐或 100M 节点容量。详见[存储硬约束](../../docs/graph-schema.zh.md#必须满足的-mmap-读取约束)。

精确的编码、校验和版本规则见[二进制契约](../../docs/schema-wire.zh.md)，整体架构见[通用 schema 方案](../../docs/graph-schema.zh.md)，后续前端及 corpus 工作见 [JVM 迁移计划](../../docs/jvm-generic-types.zh.md)。
