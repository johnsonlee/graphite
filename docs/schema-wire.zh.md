# 通用 Schema 契约与二进制编解码器

[English](schema-wire.md) | 简体中文

状态：结构基础、`GSCHEMA/1` 交换 codec 及 `GSCHEMA/2` 带索引的 mmap reader 已实现。本契约实现了 [schema 方案](graph-schema.zh.md)中的值、注册表、引用、交换格式及映射记录访问。
实现位于独立的 Rust crate [`graphite-schema`](../backend/schema/README.zh.md)。
它不改变 v1–v3 图存储，不分配 graph v4 版本，也尚未将新记录接入生产索引器、Cypher 引擎或 JVM 前端。

## 契约边界

对于编译时尚未认识其名称和语言 profile 的记录，同一个编解码器必须能够解码、校验、检查、重映射、合并并重新编码。
新增语言通过添加定义和记录接入，不添加描述器 tag 或语言分支。未知的物理 tag 或 wire version 会被拒绝；未知的语义名称会被保留。

Wire version 1 承载用于内存交换的完整逻辑文档；version 2 为同一模型增加持久化目录，支持映射上的按需记录和字符串访问。两者都不是新的生产图版本。生产图索引、压缩邻接接入、大规模 corpus 流式处理、JVM IR 输出及旧格式导入留待后续实现；不对 100M 节点的性能作出承诺。

生产图 reader 必须遵守 Graphite 既有的 mmap 访问约束。映射记录应使用带索引的 reader；将 version-1 全量 Document 解码器的输入映射到内存不会避免堆上物化。此库之外仍需完成生产 loader／query 接入和真实 corpus 验收。

## 逻辑文档

所有本地 ID 均为无符号 32 位整数；零是有效 ID，ID 可以不连续。
字符串、定义（layout）、表以及每张表中的行分别使用独立的 ID 空间。
ID 标识位置，而非语义声明。ID 空间耗尽时操作失败，不允许整数回绕。
列表位置、显式序号字段和引用身份用于保留顺序；表中按行 ID 数值排列的顺序不具有语义，重映射后可以改变。

一个文档包含：

- 以 StringId 为键的字符串字典。允许不同 ID 对应相同字符串。
- 以 LayoutId 为键的定义注册表。
- 有序的语言 profile 列表，每项包含限定名称和修订版本。
- 以 TableId 为键的具名表，每张表包含以 RowId 为键的行。

限定名称由两个独立、非空的 UTF-8 字符串组成：命名空间和本地名称。
其表示方式不会根据标点猜测二者的分界。修订版本是非空 UTF-8 字符串，不要求按数字或语义化版本解释。
命名空间的归属和语义信任由 profile 规定，不构成下载代码的指令。

定义包含限定名称、修订版本、类别和有序字段列表。
字段包含限定名称、描述器、required 标记、nullable 标记和可选的限定角色名称。
类别和角色均为开放名称。同一定义内的字段名称必须唯一。
文档内的定义（名称、修订版本）对、表名称和 profile（名称、修订版本）对分别必须唯一；允许不同修订版本共存。
已发布的定义内容不可变；不兼容的修改必须使用新的修订版本。
合并时，先将目标表 ID 转换到共享的表命名空间，再比较完整定义；同一身份对应的内容不一致时拒绝合并。

记录包含 layout ID，并按 layout 顺序为每个字段保留且仅保留一个槽位。
每个槽位可以是缺失、显式 null 或带类型的值。required 要求字段存在；nullable 独立控制是否允许显式 null。
缺失字段不会被替换为某种语言的 `unknown`、`any`、`never` 或默认值。
列表中的 null 元素通过 Null 元素描述器表示；如果需要与其他值混合，则使用包含可空字段的记录包装。
异构联合值通过 Record 包装表示，不增加新的基础 tag。

| 描述器 | Rust 值 | 含义 |
| --- | --- | --- |
| Null | Null | null 字面量；直接作为字段值时还要求 nullable=true |
| Bool | Bool | 布尔值 |
| Int64 / UInt64 | Int64 / UInt64 | 精确的有符号／无符号 64 位整数 |
| Float64 | Float64(u64) | IEEE 754 位模式；保留带符号零和 NaN payload |
| String | String(StringId) | 指向文档字符串字典的引用 |
| Bytes | Bytes | 不包含本地图引用的原始字节 |
| Ref(Some(table)) | Ref { table, row } | 限定指向某张已存在表的引用 |
| Ref(None) | Ref { table, row } | 显式指向任意已存在表的引用 |
| List(element) | List | 满足同一描述器的有序值序列，允许重复 |
| Record | Record | 携带自身已注册 layout 的内联记录 |

