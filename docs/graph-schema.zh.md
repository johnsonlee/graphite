# Graphite 通用图 Schema 方案

[English](graph-schema.md) | 简体中文

状态：设计草案，尚未实现。日期：2026 年 10 月 6 日。

目标是建立一个稳定的通用图 schema，使新增语言、节点类别、类型构造和约束关系通过数据定义接入，不需要修改持久化格式或通用编解码器。泛型是这个 schema 的一个应用，JVM、Swift 和 TypeScript 是映射示例。

本方案取代以 JVM 类型字段为中心的扩展设计。[JVM 接入说明](jvm-generic-types.zh.md)只负责现有图和 API 的迁移。[前后端架构提案](architecture-frontend-backend.md)中的节点枚举、类型表达式和 IR 字段布局需以这里的可扩展契约为准；不在本次文档工作中实现新存储格式。

## 必须成立的契约

1. 新语言不分配新的二进制 value tag，也不向核心增加 `switch(language)` 或穷举节点／类型 kind 的解码分支。
2. 新的实体、类型运算、约束、关系和属性使用带命名空间的定义，注册表作为图数据随文件携带。
3. 不认识某个语言语义的读取器，仍能读取、校验引用、显示字段、查询、复制和重存对应记录，不丢失信息。
4. 所有本地图内引用都是显式、带目标类型的引用，通用工具可以重映射 ID；不能把它们藏进字符串或语言私有 blob。
5. 声明身份、类型表达式、语言兼容性、运行时表示分别建模；核心不要求所有语言拥有 className 或擦除类型。
6. 相同信息由不同语言输出时可以使用共同概念；语言特有信息也能用相同物理机制完整保存，不要求先扩大一个封闭 TypeExpr 枚举。

“无需改格式”指新增语言和语义构造不触发 wire format 变更。新增语言仍需前端适配器；理解其特有类型规则可能需要分析模块。能够保存未知关系不等于已经能够证明该关系成立。

## 当前实现为什么不能直接承载

当前 [JVM NodeSerializer](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt)和 [Rust node.rs](../backend/storage/src/node.rs)有固定的 16 种节点 tag，边标签使用固定 family／subkind 位段。Rust 的 [columns.rs](../backend/storage/src/columns.rs)还通过节点 tag 和固定字节偏移直接读取属性。JVM 的节点类别索引及属性访问器依赖已知的 Node 类。

仅增加泛型类型表无法解决这些限制：新语言带来一个新节点类别或关系，仍可能迫使编解码器和查询器修改。因此本方案覆盖节点、边、声明、类型、约束和属性的共同表示，允许现有高性能路径作为通用格式上的可选优化。

## 三层设计

| 层次 | 固定的内容 | 可随语言新增的内容 |
| --- | --- | --- |
| 物理格式 | 基础值编码、引用编码、schema 描述器、表目录、索引和记录边界 | 新表实例、记录 layout 和字典条目，均为数据 |
| 共同词汇 | 具有明确含义的 entity、symbol、scope、type、constraint、relation 等约定 | 带命名空间的新 kind、operator、predicate、field 和 role |
| 语言 profile | 如何声明身份、映射共同词汇、说明来源及能力 | 新语言映射、语言专属约束、类型求解器和展示器 |

物理格式只理解“这里是引用列表”“这里是字符串”。共同词汇赋予某些字段“泛型实参”“调用目标”等含义。语言 profile 再说明什么叫协议符合、协变、生命周期等语义。

## 实现职责与 Kotlin 边界

决定：新持久化格式只实现一套 Rust writer／indexer／reader；新格式的 query、serve、explore 均走 Rust。Kotlin 不增加新持久化格式的读取或写入能力，负责 JVM 语义提取和通用 IR 输出。

| 组件 | 迁移后的职责 |
| --- | --- |
| Kotlin core 与 sootup | JVM 分析、完整类型和声明提取，提供 IR 数据 |
| Kotlin IR writer | 根据共同 schema 写 IR、注册定义和显式引用；不写 BVGraph 或新持久化表 |
| MmapGraphBuilder／MmapGraph | 可继续作为前端构图的临时工作存储；完整类型不丢失，但不承担新格式 reader |
| NodeSerializer、GraphStore、MappedWebGraphBackedGraph | 冻结在 v1 至 v3 的 legacy 兼容范围；过渡期保留原 v3 写入用于对照，不增加新版本分支 |
| Kotlin cypher／query／explore | 过渡期只服务 legacy 图并作为差分基线；Rust 切换验收后退出新版本发行，删除按退役阶段进行 |
| Rust 通用 IR reader 与 indexer | 校验 IR、生成新格式与全部派生索引 |
| Rust storage／cypher／explore | 读取旧图和新图、执行查询及服务 |
| Rust legacy importer | 完整导入旧格式事实，调用同一个 indexer 完成 corpus 迁移 |

