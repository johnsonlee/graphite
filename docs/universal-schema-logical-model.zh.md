# Graphite universal schema：逻辑模型草案

[English](universal-schema-logical-model.md) | 简体中文

状态：设计评审稿。依据已合并的 [#231 产品范围](universal-schema-scope.zh.md)，兼容基线采用 [70f908cf 的 README](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md)。下文的名字描述逻辑角色；Cypher 标签、文件格式、索引和网络 API 另行设计。

## 1. 从 Graphite 必须回答的程序问题出发

**第一项验收条件是完整保住已经支持的能力。** 以一个 JAR 为例，其中有两个 `radixSort` 重载，分别接收 `byte[]` 和 `byte[][]`。用户今天能区分这两个声明，保存并重开图后也必须能查到两者。声明的归属、描述符和公开查询结果都应经得起往返。第 4 节列出的各类现有输入和接口都适用这项要求；有意变更语义须按 #231 明确声明并评审。

**单个产物和整个微服务系统都是一等使用场景。** 生产服务已经通过跨图 Cypher 查询和拓扑规则定位服务间依赖。支付服务修改退款接口时，维护者需要定位整个系统内可能受影响的调用点。两个服务可以打包同一份 SDK；判断其中一个服务存在退款调用，需要该服务的代码证据和所选环境的路由配置。要进一步断言其他服务都没有调用，还必须知道分析覆盖了哪些服务和调用。

这两类问题决定模型需要表达什么。每张图发布有作用域的对象和有依据的事实；系统视图在明确条件下关联所选图的事实，并保留它们原有的身份。先用现有输入、生产跨图 API 和拓扑规则检验兼容性（第 3–4 节），再用退款查询检验新增的绑定与覆盖条件（第 6 节），最后用目标输入 IPA、ELF 和 Mach-O 检验扩展边界（第 7 节）。#169 的显式引用和带命名空间的定义是表达模型的一种方式，物理方案留待后续设计。[历史原型 #169](https://github.com/johnsonlee/graphite/pull/169)

## 2. 现有程序图的对象与身份

一份快照标识一份具体输出，包括其局部节点编号。每个已保存的节点都是一次独立出现，通过（快照，NodeId）引用。仅凭属性值相同不能合并节点。元数据行有各自在快照内的键。JVM 方法声明按声明类、名称及含返回类型的完整描述符区分。Hive 的 `shaded.parquet.it.unimi.dsi.fastutil.bytes.ByteArrays` 中，两项声明的键分别以 `radixSort([B)V` 和 `radixSort([[B)V` 结尾，数组维数使它们可以区分。现有的显示签名省略返回类型，作为查询投影保留。[MethodDescriptor 与节点类型](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) · [Hive classfile 审核](declared-types-corpus-identity-audit.md)

| 现有查询类别 | 快照保留的对象、边和元数据 |
| --- | --- |
| 数据流 | `IntConstant`、`LongConstant`、`FloatConstant`、`DoubleConstant`、`BooleanConstant`、`StringConstant`、`NullConstant`、`EnumConstant`、`LocalVariable`、`FieldNode`、`ParameterNode` 和 `ReturnNode` 保留各自的节点 ID 和有类型的属性。`DataFlowEdge` 记录有向端点和流动种类。枚举声明单独以（枚举类，枚举项名）为键，即使没有常量节点，也保留有序的构造值列表。 |
| 类字面量 | `ClassConstant` 是一次值的出现，`value` 是被引用的 JVM 类型描述符。包含相同描述符的字符串常量和遗留图中的整数零均保留原有种类。 |
| 调用图 | 每个 `CallSiteNode` 有独立 ID、调用方、被调用方、可选接收者和有序实参引用。多个调用点可以有相同的 `callee_name`，现有 Cypher 模式接受 `CallSiteNode` 和 `CallSite`。`CallEdge` 记录有向端点及虚调用、动态调用标志。方法清单包含没有入站调用的声明。 |
| 类型层级 | 快照内的类型名标识层级表中的类型。层级事实记录子类型、关系（`EXTENDS` 或 `IMPLEMENTS`）和父类型；`TypeEdge` 保留节点端点上的关系。已记录的类来源和声明类型绑定一并保留。 |
| 注解 | `AnnotationNode` 作为一次出现有独立 ID 和有类型的属性。成员索引按类名和成员名查找，再按注解名取值。仅凭此索引不能确定具体的重载。 |
| Lambda 与方法引用分派 | 原始调用点和推导出的调用点分别记录。已采集的 JVM 调用点序位区分调用方、被调用方相同的多个调用点。推导调用点的序位为负数，已记录的来源引用可追溯到原调用点。 |
| 资源 | `ResourceFileNode` 和 `ResourceValueNode` 保留各自 ID、路径，以及适用且已记录的来源、配置变体、键或有类型的值。资源清单保留路径和来源，访问器在本快照内按路径读取。`ResourceEdge` 保留端点及 `OPENS`、`LOOKUP` 等关系。 |

每条已记录的边保留方向、端点身份、种类及该种类特有的数据。第五类边 `ControlFlowEdge` 可带分支比较条件。分支范围按条件节点 ID 检索，局部定义按局部变量节点 ID 检索。其他元数据保留原有查找键，包括按类名查类来源、按成员键查合成成员身份，以及按有向产物对查依赖权重。[边类型](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Edge.kt) · [图元数据接口](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/Graph.kt) · [资源清单](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/input/ResourceAccessor.kt)

**快照身份和构建来源回答不同的问题。** 每次生产者执行都应记录输入摘要、实际生效的选项、生产者名称和版本。这些信息相同，可以据此比较构建条件。即使输入、选项完全相同，升级生产者或重复运行也可能把节点 7 分配给另一项声明。只有确认两份副本对应同一具体输出、局部标识分配也一致时，才能跨副本复用局部引用。判断两份输出是否来自同一次构建执行，还需要执行标识或等效证据。导入旧图时可以标识这份快照；原来的构建或生产者仍可能未知。 同一注册名可以先后指向不同快照。

## 3. 现有关系、元数据与查询投影

### 3.1 现有图中的关系

每条边保留快照、已存储的起点和终点、家族、子类或标志，以及图实际暴露的重复次数。元数据中的声明或条目要独立保留，即使没有边经过它。下列五类边在 JVM 模型和 Graph IR 中均已存在。Cypher 模式里的关系类型和边属性 `kind` 的拼写可能不同。[JVM 边定义](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Edge.kt) · [Graph IR](../ir/graphite_ir.proto) · [Cypher 关系匹配](../backend/cypher/src/engine/matching.rs) · [边的解码名称](../backend/storage/src/graph.rs)

| 边家族和公开模式名称 | 需要保留的方向和含义 |
| --- | --- |
| `DATAFLOW`、`DATA_FLOW` | 沿已记录的产生方 → 使用方查询。保留 `ASSIGN`、`PARAMETER_PASS`、`RETURN_VALUE`、`FIELD_STORE`、`FIELD_LOAD`、`ARRAY_STORE`、`ARRAY_LOAD`、`CAST` 和 `PHI`。流入调用的 `ClassConstant` 保留描述符形式的 `value`。调用点的有序实参槽位和接收者仍可分别查询，即使两个槽位引用同一节点。 |
| `CALL` | 保留两个端点以及 virtual、dynamic 标志。调用方和被调用方的描述符均属于调用点。例如 JVM 前端可以在 lambda 实现的调用点上生成 dynamic 自环，其 callee 描述符标识目标。若把自环改连到新增的成员节点，原有遍历结果就会改变。[JVM 调用点生成](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/CallSiteEmitter.kt) |
| `TYPE` | `EXTENDS`、`IMPLEMENTS` 的方向为子类型 → 父类型。按子类型、关系、父类型保留层级元数据，即使声明没有对应的边端点。 |
| `CONTROL_FLOW`、`CONTROLFLOW` | 保留有向的 `SEQUENTIAL`、`BRANCH_TRUE`、`BRANCH_FALSE`、`SWITCH_CASE`、`SWITCH_DEFAULT`、`EXCEPTION`、`RETURN`。条件边可由条件操作数指向所选分支内的节点。比较运算符和比较对象引用、分支成员以及按语句顺序记录的局部变量定义，均包含无法仅从邻接关系中恢复的信息。 |
| `RESOURCE` | `OPENS`、`LOADS`、`BUNDLE_CANDIDATE`、`LOOKUP`、`ENUMERATES` 的 Cypher 类型依次为 `RESOURCE_OPEN`、`RESOURCE_LOAD`、`RESOURCE_BUNDLE_CANDIDATE`、`RESOURCE_LOOKUP`、`RESOURCE_KEYS`。保留记录的方向，例如资源文件 → 读取它的调用点。文件和解析出的值记录保留各自已有的路径、键、值和限定条件。README 还列有 `RESOURCE_CONTAINS`（文件 → 值），当前 IR 和匹配器中没有对应种类。这项文档差异单独记录，原有查询保持其实际结果。[资源查询](../README.md#query-resources-with-cypher) · [JVM 匹配器](../frontend/jvm/cypher/src/main/kotlin/io/johnsonlee/graphite/cypher/RelationshipPatternMatcher.kt) |

原有投影还暴露方法声明、枚举项值列表、类的来源、带权重的产物依赖，以及带有声明类、成员、名称和值映射的注解。方法可以没有调用者，枚举项的值列表可以为空，类级注解的成员可以为 `<class>`。类字面量是有类型的常量，lambda 派发可以由调用点及已记录的数据流表示。这些事实均无需增加边家族。[图记录的事实](../README.md#what-the-graph-captures) · [节点定义](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) · [元数据批次](../ir/graphite_ir.proto)

### 3.2 公共词汇和公开别名

采用前后端架构提案的 `Module`、`Type`、`Member`、`CallSite`、`Constant`、`Local`、`Parameter`、`Return`、`Annotation`、`Resource`、`ResourceValue` 作为逻辑角色。JVM 方法、JavaScript 函数和 Swift 初始化器都可承担可调用成员的角色，各自保留前端的声明键和签名语法。JVM 调用、JavaScript 调用和 Objective-C 消息发送都可提供调用点。装饰器或属性只能在前端记录了附着对象和值的范围内投影为注解元数据。共用角色不意味着提取覆盖相同。[前后端架构提案 §3](architecture-frontend-backend.md#3-the-graph-model)

保留 `CallSiteNode`、`CallSite` 等公开标签、各类常量标签，以及各前端现有的属性与 `keys(n)` 行为。例如，`MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 10` 从已保存的 callee 描述符读取名称，无需解析到成员。提案中的 `callee.owner/name/signature`、`caller.owner/name/signature` 角色，对应现有的 `callee_class/name/signature`、`caller_class/name/signature` 属性（每个属性均带相应前缀）。字段和注解的归属保留 `class`，枚举常量保留 `enum_type`，局部变量、参数和返回节点保留 `method`，字段保留 `static`。各图保持已有的签名与描述符表示。上述映射描述逻辑角色，不向现有查询响应增加键。[公开查询示例](../README.md#cypher-compatibility) · [现有属性投影](../backend/cypher/src/engine/props.rs)

架构提案中的一等 Type、Member 节点，Module → Type → Member 包含关系，`CONFORMS` 和 `ALIAS`、更多派发标志及语言专属属性，可在证据支持时扩展显式选用的视图。原有图保留节点数、虚拟 Method 视图、边端点和标志。逻辑模型采纳边家族及其含义，具体编码与索引无需采用提案中的 8 位方案或为所有图统一建设。

### 3.3 现有生产路径：跨图查询与服务拓扑

Graphite 已经支持在选定的图上执行一次跨图 Cypher 查询。向 `POST /api/cypher/graphs` 提交 `graphs: ["orders", "billing"]` 时，默认模式在两图的并集上执行连接与聚合；显式选择 `fanout` 时，同一查询在各图中独立执行。两种模式的结果语义、行数上限和图归属属于兼容基线。[README 中的 API 契约](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md#L1261-L1272)

```cypher
MATCH (a:IntConstant {value:42}), (b:IntConstant {value:17})
RETURN DISTINCT graphId(a) AS callerGraph, graphId(b) AS otherGraph
```

在已发布的 Graphite 2.18.0 上，用两个 JAR 构图：orders 含调用 `com.company.rpc.billing.Adapter.refund(42)` 的方法，billing 含返回 17 的方法。对两张保存的图实际发送上述查询，默认模式返回一行 `(orders, billing)`，其 `$metadata.graphIds` 为 `["billing","orders"]`；同一请求改用 fanout 返回零行。

对于服务拓扑，README 的现有 `--topology` 规则从 `graphId(call)` 得到调用方，从 adapter 类名取得目标服务。对上述同一组输入，生产服务的 `/api/topology` 返回一条 orders → billing 的 `company-rpc` 边，operation 为 refund、权重为 1，证据为 adapter 类名。第 6 节另行检验新提出的目标唯一性和调用者计数的完整性条件。现有跨图查询和拓扑规则无需启用这些附加条件。[README 的 RPC 规则](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md#L948-L965)

## 4. 基线查询与兼容验收

### 通过公开 CLI 复现保存、重新加载与查询

保存并重开样例 JAR 后，以下查询结果应保持一致。两个重载均属于 `com.example.ByteArrays`，描述符分别是 `([B)V` 和 `([[B)V`。[可执行验收用例](../examples/universal-schema/logical-model.doctest)

| Cypher | 预期结果 |
| --- | --- |
| `MATCH (m:Method) WHERE m.class = 'com.example.ByteArrays' AND m.name = 'radixSort' RETURN m.parameter_types AS parameters ORDER BY parameters` | `["byte[]"]`, `["byte[][]"]` |
| `MATCH (c:IntConstant)-[:DATAFLOW*]->(cs:CallSiteNode) WHERE cs.callee_class =~ 'com.example.*' RETURN c.value, cs.callee_name` | `17`, `consume` |
| `MATCH (r:ResourceValue {key: "feature.mode"}) RETURN r.path, r.value` | 0 行 |
| `MATCH (f:ResourceFile {path: 'application.properties'}) RETURN f.path, f.format` | `application.properties`, `properties` |

数据流结果要求模型保留整数类型、有方向的数据流路径及调用点的被调用方属性。资源结果区分了此 JVM 输入上的两项观察：文件已被记录，JVM 前端没有将该键的内容建成 `ResourceValue`。虽然源文件含有 `feature.mode`，按键查询仍需保留公开投影的零行结果。该检查使用 Graphite 2.18.0 实际运行。仓库另有可执行示例 `bash examples/quickstart/run.sh`，其结果为 `{"flag":42,"method":"enableFeature"}` 和 `{"caller":"demo.Checkout.startCheckout()"}`。类字面量测试在已保存的目录与归档上检查较新的读取器，包括区分类字面量描述符与文本相同的字符串。[README 查询](../README.md#query-resources-with-cypher) · [快速示例](../examples/quickstart/run.sh) · [JVM 资源契约](../frontend/jvm/sootup/src/test/kotlin/io/johnsonlee/graphite/sootup/ResourceConfigLinkingTest.kt) · [类字面量查询](../backend/cypher/tests/class_constant.rs)

### 比对完整多图查询结果

参照物是可信控制器的 **73 条原始 Cypher 查询**，输入固定为来自 Android、Hive、Tika、Kotlin compiler 的同一组 64 份真实分片。每一条都向全局端点发送原文，完整读取响应并与基线比对。列与行的顺序、重复项、JSON 类型、字段缺失与 null、`rowCount`、请求图范围及每行来源均须保留。控制器只规范化 JSON 对象键的顺序。总数相等不足以证明各行结果一致。每次重复请求也须匹配该版本的首次响应。每例并发四路取得首次响应一次、预热两次、压力测量二十次，并按预定条件在并发一路时增加四十次独立请求。有意改变语义需要可信控制器中事先评审过的逐例期望。[控制器及校验](../benchmark/src/native.rs) · [门禁协议与语义变更](../benchmark/README.md)

已有参照运行 46148788598 比对了 `4ab97a56` 与 `96936008`。`MATCH (n) RETURN count(*)` 两端均返回 19,438,099；`MATCH (n:CallSite) RETURN count(*)` 两端均返回 5,051,914。标签分布查询还独立返回 `["CallSiteNode"]` 对应 5,051,914。同一批调用点既要匹配已有查询别名，也要保留原来对外返回的标签。73 条查询原文与首次完整响应全部精确相同；每一端保留 4,399 份完整成功响应及回执，含预热、压力与按条件执行的独立重复。这些是后续实现需要对照的实际答案，本次核对没有测量所提 schema 的实现。[门禁报告](https://github.com/johnsonlee/graphite/actions/runs/46148788598/artifacts/15265549997) · [查询、原始响应与回执](https://github.com/johnsonlee/graphite/actions/runs/46148788598/artifacts/15266315759)

### 实现必须保留的行为边界

| 输入 / 事实 | 拿模型回答的问题和预期 | 会暴露的建模错误 |
| --- | --- | --- |
| JAR、fat JAR、WAR、class 目录 | 同名方法按所属声明和完整 JVM descriptor 区分。Hive 的两个 `radixSort` 重载分别接收一维和二维 byte 数组，应得到两个声明。包内嵌套 JAR 另保留成员链和类路径选择。 | 只用显示签名去重会丢重载；相同库内容不能消除其在不同包中的归属。[已核对的 classfile 清单](declared-types-corpus-identity-audit.md) |
| APK、AAR | APK 中 DEX 的声明、资源、各 ABI 原生库分别归属各成员；AAR 的 classes.jar 与资源保留各自来源。对同一声明，已读方法签名、已分析方法体、可用控制流是不同覆盖。 | 发现一个原生库不会生成 JNI 绑定或调用边；声明存在不能证明方法体已分析。[已有输入与配置](../README.md#inputs-by-language) |
| 已编译 JS 文件与目录 | 对 `f(x=1)`，`f()` 和 `f(1)` 都可能记录有效值 1。对于已解析的调用，当前前端在默认值生效后将有效值记录在 `CallSite.arguments` 中。原始实参个数未知。对 `g(x,x)` 则需要两个序位。扫描单文件和扫描目录的输入范围分别记录。 | 用有效值重建源码实参，或对两条实参按值去重。[现有 JS 行为](typescript-frontend.md) |
| Swift package、Xcode project/workspace、index store + 源码，含 Objective-C | 同一源码出现在不同编译目标时保留各自的索引上下文。声明可以独立于使用点保存。Signal 的往返记录中，10,027 项枚举元数据即使没有对应常量节点也必须保留；查询已声明但没有关联值的枚举项，返回 `[]`；查询未声明的枚举项，返回 `null`。 | 从使用点倒推出声明全集，会把“已知为空”变成“从未记录”。[Signal 往返核对](apple-frontend-validation.md) |
| Graph IR 导入、图目录、.graphite 归档 | IR 的独立方法和枚举批次均进入事实清单。旧图把 class literal 投为整数零，只能保留其历史投影。支持 tag 16 的新图可给出 `ClassConstant` 描述符；IR v1 的 Node oneof 未声明它。 | 编解码器能读取 header 或三项 trailer 计数相同，都不能证明每类事实已传递。旧 reader 遇到影响答案的新标签必须正确回答或拒绝。[IR 定义](../ir/graphite_ir.proto) · [tag 16 行为](webgraph-storage.md#class-literals-node-tag-16) |

对每种已支持输入，按实际公开的接口，经 Kotlin 库、CLI、两个现有引擎以及 REST、MCP、Explorer 比对 README 的查询示例与有类型的投影。新 reader 既要读取旧图，也要核对相同输入与选项产生的新图。保留原有事实种类、标识、属性与值、顺序、重复、错误和覆盖缺口。上述 CLI 示例与原生门禁已覆盖特定路径。其余接口和输入还须有各自的样例与比对，schema 的实现才能满足首要验收条件。

### 普通查询的成本约束

**成本属于兼容契约。** 只请求普通结果行的现有 Cypher、REST 和 MCP 查询须保留原有结果与成本约束。查调用点或遍历数据流必须能够只用基线事实完成；加载或查询时都不应强制连接证据、汇总覆盖、构建系统视图或执行跨快照解析。显式请求证据、限定范围的否定证明或全系统绑定时，才可能需要这些操作。两条路径均须遵守现有取消、工作预算与错误语义。

实现验收依照仓库的优先级：正确性优先，其次是可用性与稳定性，再看服务端查询 p50、p95，最后看构建与保存。普通查询须使用真实、有代表性的多图请求，在范围、缓存状态、并发度与完整消费结果等条件匹配的情况下检验。逐用例比较 p50、p95 和毫秒绝对值，逐项检查受影响操作的 CPU、RSS 和堆上限。托管门禁的耗时仅作报告。逻辑要求是普通查询为可选能力增加的强制工作量为零；延迟和资源验收仍需实测。[基准协议](../benchmark/README.md) · [仓库约束](../AGENTS.md)

## 5. 查询所需的附加上下文和依据

### 产物与系统的可选上下文

现有图无需容器层级、映射地址或服务清单即可回答上述问题。当输入提供相应信息时，下列对象可补充分析上下文。 有名的链接符号可以没有代码体，代码范围可以没有名称，一个声明也可以对应多个代码实例。

| 逻辑对象 | 身份和关系约束 | 它让哪个问题有答案 |
| --- | --- | --- |
| 产物内容与容器条目（Artifact、ArtifactEntry） | 内容可以是文件或有清单的集合。每个成员出现有自己的标识、一个父容器、成员名和所指内容；同名条目仍可按出现位置区分。内容相等可由相同摘要算法和摘要验证；无内容身份时只保留局部引用。 | 同一份库被哪两个应用打包？同一份 ELF 单独提交和从 APK 取出能否复用内容事实？ |
| 表示（Representation） | 在特定输入范围和解释上下文中观察内容的一种方式。可依赖多个输入，例如编译器索引、源码和构建目标；一个文件也可给出多个表示，例如两个 Mach-O slice。解析事实可按内容复用；依赖搜索路径、编译目标的结论要另带上下文。 | 为什么同一 Swift 源文件在两个模块中的声明不能直接合并？ |
| 位置（Location） | 由表示、坐标系、必要的所有者和点或半开区间组成。坐标系定义单位和原点，例如某方法的字节码偏移、某节内的字节偏移、slice 内文件偏移、映像的链接时虚拟地址。不同坐标间的映射本身是事实，可一对多、部分定义或无对应。 | 能否把这个地址定位到文件或源码？地址属于哪个映像？ |
| 系统视图（SystemView） | 由显式的服务清单、所选快照、可得的服务版本及静态环境输入组成。带依据的归属关系连接服务与产物成员或表示，一个服务可对应多张图，同一内容可出现在多个服务。未知版本保持未知。 | 结论究竟适用于哪一组服务、哪个环境？ |

**只有作用域内的身份可以直接连接。** 图中节点 7、另一个图中的节点 7、两个映像中的地址 0x1000 均为不同引用。名称、反混淆后的显示文本和 URL 路径只提供匹配线索。跨快照关联应声明关系种类，例如“字节内容相同”“调试信息描述此映像”“此配置下可绑定至该接口”。三者分别允许内容复用、源码关联和影响分析；它们不会自动合并节点。对旧图的公开标识保留原有查询投影。

### 5.1 事实身份与依据

用下列记法描述事实。括号中为具名角色，方括号表示有序序列。记法只用于评审逻辑与样例。

```text
Definition = (命名空间, 名称, 语义版本, 各角色的类型与基数, 等价及覆盖条件)
Fact       = (定义, 作用域, 各角色的值)
Evidence   = 支持该事实的依据集合
Basis      = 直接观察(提取执行, 可选的输入内定位)
           | 推导(规则及版本, 前提事实引用, 解释上下文)
```

- **角色和值。** 角色可以是必需、可选或可重复，并声明重复值及顺序是否有意义。值包括有类型的标量、显式空值、对象或事实引用以及结构化集合。定义决定相等和精度。缺字段、显式空值、空序列和未提取的事实分别解释。一次调用的两个实参可指向同一个值；关系双方相同也可以因角色、序位或出现位置而有多条事实。
- **事实身份与依据。** 定义规定用作用域内哪些角色值标识一项断言。有意义的出现次数需按具体出现或序位区分。两次独立观察支持同一断言，就给同一事实增加两份依据。事实引用可使用这一逻辑身份。一个批次可以为其中的事实声明默认依据集合；单条事实可补充依据，也可用实际依据覆盖默认值。已知的输入定位可以是表项、成员路径、源码范围或旧图记录。即使原始源码位置未知，旧图仍能证明它记录了什么。这些规则允许共享依据，无需为每条事实单独存储标识、证据记录或位置。
- **计数与冲突。** 统计调用点按调用点身份去重，统计证据按依据去重。规则结论须能追溯至前提事实和最终的观察，包括程序关系成环的情况。相互矛盾的观察应保留各自的来源和上下文。只要冲突仍影响答案，要求唯一结论的查询就保持未决。
- **引用与解析分开。** 即使目标未知，也应保留观察到的引用。每个候选都要给出使该目标成立的规则、上下文和依据。确认唯一绑定需要证据证明已覆盖该规则和上下文下的完整候选域，已按适用条件与优先级评估候选，并且只有一个可行目标。保留支撑事实与覆盖声明，供读者核对。候选域尚未查全时，即使只观察到一个候选，也仍然只是候选。
- **演进。** 新格式可以发布新定义，并声明与公共定义之间有依据的投影。相同定义版本须保持角色含义、身份和结果精度。把“显式实参”改成“有效参数”需要另一个定义；旧投影继续依照原语义。通用消费者可以读懂结构和引用；执行特定类型推理需要相应能力。对超出解释能力且可能影响答案的事实，读者须明确拒绝有关操作或整图。

### 5.2 新输入与系统视图的可选关系

以下示意定义仅在所选视图提供相应证据时适用。原有图及查询无需具备这些关系。各领域保留其原生谓词，例如 ELF 动态表项或 Mach-O 装载命令。

| 关系（示意） | 保证的含义 |
| --- | --- |
| `contains(parent, entry, content)` | entry 是 ArtifactEntry，标识内容在父容器中的一次出现。一个条目可以包含多个程序声明；程序成员 Member 的身份仍由所属声明和签名确定。 |
| `declaresDependency(image, requestedLibrary)` | 映像的装载元数据请求该库，可保留原始名称及限定条件。能否解析到具体成员由绑定关系表示。 |
| `refersTo(site, reference)`、`candidate(reference, target, context)` | 区分输入中的引用与解析所得的可能目标。 |
| `confirmedBinding(reference, target, context)` | 解析规则在已完整覆盖的候选域内选出唯一可行目标。结论适用于所记录的条件。 |
| `argument(call, ordinal, value, role)`、`flowsTo(value, use, context)` | 实参顺序和来源有据可查；数据流保留分析边界，不把一个客户端值直接连成另一个服务的形参。 |
| `provides(serviceRevision, operation, handler)`、`requests(site, operationRef)` | 接口声明和客户端操作可各自查询；跨服务绑定须有协议、目标和路由等匹配依据。 |
| `corresponds(left, right, kind, context)` | 关联的种类决定可得出的结论。源码、索引、映像和调试记录之间允许多对多。 |

### 5.3 空结果、部分结果与查询边界

覆盖声明针对 **（快照或系统清单，事实定义及版本，所选范围，提取条件）**。允许整批声明再附作用域更小的例外。完整表示对这个具体问题已枚举完毕；部分表示已检查的范围有界，且仍有已知缺口，即使已检查部分没有匹配项；未知表示缺少足以判定的声明。不能用“这个图含有某种节点”推导整图覆盖。采集于哪次执行、为何跳过、何处损坏应可区分。已声明必需的数据损坏时，受影响查询返回完整性错误。

**计数的对象域决定结论。** 第 4 节的 JAR 中，查询 `ResourceValue` 可以准确返回零行，因为已保存的图没有对应节点。对同一份输入问“配置文件中有没有 `feature.mode`”，答案却是有：原文件写有这个键。若只有图而没有原件或覆盖该键的提取证据，第二个问题的答案是未知。两个问题必须能同时得到各自的答案，否则模型就改变了现有 Cypher 语义，或把提取缺口误作软件事实。

查询要求给出完整性结论时，先确定对象域和所需事实，再合并每个范围的覆盖。正向匹配可报告其已有证据；缺口下的计数只能描述已确认集合。否定、精确计数和唯一绑定还需要覆盖所有可能改变答案的范围及解析候选。最后单独检查查询是否完整消费结果；超预算或取消不能产生“零匹配”的证明。旧 Cypher 的行、null 和错误契约继续有效；这里定义的是结论成立的条件，不指定如何在 API 中携带它们。[已有方法体完整性语义](analysis-completeness.md)

## 6. 跨服务查询：退款接口变更会影响谁？

系统视图为每个服务选定一个版本。orders 和 billing 都打包了同一 SDK。orders 的两个调用点通过别名 `pay` 请求 `POST /refunds`。billing 的 B2 已知请求相同操作，但目标表达式未能解析。清单还包含未采集请求的 legacy-worker。问题是在 staging 环境中，哪些服务有静态绑定至 payments 的 H1 handler 的调用？

样例将协议、动词和路径固定为 HTTP `POST /refunds`。列出的请求都有独立的调用点证据，SDK 的包含关系不能提供这项证据。每个路由表项列出该环境与别名的**全部**适用目标，每个提供方表项列出该服务版本与操作的**全部** handler。空集合证明该范围内无匹配，缺少表项则表示范围未知。部分采集若已得到候选，须保留已有候选及依据，并将枚举标为未完成。下面的小样例仅使用完整表项或缺失表项。

解析器只采用一条确定性规则：按所选别名找到目标服务，再匹配目标服务提供的操作。规则约定的适用范围排除了回退路由、重写和运行时覆盖。要确认绑定，须有已知请求，完整覆盖适用路由和提供方，并在应用全部规则后恰有一个符合条件的 handler。确认绑定的依据可追溯至调用点、所用路由及提供方表项、相应覆盖声明和解析规则。歧义候选各自保留，若缺失的事实可能改变答案，则调用点仍未决。生产解析器需用自己的规则与覆盖证据建立这些条件。[绑定与计数验收用例](../examples/universal-schema/logical-model.doctest)

| 条件 | 对 payments/H1 的结论 |
| --- | --- |
| staging 路由和提供方均完整，C7、C8 唯一绑定 H1 | 两个已确认调用点来自一个服务 orders。B2 的目标未知，legacy-worker 未采集，服务总数未知。 |
| C7 的适用路由表缺失，或目标服务的提供方清单缺失 | C7 未决。若完整路由表明确给出零个目标，则可排除 C7。 |
| 两条适用路由分别指向 H1 和 H2 | 两个候选均保留。即使只观察到 H1，只要另一目标的提供方清单缺失，C7 仍未决。 |
| B2 已解析到 sandbox-pay，补采请求后未发现新增调用点 | 若服务清单完整，精确计数为 1；清单开放时，只能确认下界为 1。 |
| 在 test 中仅检查 orders，路由指向 sandbox-pay | 对该范围的精确计数为 0。加入未采集的 legacy-worker 后，已确认数仍为 0，精确总数未知。 |

现有拓扑按配置的 graphId 执行规则，对 (source, target, protocol) 汇总权重并去重 operation 和 evidence。可将上面的调用点结果投影到服务对，同时保留详细依据供反查；既有规则的权重仍按其原定义计算。旧工作流的每个注册图仍映射到一个服务，C4、拓扑与按 graphId 分组的 endpoints 保持既有契约。若要证明跨服务字段级数据流，还需序列化字段、协议版本以及对端解码映射等证据，单个端点匹配只能支持操作级影响。[拓扑查询契约](topology-graph.md) · [现有跨图规则示例](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md#L948-L965)

## 7. 用原生样本核对三类边界

### IPA 与 Mach-O：包含、装载请求、候选绑定

在临时目录构建了 arm64 iOS 主程序 Demo 及动态库 Pay。Demo 的 C 入口引用外部函数 `pay`，库定义该函数；将两份二进制放入具有 IPA 成员布局的 ZIP。下面是独立工具实际读到的记录及其对应关系。样本只检验包布局与静态元数据，未安装或执行。[Apple QA1798《Checking Distribution Entitlements》中的 IPA 检查示例](https://developer.apple.com/library/archive/qa/qa1798/_index.html)

| 工具观察 | 模型中的事实 |
| --- | --- |
| `unzip -Z1 demo.ipa` 列出 `Payload/Demo.app/Demo` 和 `Payload/Demo.app/Frameworks/Pay.framework/Pay` | 两次成员出现、两份内容、两个独立映像表示。 |
| 对 Demo 执行 `otool -l` 读到 `LC_LOAD_DYLIB @rpath/Pay.framework/Pay` 及 `LC_RPATH @executable_path/Frameworks` | 主映像声明依赖，另有属于该映像的搜索条件。其对系统库的另一项装载请求可以保持外部引用。 |
| 对 Pay 执行 `otool -D` 得到同一 install name；`nm -gU` 有 `_pay` 的定义；Demo 的 `nm -u` 有未定义的 `_pay` | 应用搜索条件、包内位置、架构和库身份支持一个静态候选。符号引用与导出各自成立；仅凭两张符号表无法定位具体调用点或断言实际执行。 |

**反事实检验。** 移除包内 Pay，Demo 的装载请求仍成立，包内候选消失。另放入一个与 Demo 现有装载请求均不匹配的 framework，可增加该库自身的事实及包内成员关系，Demo 的候选集保持不变。对多架构 Mach-O，arm64 和 x86_64 各有自己的地址域及 build UUID；补入 dSYM 时按架构与 UUID 匹配，只给相应 slice 增加源码关联。文件名相同但 UUID 不同不能建立绑定。后两项为规范推演。[Apple 的 build UUID 与 dSYM 规范](https://developer.apple.com/documentation/technotes/tn3178-checking-for-and-resolving-build-uuid-problems)

### ELF：同一个数字可以是两种位置

将同一 Demo 源编译为 AArch64 ELF 对象。实际执行 `readelf -h`、`readelf -Ws`、`readelf -rW`，分别得到 ELF64、类型 REL；已定义的 main 与未定义的 pay；以及位于 .text 偏移 0x10、引用 pay 的 R_AARCH64_CALL26 重定位。答案是“此对象的 .text+0x10 有一个对 pay 的重定位引用”。`readelf -SW` 显示 .text 从文件偏移 0x40 开始。因此，节内偏移 0x10 指向文件偏移 0x50 处的字节，而文件偏移 0x10 指向 ELF 头中的一个字节。要解释 0x10，既需要对象身份，也需要坐标域。此样本没有给出依赖库名，也没有最终装载地址。[ELF 重定位规范](https://gabi.xinuos.com/elf/06-reloc.html)

对可装载 ELF 再做两个边界推演。若 PT_LOAD 的文件长度为 0x100、内存长度为 0x180，则段内偏移 0x120 落在零初始化区：它有映像位置，没有对应文件字节。若同一共享库以独立文件和 APK 成员两种方式输入，按同一内容所读出的动态依赖请求应一致；匹配到哪一份库仍取决于此次选择的搜索上下文。stripped 输入的局部函数名可以未知，同时已完整读取的动态依赖仍可查询。[ELF 装载规范](https://gabi.xinuos.com/elf/07-pheader.html)

## 8. 这份设计在什么情况下应被否决？

- **已有答案被改变。** 即使节点总数保持一致，把两个数组维数不同的重载映射到同一方法，或者让跨图联查失去其中一个来源图，都违反兼容要求。评审应能从第 2、3 节的身份和投影规则直接解释第 4 节的结果。
- **已有事实被过度解释。** 拓扑规则可以从 adapter 类名得出 orders → billing。要进一步声称“在 staging 中，C7 唯一绑定 H1”，必须给出适用路由、提供方声明和候选范围完整的依据。一个服务依赖关系不足以推出目标唯一。
- **新输入迫使旧图承担新义务。** 若表达一个 ELF 节内偏移就要求每个 JVM 调用点都持有映像地址，或使普通查询必须计算证据闭包，该方案不能接受。附加上下文只在问题需要时参与解释。

仓库记录和原生工具输出提供已知事实，用来质疑上述规则；样例中的路由和覆盖范围是明示的假设。设计通过评审后，实现仍须接受现有查询全链路兼容、实际成本、多 bundle IPA、Mach-O/dSYM 配对和 stripped ELF 的独立检验。