每个引用目标都必须存在，包括未使用定义的字段描述器中声明的目标。
内联记录与顶层行使用相同的注册表。允许前向引用和通过 Ref 形成的循环；递归内联嵌套受深度限制。
原始字符串和 Bytes 不得隐藏需要重映射的本地 ID：结构校验无法推断隐藏引用，因此这是对生产者的契约要求。

映射通过条目记录列表表示，由 profile 声明键的唯一性和顺序规则。
Symbol 包含模块／profile 和声明身份；参数绑定包含作用域身份及序号。
显示名称相同不构成合并行的理由。声明、推断、解析和观测所得的类型通过不同记录或角色分别保留。

## 共同词汇映射

[方案](graph-schema.zh.md#通用领域记录)中的领域记录与未知扩展使用相同的定义机制。
Module、Symbol、Scope、Parameter、Expression、Constraint、Assertion、Node、Edge 和 TypeUse 是语义类别，不是新增的物理 tag。
profile 为其输出的记录发布具体的、带修订版本的 layout、字段角色、来源信息和提取完整性说明。

运算符、谓词和参数类别使用带命名空间的定义，或者使用显式引用指向描述这些定义的记录。
例如，名为 `("example.type", "region-qualified")` 的定义可以具有类别 `("core", "expression")`，并包含角色为 `("core.type", "operand")` 的字段。
方案中的示例映射为一个 layout，其 base 和 region 字段均为带类型的引用。
前端提供相应的目标表；编解码器不解释 `region-qualified` 的语义。

注册表允许在没有语义模块的情况下完成结构访问。
共享某个角色并不能证明子类型、可赋值性或符合性关系。规则不同的语言可以共享结构 layout，同时提供各自的分析器。
本次发布固定结构契约，不宣称语言映射或推断规则已经完备。
具体的 JVM／Swift／TS profile 修订版本必须与对应前端一同审查，无需为此修改 wire format。

## Version 1：交换封装

所有整数均采用小端序。UTF-8 必须严格有效。
`text` 表示 u64 字节长度及其后的对应字节；`name` 表示两个 text（命名空间、本地名称）。
计数使用 u32。格式不包含对齐填充、原生指针、时间戳、压缩或可执行 schema 代码。
完整文件依次包含以下字段：

| 偏移 | 宽度 | 内容 |
| --- | --- | --- |
| 0 | 8 | ASCII `GSCHEMA`，后接一个零字节 |
| 8 | 4 | Wire version，为 1 |
| 12 | 8 | payload 字节长度 |
| 20 | 32 | 整个 payload 的 SHA-256 |
| 52 | payload 长度 | 目录及随后各 section 的内容 |

payload 以 section 数量 4 开始，随后是按 kind 1 至 4 排列的四个目录项。
每个目录项包含 u32 kind、u64 长度和对应 section 的 32 字节 SHA-256。
各 section 的内容按该顺序连续排列。目录占 180 字节。
每个 section 和整个 payload 的哈希都必须匹配。不允许间隙、未知的封装 section 或尾随字节。
payload 哈希将目录、注册表、字典、profile 和表绑定在一起。
它是完整性检查，不是身份认证，也不是跨 ID 重映射保持不变的语义身份哈希。

| Kind | Section 内容 |
| --- | --- |
| 1: strings | count；重复的 (u32 StringId, text) |
| 2: profiles | count；重复的 (name, revision text) |
| 3: definitions | count；重复的 (u32 LayoutId, name, revision text, category name, field count, fields) |
| 4: tables | count；重复的 (u32 TableId, name, group count, groups) |

字段编码为 (name, descriptor, required u8, nullable u8, role-present u8, 可选的 role name)。
布尔标记只接受 0 和 1。描述器编码如下：

| Tag (u8) | 描述器 | 描述器附加字节 |
| --- | --- | --- |
| 0 | Null | 无 |
| 1 | Bool | 无 |
| 2 | Int64 | 无 |
| 3 | UInt64 | 无 |
| 4 | Float64 | 无 |
| 5 | String | 无 |
| 6 | Bytes | 无 |
| 7 | Ref | target-present u8；存在目标时追加 u32 TableId |
| 8 | List | 嵌套的元素描述器 |
| 9 | Record | 无 |

表分组包含 (u32 LayoutId, u32 row count, rows)。
行包含 (u32 RowId, u64 record-body length, record body)。
分组必须非空，且按 layout ID 严格递增；同一分组内的行必须按 row ID 严格递增。
RowId 在整张表内唯一，包括不同分组之间。空表没有分组。
编码器按字符串字典、定义和表的 ID 排序；解码器可以接受这些唯一 ID 的其他排列顺序。
profile 顺序以及所有字段和列表的顺序均被保留。

记录体以两个位图开始，每个位图均占 `ceil(field_count / 8)` 字节：先是 presence，随后是 null。
字段 i 使用对应字节中从最低有效位开始计算的第 i 个位置。填充位必须为零。
设置 null 位时必须同时设置对应的 presence 位。
随后按 layout 顺序编码存在且非 null 的字段值，不逐值存储类型 tag 或字段名称：

| 值 | 字节 |
| --- | --- |
| Null | 不占字节（直接字段使用其 null 位图位） |
| Bool | u8，必须为 0 或 1 |
| Int64 / UInt64 / Float64 | 8 字节，保留整数／浮点位模式 |
| String | u32 StringId |
| Bytes | u64 长度及随后的字节 |
| 同表 Ref | u32 RowId；目标表由描述器提供 |
| 任意表 Ref | 先 u32 TableId，后 u32 RowId |
| List | u32 元素数量，随后按元素描述器编码各元素 |
| 内联 Record | u32 LayoutId，随后是按该 layout 编码的记录体 |

描述器为 Null 的直接字段不能只设置 presence 而不设置 null。
每个行体和 section 都必须被完整、精确地消费。
缺失字段／表／layout／字符串、无效引用、冲突身份、不支持的 tag、格式错误的位图和多余字节均导致错误。
不会静默丢弃任何格式错误的记录，也不会将其替换为空对象。
新增语言只改变字典、定义和表的内容。

## Version 2：带索引的映射封装

`encode_mapped(&Document, &Limits)` 校验完整输入文档并写出此索引格式。`MappedDocument<B: AsRef<[u8]>>::from_bytes(B, MappedLimits)` 保留 backing 字节而不复制。`unsafe MappedDocument::open` 使用 `memmap2` 映射独立文件；调用者必须保证映射存活期间文件不会被修改或截断。已有的目录文件或 ZIP entry 映射区间可以使用 `from_bytes`，无需依赖 storage crate。

80 字节头部之后依次是元数据、字符串目录、行目录、全部字符串 payload 和全部记录 payload。整数采用小端序。区间连续、不重叠、恰好占满文件；保留字段必须为零。目录保留在 backing 字节中，使用二分查找。

| 偏移 | 宽度 | 内容 |
| --- | --- | --- |
| 0 | 8 | `GSCHEMA`，后接一个零字节 |
| 8 | 4 | Wire version，为 2 |
| 12 | 4 | 保留字段，零 |
| 16 | 8 | 文件总长度 |
| 24 | 8 | 元数据字节长度 |
| 32 | 8 | 字符串数量 |
| 40 | 8 | 所有表的总行数 |
| 48 | 32 | 索引 SHA-256 |

元数据是一个 version-1 封装，包含定义、profile 和具名表；表的行映射及字符串字典为空。`metadata()` 返回这个受预算限制的 `Document`，空数据映射不表示映射文件没有数据。

每个 56 字节字符串目录项为 `(u32 StringId, u32 reserved, u64 offset, u64 length, [u8; 32] SHA-256)`，按 StringId 严格递增排列。payload 是不带长度前缀的原始 UTF-8。每个 64 字节行目录项为 `(u32 TableId, u32 RowId, u32 LayoutId, u32 reserved, u64 offset, u64 length, [u8; 32] SHA-256)`，按 `(TableId, RowId)` 严格递增排列。offset 从此封装起点计算。记录 payload 使用上述 version-1 record body 编码，layout 由目录项提供。

索引 SHA-256 覆盖头部字节 `0..48`，随后是元数据及两个目录（`80..payload_start`）；不包含 digest 字段及字符串／行 body。因此无需读取 payload，就能绑定查找 ID、layout ID、范围及各 payload 的 digest。

打开时校验头部、索引哈希和 schema，并扫描目录检查排序、layout、表身份和范围。工作量为 O(目录项数)，堆使用受限；不承诺常数时间打开。打开不计算字符串／记录 payload 的校验和或解码它们，也不建立堆上行索引。

`record(Reference)` 返回 `Result<Option<Record>>`，在独立的单记录预算内解码并校验选中的记录。字段结构及嵌套字符串／行引用通过持久化目录校验；检查引用不读取或解码目标。`string(StringId)` 返回 `Result<Option<&str>>`，只检查该字符串的校验和与 UTF-8 并借用其字节。ID 不存在返回 `None`，选中数据损坏返回错误。未知 layout 使用相同的 schema 驱动路径，不回退到全量文档解码。

`verify_all()` 显式逐个访问全部字符串和记录。成功打开只证明元数据和目录结构已校验，不保证所有 payload 完好；未访问数据的损坏推迟到访问或 `verify_all` 时发现。校验和用于完整性检查，不是身份认证；生产容器的完整性检查仍是外层的额外校验。

`MappedLimits` 为元数据和单条记录分别提供 `Limits`，另有总文件大小、字符串数和行数限制。默认元数据和记录字节预算分别为 16 MiB 和 64 MiB，不对 mapped payload 额外施加累计 64 MiB 上限。writer 仍接收完整内存文档并返回字节向量，且用其 `Limits.max_bytes` 限制整个输出（默认 64 MiB）；此 API 尚未实现流式输出或生产索引构建。

持久化目录占每个字符串 56 字节、每行 64 字节，另加 payload 和元数据。例如 100M 行仅行目录就占 6.4 GB（十进制单位）；这是算术结果，不是容量基准。此库的索引格式尚未实现方案中的按 group／列组织的生产布局。

## 内存操作与资源限制

`encode(&Document, &Limits)` 在返回字节之前进行校验。
`decode(&[u8], &Limits)` 在返回文档之前检查封装、section 完整性、结构和所有引用。
二者均不需要语言插件。`record`、`field` 和 `records_of` 通过显式引用及限定名称提供结构访问，并非 Cypher 实现。
`records_of` 返回跨修订版本的顶层记录。`field` 对缺失或未知字段返回 None，对显式 null 返回 Some(Null)。
浮点相等性使用原始位模式，以保证无损保留。

`remap(&IdMap, &Limits)` 要求为字符串、layout、表和行提供完整、无冲突且没有多余键的映射。
它重写所有嵌套引用、字符串 ID、layout ID 以及描述器中的目标表 ID。
profile 名称、原始字节、缺失字段、null、列表顺序、重复引用和不同的行均被保留。

`merge(&Document, &Limits)` 按限定名称共享表，在转换表 ID 后共享相同的（名称、修订版本）定义，并对字典字符串去重。
profile 按（名称、修订版本）合并，保留左侧顺序，再追加右侧新增的 profile。
行绝不合并；复制任何值之前，先为右侧行分配新的 ID，从而保留前向引用和循环。
左操作数的 ID 保持不变。定义不兼容或 ID 空间耗尽时操作失败，不修改任何输入。
分配时尽量在已有 ID 后追加；当稀疏命名空间已达到 u32::MAX 时，复用较小的空闲 ID。
这是结构合并，不是符号解析或类型规范化。

默认限制为 64 MiB 编码字节、1,000,000 个累计元素和 64 层内联深度。
调用者可以降低限制，也可以提高字节和元素限制；深度硬上限为 256。
元素计数包含字典／profile／表／定义条目、字段和描述器、layout 分组、行、记录槽位、列表槽位，以及每个非 null 字段值或列表值。
在由计数驱动的迭代或分配之前检查计数。字符串和字节串长度必须不超过剩余输入。
模型校验还对 UTF-8 和 blob 的累计字节数计入预算。编码时，最终字节限制包含封装开销。

顶层记录或字段描述器的深度从零开始。List 描述器增加一层。
字段值相对于所属记录增加一层；列表元素和内联记录相对于其所属值增加一层。
Ref 遍历不增加内联深度，校验不会递归追踪引用循环。
不会使用不可信计数进行未经检查的预分配。
API 在内存中持有完整文档和受限缓冲区；max_bytes 是 wire 字节预算，不承诺峰值 RSS 与其相等。

## 验证与后续接入

测试套件包含一个此前未知的 profile，具有超过 16 种节点类别和超过 256 种谓词，覆盖标量位模式的精确保留、带作用域及同名遮蔽的参数、循环和嵌套引用、平行关系、完整 ID 重映射以及 ID 重叠文档的合并。
测试在重新编码后断言逻辑引用目标和完整字段值，而非仅比较数量。
独立的二进制 fixture 和具有正确校验和的格式错误输入用于检查解码器行为，避免仅依赖编码器与解码器彼此一致。

运行 `cargo test -p graphite-schema --locked` 和
`cargo clippy -p graphite-schema --all-targets --locked -- -D warnings`。
合成 fixture 仅作为正确性证据。
仓库要求的真实数据 benchmark gate 用于检查现有运行时回归；不能根据这些合成 fixture 推断新编解码器的吞吐量或容量。

JVM 提取和完整 corpus 迁移将在 #165 的 SootUp 修改与基线稳定后进行。
导入器必须处理较早的 v3 图，以及新增的调用点序号／origin sidecar、GRB 绑定和 folding provenance。
本 crate 不宣称已提供 `graphite build --from` 或新的生产查询路径。