Kotlin 需要维护 IR 的通用结构编码，但不需要第二套新格式 mmap reader、属性偏移读取器、索引恢复逻辑及查询引擎。不能通过更新共享的 Kotlin FORMAT_VERSION 顺带让 legacy NodeSerializer 写出半实现的新格式。临时构图文件与对外发布的持久化图必须明确区分。

旧 Kotlin 程序遇到新格式明确拒绝，指向 Rust CLI，不隐式启动转换。v1 至 v3 读取能力保持既有兼容范围；为严格迁移补齐旧语义的工作落在 Rust importer，Kotlin legacy reader 可作为验收对照。无需双端实现新格式，但实际节省的工作量不以“减半”作为估算承诺。

```text
语言前端
  → 注册定义 + 符号 + 作用域 + 类型表达式 + 约束 + 节点与边
  → 同一个通用 writer / indexer
  → 同一个持久化格式
  → 同一个通用 reader / query engine
  → 可选的语言语义分析模块
```

## 稳定的基础值与记录

### 基础值代数

物理层只提供有限的基础编码，不把语言概念作为二进制 tag：

```text
Value = Null | Bool | Int64 | UInt64 | Float64 | StringRef | Bytes
      | Ref(table, row)
      | List(elementDescriptor, values)
      | Record(layoutId, fields)
```

任意有限的语言语法或分析结果均由这些基础值和引用组合表示。精确大整数等内容可以使用带语义名称的记录和字符串／bytes 表示，不需新增 primitive tag。任意嵌套结构仍由 layout 描述，读者不需要运行语言代码。

字段定义区分“缺失”和显式 Null；list 保留顺序和重复项。map 用键值记录列表表达，并声明键的唯一性与是否排序，不能依赖编程语言容器的迭代顺序。多态值可用已有 Record 包装，不要求给每种扩展增加 Value union 分支。

`Bytes` 只用于不含本地图内引用的原始内容，如源文件片段。语言结构与所有可迁移引用必须显式化；前端不能用一个 JSON 字符串或 blob 把类型、scope、symbol ID 隐藏起来，再宣称支持通用重存。

### 自描述注册表

记录实例使用文件内的紧凑 ID；注册表保存完整定义：

```text
Definition {
  name: QualifiedName,
  revision: String,
  category: QualifiedName,
  fields: [FieldDefinition]
}

FieldDefinition {
  name: QualifiedName,
  valueDescriptor: Scalar | Ref(targetTable) | List(descriptor) | Record,
  required: Bool,
  nullable: Bool,
  role: QualifiedName?
}

Record {
  layoutId: LayoutId,
  values: [Value]   // 按 layout 字段顺序编码
}
```

QualifiedName 由 namespace 和 local name 组成；namespace 可使用发布方控制的 URI。例：`core.type.apply`、`swift.type.opaque`、`ts.type.conditional`。这些名称是字典数据，不是代码中的封闭 enum。

category 标识结构用途，例如 type expression 或 graph node，不自动声明语义继承。role 可使用共同定义，如 `core.type.argument`，使通用分析识别某个引用列表的用途；不能让未知 predicate 仅凭名字相似就获得已知语义。

相同名称和 revision 必须对应相同定义及摘要；内容冲突拒绝混合。新增字段或改变 layout 产生新的定义版本，旧定义不被覆写。合并工具保留不认识的定义及字段，并根据引用描述器完成所有嵌套 ID 重映射。

注册表描述结构和可校验约束，如引用目标、是否必填、列表顺序；不嵌入可执行脚本，也不要求后端下载并执行 schema 代码。显示器和语义模块是独立、显式安装的能力。

## 通用领域记录

这些是第一版共同词汇，使用上述机制定义。它们不是物理格式中不可扩展的记录 tag。

| 记录 | 主要信息 | 语义边界 |
| --- | --- | --- |
| Module | 来源、版本、语言 profile、构建配置、artifact 身份 | 区分同名但不同来源的模块 |
| Symbol | 所属模块、前端提供的声明身份、显示名、可选声明节点引用 | 后端不解析语言签名来构造身份 |
| Scope | owner symbol／父 scope、绑定位置、参数列表 | 覆盖类、函数、类型别名、匿名泛型及局部绑定 |
| Parameter | scope、ordinal、参数类别、可选默认值 | 类别可为 type、value、lifetime、effect 或命名扩展 |
| Expression | operator、具名／有序 operands、属性 | 表示类型、常量表达式、类型运算等，不限于 class |
| Constraint | predicate、operands、scope、声明来源 | 表达 subtype、conforms、same type 等要求，不表示已证明 |
| Assertion | predicate、operands、context、状态、证据 | 区分前端解析事实、推断结果与未知 |
| Node | kind、symbol、location、属性和具名引用 | 新节点类别使用新定义，不修改 reader |
| Edge | source、target、predicate、role、ordinal、属性 | 支持同一对端点间的多条不同关系 |
| TypeUse | owner、role、expression、context、provenance、状态 | 区分声明类型、实例化类型、流敏感类型和运行时观察 |

泛型只是 Scope 中声明参数，并在 Expression 中引用或应用这些参数。约束是独立的关系记录，不统一压成 `extends`。参数列表和实参列表保留顺序；嵌套 scope 和显式参数引用处理同名遮蔽。

类型表达式可以共享但不必成为拓扑中的节点；Symbol 可关联独立的类型声明节点。TypeUse 是逻辑关系：常用的单个声明类型引用可以内联在节点 layout 中，额外上下文类型再写稀疏记录，不要求每个节点固定增加一组字段。

类型表达式和 scope 允许有引用环。例如递归类型和递归 bound 通过 ID 引用闭合，不无限内嵌。未知 operator 的具体环语义由 profile 决定；物理读取允许有限记录组成的环，遍历有访问集与工作预算。结构去重不依赖把所有引用展开成树。

### 身份与相等性

图内 ID 是定位手段，不是跨图语义身份。Symbol 身份包含语言／profile、module identity 和前端声明标识；scope 包含所属 symbol 或稳定绑定位置。显示名相同的 `User` 和不同声明中的 `T` 不因此合并。

类型结构去重只合并在定义、上下文、身份引用和 operand 顺序上等价的记录；对不能确认等价的结构可以不去重。不同 TypeId 不自动表示语言类型不相等，相同结构也不等于可赋值。canonical hash 不能包含会随重排变化的临时 ID；跨文件合并先解析目标身份，再做有边界的去重或保守保留。

declared、resolved、inferred、observed 使用独立 TypeUse／Assertion 表达。没有提取到信息与语言自身的 `unknown`、`any`、`never` 等类型不是同一个状态；存储能保留这一差别，分析器不能把缺失当成证明为假。

## 类型与语言映射示例

共同词汇定义 named、apply、parameter reference、projection、function、tuple、record、union、intersection 等常见 operator。新增 operator 仍是普通 Expression，通用存储不随 operator 列表变化。

| 示例 | 共同结构或扩展记录 |
| --- | --- |
| JVM `List<User>` | `core.type.apply(base=SymbolRef(List), arguments=[TypeRef(User)])` |
| Swift `Array<User>` | 相同 apply 结构，但 base 指向 Swift Array 的声明 |
| TS `Array<User>` | 相同 apply 结构，但 base 指向该 TS 项目解析到的声明 |
| Swift `S.Element == User` | projection 表达式与 same type constraint；S 引用其 scope 参数 |
| TS `Box<User \| null>` | apply 的实参引用 union 表达式 |
| JVM `? extends User` | wildcard／bound 表达式或 JVM 命名 operator，保留方向 |
| Swift `some P` 与 `any P` | 各自的命名 operator；opaque 声明身份与约束独立保存 |
| TS 条件类型 | `ts.type.conditional` 的 check、extends、true、false operands 及局部 scope |

JVM erasure 和 descriptor 是 profile 字段，既有查询可通过 JVM 兼容适配器访问。其他语言不需要虚构擦除类型。Swift 的关联类型和同类型约束见[语言规范](https://github.com/swiftlang/swift-book/blob/main/TSPL.docc/ReferenceManual/GenericParametersAndArguments.md)，TS 的结构兼容与类型运算见[类型兼容规则](https://www.typescriptlang.org/docs/handbook/type-compatibility.html)及[类型运算](https://www.typescriptlang.org/docs/handbook/2/types-from-types.html)；这些语言语义由前端或对应分析模块处理。

### 未知语言的具体例子

一个未来前端可以注册下面的定义。这里是可读表示，不是要求磁盘存 JSON：

```json
{
  "name": "example.type.region-qualified",
  "revision": "1",
  "category": "core.expression",
  "fields": [
    {"name": "base", "valueDescriptor": {"ref": "types"}, "required": true, "nullable": false, "role": "core.type.operand"},
    {"name": "region", "valueDescriptor": {"ref": "parameters"}, "required": true, "nullable": false},
    {"name": "mode", "valueDescriptor": "string", "required": true, "nullable": false}
  ]
}
```

旧通用 reader 不认识 region-qualified 的语义，但可以读取 base、region 和 mode，跟随两个引用，查询该 operator，重映射 TypeId 和 ParameterId 并重存。只有“这两个 region 是否兼容”的专属分析需要新增语义模块。schema 本身不附带需要执行的语言代码。

## 持久化与大图成本

### 表目录与 layout 驱动编码

容器 manifest 包含 wire format 版本、注册表摘要、语言 profile、表目录、表 schema、数据段位置／大小／校验值，以及可选索引。新增语言创建新的字典条目、记录 layout 或表实例，容器仍使用同样的目录和字段描述机制，不硬编码语言文件名。

每张表按 layout 分组存储。group header 携带 layoutId；固定宽度字段列式或定长排列，可选字段使用 presence bitmap，变长字段使用 offset 和数据区。字符串和定义名称全局字典化。此处固定物理编码规则，具体字段布局由 schema 数据描述，不逐行存字段名、JSON 或完整类型字符串。

同构引用字段的目标表由 layout 指定，实例只存 4 字节 row ID。异构引用显式保存目标表标识和 row ID，不能按 4 字节统一估算。首版 ID 范围需与所有读写端统一，超范围明确报错；后续容量编码升级属于物理格式演进，不由新增语言触发。

节点和类型的全局 ID 到 group／行位置通过通用索引映射；该索引是容量预算的一部分。类型表达式、scope、符号和约束共享存储。快路径可以针对常用 layout 缓存编译后的字段偏移，但必须按 layout 摘要验证并具有正确的通用回退。

边 predicate 使用注册表 ID，不将语义限制在固定 8 位 family／subkind 中。邻接仍可压缩，边属性与 predicate 通过边 ID／ordinal 关联；必须保留平行边及其顺序语义，不将相同端点的多条关系静默合并。旧 8 位标签属于 legacy decoder。

类型、属性索引都是可选派生数据。新增 kind 可以先使用通用查询和扫描，后续加速不改变语义记录格式。布局和索引优化不能以仅支持当前已知语言为前提。

### 一亿节点的预算

此前“类型引用仍为 4 字节，所以节点增量为零”仅适用于局部替换 JVM 类型槽位，不能作为本次通用图格式迁移的总成本承诺。

```text
总增量 = 节点布局变化 + 边布局变化 + 共享类型／声明／约束
       + 注册表与字典变化 + ID 定位及查询索引变化
       - 被替代的旧数据与索引
```

100M 节点每增加一个 4 字节字段就是 400 MB；每增加一个 1 字节字段就是 100 MB，均为十进制。因此 kind/layout 按 group 摊销，类型和参数定义去重，额外类型观察稀疏存储，不逐节点重复 schema 或泛型元数据。

若 U 是全部不同类型表达式数，B 是包含类型定位索引的平均记录成本，类型部分为 U × B。仅假设 B=64 字节时，10 万、100 万、1000 万类型分别为 6.4 MB、64 MB、640 MB；这不包含符号、scope、约束、引用、注册表及节点／边迁移的其它开销，也不是实测。

接入新语言不改变格式，但可以改变 U、结构复杂度和引用数量。真实数据测量需分别报告文件大小、构建峰值堆内存、加载 RSS、查询缓存和延迟；不把 wire 可扩展性等同于性能不变。

## 未知扩展的读取与查询

通用 reader 的最低能力是：解析已知物理值编码和自描述 layout，验证字段与引用，返回所有原始字段及关系，并能够无损往返。不能遇到未知 kind 就删除记录、替换为空对象或只保留显示文本。

查询器至少暴露记录定义名称、字段、operator／predicate 及可跟随的引用，支持按未知 kind 筛选和按字段取值。索引与返回投影从注册表发现字段，不只依赖预编译的节点属性白名单。新的语言标签可作为 kind 的 profile 别名注册，不能伪装成不符合语义的旧节点标签。

已知共同 role 的类型依赖可直接遍历；未知字段里的显式引用只证明“存在引用”，不自动证明调用、继承、subtype 或 assignability。profile 需说明提取完整性，依赖分析返回 unknown／partial 的边界，不能把没有提取到引用解释为不存在依赖。

可选语义模块可以提供规范化、格式化或语言分析。没有模块时通用结构展示仍可用；若分析依赖某个不可用的语义能力，仅拒绝该分析并说明 unsupported，不能拒绝整个图的读取和通用查询。

## 版本与前后兼容

版本分开管理：

| 版本 | 何时变化 | 旧通用 reader 的行为 |
| --- | --- | --- |
| wire format | 基础编码、记录边界或索引寻址机制不兼容变化 | 明确拒绝无法解码的物理格式 |
| definition／vocabulary | 新 operator、字段、关系或新的语义版本 | 按随图定义读取，不要求认识其语义 |
| language profile | 前端映射、语言能力或工具链变化 | 保存 profile 和数据，通用能力继续可用 |
| analysis capability | 新的语义求解与解释能力 | 对不支持的分析返回明确状态 |

定义内容和 revision 一经发布不可就地改变；不兼容语义使用新定义版本。存储、复制、合并工具不得因未安装某个 profile 而丢失其字段。没有变化的 wire 版本是新增语言验收的硬条件。

引用重排后不要求文件字节相同，但记录含义、未知字段、引用指向、顺序、多重性和原始非引用 bytes 必须保留。校验和随重写重算；manifest 绑定注册表、各表及字符串字典。自描述不意味着允许无边界分配：验证器限制计数、嵌套、引用范围和遍历预算。

Rust 对当前 v1 至 v3 图保留 legacy adapter，保留原有 JVM 属性值和节点身份；已有文件无需在线修改。Kotlin 只保留 legacy 支持，旧 writer 不能写新格式。新格式版本与现有架构提案统一分配，不先为 JVM 泛型单独占用一个互不兼容的 v4。

迁移到新目录／容器并完成一致性验证后切换，保留旧图回退。旧图原本丢失的泛型无法由 schema 自动恢复，需要重新提取源输入。未来 IR 使用同一逻辑记录与注册表；具体传输载体可以独立选择，但 indexer 不能要求每新增语言就增加固定字段分支。

## 现有 Corpus 的直接升级

### 必需入口与无前端迁移

决定：首次发布新格式时必须提供 `graphite build --from <v3 graph> -o <target>`，同时接受目录和 `.graphite` 容器。该入口沿用[架构提案第五节](architecture-frontend-backend.md)的计划；当前 CLI build 仍透传 JVM 前端，此命令尚未实现，必须作为发布门槛而非后续优化。

```text
v3 目录或容器
  → Rust 严格 legacy importer
  → 共同 IR 的逻辑记录流
  → 同一个 Rust indexer
  → 新格式表、邻接和索引
  → 校验报告与新目录／容器
```

路径全程无需原始 JAR、源码、JDK 或 Kotlin 进程；IR 可作为进程内流传给 indexer，不强制在磁盘保留另一份完整 corpus。转换按图分批、按预算流式处理，排序等需要的工作区可落盘。

必须区分格式升级与语义补全：`--from` 转换已有持久化事实、重建全部派生索引；只有需要补齐旧图中不存在的泛型、局部变量类型或其他分析信息时，才从源输入重新提取。不因缺少泛型拒绝格式升级，不假造缺失信息。

### 数据保留与严格导入

迁移器不能直接把当前 Rust 查询 Graph 对象重存。现有 Rust metadata 读取在固定段结束后没有导入 Kotlin writer 可输出的 GRX trailer、graph.branchdefs 和 GRS synthetic identities。首次迁移发布前须补齐所有已支持 v3 语义段，并按共同记录表示其中的引用。

| 源数据 | 迁移行为 |
| --- | --- |
| 节点、已有边、调用与参数引用、属性值及列表顺序 | 完整保留，不运行新的语义分析重写原图 |
| methods、继承、enum 值、annotations、class origins、artifact dependencies | 转为共同声明／属性／关系，保留既有查询值 |
| branch scopes、comparisons、有效 graph.branchdefs 及其绑定 trailer | 校验后导入，显式转换 NodeId 和语句位置引用 |
| GRS synthetic identities | 保留成员 key 与 fingerprint，不重新推导 |
| graph.resources 及其它原始资源 | 保留内容、路径和来源；缺失与合法空表保持不同状态 |
| offsets、type index、字符串列、CallSite／trigram 索引、overview、正反向邻接 | 按源事实在目标格式中重建，重新生成绑定摘要，不复制旧偏移或缓存 |
| 源格式已丢失的泛型、重复 arc 等信息 | 保持缺失，不声称迁移能恢复 |

源图只读。importer 检查所有已知段的边界、计数、标签、引用及存在的摘要，不能把查询 reader 的容错或忽略行为视为迁移正确性。已知派生缓存损坏时从权威数据重建；包含独有语义的段损坏、错配或无法解释时，该图迁移失败，保持源图可用。

输入文件和尾段须有完整清单：已知派生数据可丢弃重建；确认为不含图内引用的源附件可保存；未知且可能包含语义或引用的扩展必须明确报告 unsupported。不能把未知旧段塞进 Bytes 就宣称无损通用化。默认无静默降级模式，迁移失败不要求从 JAR 重建，先补齐相应 legacy decoder 或修复输入。

### 图身份与查询连续性

直接迁移保留对外 NodeId、corpus graphId、注册顺序和已有查询别名，不额外生成会改变旧 MATCH 计数的拓扑节点。新增的 Symbol、Scope 等作为共享元数据保存。物理行、字符串和类型表内部 ID 可重排，但全部引用必须重映射，旧 NodeId 到物理位置有显式索引。

graphId 是 corpus 注册身份，不能用目标文件内容摘要替换。新文件 fingerprint 必须重新计算；将源 fingerprint、源格式版本、迁移器版本和转换参数记录为 lineage。目录输入没有容器 fingerprint 时，对只读源文件清单计算内容身份；迁移过程中源内容改变则废弃该次结果。

允许旧新格式图在 Rust 服务中共存。所有索引和服务缓存按目标内容身份重新绑定；registry generation 在切换时推进，不能沿用源图的内容缓存。对外 graphId 和 NodeId 不变不代表文件摘要或缓存 generation 也不变。

### 数百个图的批量流程

corpus 迁移器以清单调用同一个 `--from` 路径。清单保存 graphId、顺序、源路径与 fingerprint、目标路径、目标 wire 版本、importer／indexer 构建身份、目标 schema／profile 定义摘要、迁移参数和任务状态；具体批量 CLI 参数在命令接口实现时冻结。

每图状态为 pending、running、verifying、ready 或 failed，发布状态单独记录。任务仅在源 fingerprint、迁移参数、目标 wire 版本、importer／indexer 构建身份及目标 schema／profile 摘要全部一致时可幂等复用；还需验证 ready 输出的目标摘要。任何一项改变都重新迁移，避免修复 importer 后复用旧错误产物。崩溃中断的临时结果从该图重新开始，不要求实现任意字节位置恢复。

目标写入独立 staging 位置，验证后在同一文件系统原子发布到独立版本路径，不覆盖源图。输出同名且内容不匹配时失败，不替换其它迁移结果。并发按内存／磁盘预算限制；一个图失败记录原因并继续其它图，整个批次返回非成功状态。

容量计划计入保留的旧 corpus、已完成的新 corpus、正在迁移图的输出与外部排序临时文件。启动和调度前检查空间预算，空间不足使任务失败或暂停调度，不自动删除源图来腾空间。

默认在所选清单全部 ready 后生成新的 registry 清单并原子切换；失败批次不自动发布半份 corpus。已有请求继续持有旧 registry 快照，新请求使用新代。旧清单和文件保留可回退。运维可另选已验证子集建立新批次，不将部分成功伪装成整批完成。

### 校验与发布门槛

每图生成迁移报告：源／目标身份、按 kind 的节点和关系统计、规范化记录／引用摘要、metadata 与资源摘要、缺失能力、索引构建结果和耗时。流式比较源事实与目标 legacy 兼容投影，不能只比较计数；验证内部 ID 重排后引用仍指向同一逻辑对象。

执行三方差分：Kotlin legacy 查询结果对照 Rust v3，再对照 Rust 新格式；Rust 当前尚不暴露的 branchdefs／synthetic identity 另做结构级完整性断言。缺失泛型的 v3 fixture 必须在不访问 JAR 的条件下完成升级和查询；容器与目录、资源缺失／空表、尾段、多图同 NodeId、失败恢复和回滚都需覆盖。

新格式发布 gate 同时要求“从 JVM IR 新建图”和“已有 v3 corpus 直接升级”可用。在隔离 JDK／JAR 访问的环境验证 `--from`，以真实 corpus 验证迁移时长、空间峰值和新索引查询成本。真实数据不可用时记录证据缺口，不用合成图给出吞吐或容量结论。

## 实施顺序与验收

| 阶段 | 交付物 | 验收条件 |
| --- | --- | --- |
| 1 契约冻结 | 基础值、引用、定义／layout、共同词汇、版本和 unknown 行为规范 | JVM、Swift、TS 和人为未知 profile 都能用同一个契约表达 |
| 2 通用 codec | Rust 通用 writer、reader、validator、引用重映射和容器往返；前端 IR writer | 不链接任何语言分析模块即可无损处理未知定义；Kotlin 不实现新持久化 reader |
| 3 通用查询 | schema 字段访问、关系遍历、索引回退、结构展示 | 未知 kind 和 predicate 可查询，专属分析明确标为 unsupported |
| 4 Legacy 与 JVM profile | Kotlin 写 IR、Rust 严格 legacy importer、build --from、corpus 批量升级、查询别名 | 无 JAR／JDK 迁移 v3；旧行为及元数据完整保留，JVM 新提取类型也可全链路保留 |
| 5 真实数据验证 | 容量、构建、加载及查询基准和回归 gate | 分别报告通用格式迁移的成本及方法级／端到端变化 |
| 后续前端 | Swift、TS 或其他语言映射与分析模块 | wire format 和通用 codec 源码均不因新增语言而修改 |

关键测试不是“支持三个预先写好的语言分支”，而是冻结 reader/writer 后注册一个全新的 profile：

1. 新增未知节点 kind、类型 operator、参数类别、约束 predicate 及边关系。
2. 使用带嵌套引用的列表／记录、同名不同 scope、循环引用和平行边；只提供 schema 和数据。
3. 由未修改的 reader 验证、查询、显示，复制／合并时重排 ID，再重存读取。
4. 逐项验证所有已知与未知字段、引用目标、顺序和多重性；缺失语义模块只影响专属分析。
5. 确认全过程 wire 版本不变，通用 codec 和查询字段访问代码没有新增语言分支。

fixture 需包含超过 16 种节点类别和超过 256 种关系定义，防止旧 tag 数量和 8 位标签限制残留。ID 重排测试应让合并前的两个输入具有重叠的局部 ID，避免只验证无需重映射的简单复制。

另需覆盖 schema 冲突、非法引用、缺失表、容器校验、旧格式兼容及 JVM 原有 fast path 与通用路径的一致性。JVM 的 scoped T、bounds、wildcard、array、owner 和 bridge，Swift 关联类型和 opaque 身份，TS record、union 和未求值类型运算都作为结构验收样例；结构 fixture 不等于已实现对应语言前端。

遵循 [CONVENTIONS.md](../CONVENTIONS.md)：synthetic 数据只用于正确性与覆盖，性能证据必须来自真实持久化图，缺少数据时明确标注。实现 PR 需通过 `benchmark-regression-gate`；包含相关模块测试／lint、`CypherBenchmark`、适当的 load/query 基准及 `LargeCorpusPerformanceGateTest`。不能用“没有改 wire format”推断没有性能回归。

## 方案取舍

采用小而稳定的物理编码和开放词汇，避免穷举所有语言的类型系统。共同词汇保证常见分析有一致入口，未知定义的通用访问保证新语言不依赖后端同步发版。代价是一次完整的通用存储与查询适配，以及需要真实数据验证的元数据和间接访问成本。

不采用逐节点 JSON／任意 map 作为主要磁盘格式，也不以不透明 blob 作为语言接入协议。不采用封闭的 TypeExpr 或 NodeKind 二进制 enum 后再逐语言补分支。新增语言的正常路径是增加 profile、定义和记录，必要时增加语义分析能力。
