# JVM 接入通用图 Schema 的迁移方案

[English](jvm-generic-types.md) | 简体中文

状态：设计草案，尚未实现。日期：2026 年 10 月 6 日。

核心设计见 [Graphite 通用图 Schema 方案](graph-schema.zh.md)。JVM 是通用 schema 的一个 profile；本文件只定义当前 JVM 图、存储和查询的迁移，不再为泛型单独设计一套格式。新增语言不应修改通用 wire format 或编解码器。

## 已确定的组件边界

Kotlin 负责 JVM 分析和写 IR，不实现新持久化格式的 reader／writer。NodeSerializer、GraphStore 和 MappedWebGraphBackedGraph 冻结在既有 v1 至 v3 兼容范围；过渡期保留 v3 写入用于差分，Rust 切换完成后按计划退役 Kotlin 查询／服务模块。MmapGraphBuilder 可继续作为前端内部工作存储，并完整保留要写入 IR 的类型。

新格式的持久化、索引、查询和 Explorer 统一由 Rust 实现。Kotlin 需要的改动集中于模型、提取、IR 输出和临时构图，不为新格式移植 mapped reader、索引恢复、属性扫描或 Cypher 引擎。新格式兼容属性由 Rust 的 JVM profile 提供。

## 当前断点

| 环节 | 当前行为 | 修改位置 |
| --- | --- | --- |
| 类型模型 | className 与 typeArguments 无法完整表达作用域、通配符和 bounds | [Node.kt](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/core/Node.kt) |
| 字节码解析 | 已读字段、方法和类 Signature，部分结构信息未保留 | [GenericSignatureParser.kt](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/GenericSignatureParser.kt)、[BytecodeSignatureReader.kt](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/BytecodeSignatureReader.kt) |
| 构图 | 字段已接入部分泛型，参数和返回值主要取 Soot 擦除类型 | [SootUpAdapter.kt](../frontend/jvm/sootup/src/main/kotlin/io/johnsonlee/graphite/sootup/SootUpAdapter.kt) |
| Mmap | 类型池按 className 去重并丢弃类型参数 | [MmapGraphBuilder.kt](../frontend/jvm/core/src/main/kotlin/io/johnsonlee/graphite/graph/MmapGraphBuilder.kt) |
| 持久化 | 节点和内嵌方法的类型写为原始类名字符串 ID | [NodeSerializer.kt](../frontend/jvm/webgraph/src/main/kotlin/io/johnsonlee/graphite/webgraph/NodeSerializer.kt) |
| 查询 | 显式属性、完整节点输出与快速扫描都依赖原始类名 | JVM／Rust 的属性访问器、columns、scan、materialize 和 Explorer |

当前固定节点 tag、边标签位段和查询白名单的通用化由主方案处理，不在 JVM profile 中继续扩充这些枚举。

## JVM 信息到共同模型的映射

| JVM 信息 | 共同记录 |
| --- | --- |
| 类、字段、方法身份 | Module 与 Symbol，descriptor 是 JVM profile 数据 |
| 类／方法形式参数 | Scope 与 Parameter，按声明位置绑定 |
| List<User> 等类型 | Expression，使用共同 apply、named 与参数引用结构 |
| wildcard、bound、owner、array | 相应 operator、operand 及 Constraint，保留方向、顺序和 owner |
| 字段、参数、返回类型 | 对应节点的声明 TypeUse |
| 已有 actualType | 独立的解析／推断 TypeUse，不覆盖声明类型 |
| 泛型父类和接口 | 声明引用与约束关系；保留原始继承拓扑含义 |
| callee／caller | Symbol 引用及可用的声明类型，不等于已经完成调用点泛型替换 |

JVM 擦除类型由 descriptor 提供，既有 className 访问由 JVM adapter 映射。通用 Expression 不要求 erasedType；其他语言不必伪造 JVM 字段。方法匹配和原有公开 signature 字符串保持既有语义，不把 List<User> 拼进原始 className。

用于读取方法 Signature 和区分泛型 scope 的 key 使用所属类、方法名和完整 JVM descriptor，包括返回类型。既有公开 MethodDescriptor.signature 不含返回类型，不能直接拿它作为所有新声明的唯一 key。类型变量用 scope 与 ordinal 引用，bounds 放在约束记录中，避免 T extends Comparable<T> 无限内嵌。

变量名不是身份。同名不同 scope、方法变量遮蔽类变量、内部类引用外层变量均需保留。完整结构相等用于类型去重；JVM 方法匹配仍使用擦除身份，审计现有 data class equality、方法集合和缓存的用途。

## 提取与构图

类、方法和字段的泛型读取 [Signature 属性](https://docs.oracle.com/en/java/javase/26/docs/specs/jvms/jvms-4.html#jvms-4.7.9)。BytecodeSignatureReader 同时接收 descriptor 与可选 Signature，并输出参数、返回值、scope 和约束。在可解析范围内校验 Signature 与 descriptor 的擦除结果，矛盾时记录诊断并回退；缺少外部 bound 时标记未解析，不猜测替换 descriptor。

SootUpAdapter 在统一方法描述符入口接入声明信息，ParameterNode 复用其参数类型，字段沿用现有入口。构造器的隐含参数、bridge 和 synthetic 方法需专门校验位置与身份，无法可靠对齐时保留擦除类型，不误绑泛型。

首个 JVM profile 提供字段、参数、返回、caller／callee 的声明类型及泛型继承信息。LocalVariable 先保留已知完整类型；按 slot 和字节码有效范围读取可选 [LocalVariableTypeTable](https://docs.oracle.com/en/java/javase/26/docs/specs/jvms/jvms-4.html#jvms-4.7.14)，以及调用点 T → User 推断，属于后续能力。

当前 TypeHierarchyAnalysis 的缓存只包含原始类名和方法，字段赋值缓存只存类名，构造器推断按参数位置猜 T。接入后需要明确哪些路径能消费完整类型，修正会混淆不同实例化的缓存；不能把持久化完成当成泛型推断已经正确。语义绑定与推断能力单独验收。

## 存储迁移与旧图

Kotlin IR writer 输出共同记录与定义，由 Rust indexer 写主方案的通用表和 layout；不新增 JVM 专属长期类型文件或私有 payload。现有内嵌 MethodDescriptor 不意味着已有共享 methodId；迁移到共同 Symbol 的成本与类型记录一起测量，不能沿用局部替换字段时的零增量承诺。

Mmap 构建路径改为按完整且带 scope 的类型去重。nodeTypes 目前是堆内表，即使临时文件的引用宽度不变，堆内存也会增加，必须单独测量。

| 情况 | 行为 |
| --- | --- |
| Rust 新引擎读取 v1 至 v3 | legacy adapter 保留原图逻辑视图，不要求新 schema 表存在 |
| Rust 新引擎读取通用格式 | 按随图注册定义解码，JVM profile 提供兼容属性 |
| Kotlin legacy 读取器 | 保留原有 v1 至 v3 能力，明确拒绝新持久化格式 |
| 旧引擎读取通用格式 | 明确拒绝不支持的物理版本 |
| 旧图迁移为通用格式 | 保留已有信息；无法恢复之前丢失的泛型 |
| 从原始 JAR 重建 | 提取源字节码仍保留的泛型与声明信息 |

新 wire 版本与[架构提案](architecture-frontend-backend.md)统一分配，不先为 JVM 泛型单独占用一个 v4。Kotlin NodeSerializer 的 FORMAT_VERSION 保持 3，不通过递增该常量引入新格式；因此不为本次迁移扩充 Kotlin 的版本分支和边解码。Rust 的新旧 reader 分开处理能力阈值，继续正确读取 v1 至 v3 标签和 metadata。

首次新格式发布必须支持纯 Rust 的 `graphite build --from <v3-directory|v3.graphite> -o <target>`，不用 JAR、JDK 或 Kotlin。完整迁移 branchdefs、GRX／GRS、synthetic identity、资源状态及其它 v3 事实，重建所有派生索引；不能仅转存当前 Rust 查询 Graph，因为它尚未读取部分尾段。详见主方案的 corpus 升级流程。

保留 corpus graphId、公开 NodeId 和注册顺序；物理与字符串 ID 可重排，内容 fingerprint 重算并记录源 lineage。已有泛型缺失不是格式迁移失败：没有源 JAR 也能升级，只不能恢复此前未保存的信息。

新目录／容器验证完成后再切换，保留旧图回退，不在线逐文件 patch。通用 registry、数据表及未知扩展都必须随 .graphite 打包、验证、复制和解包完整保留。

## 查询与展示兼容

既有 type、actual_type、method、callee_signature、caller_signature 保持 JVM 兼容视图；部分节点的 type 当前是节点类别，不能直接改成声明类型。fieldType、paramType、varType 保持原有含义。新 schema 的结构访问通过通用查询入口提供，完整声明类型是 TypeUse 的投影，不逐节点重复保存字符串。

可提供 generic_type、actual_generic_type 和 caller／callee 的完整参数／返回类型作为 JVM 便利属性；它们不是通用 schema 的核心字段。完整节点返回及 properties(n) 新增字段是可观察的 API 扩展，需要更新精确比较属性集合的客户端测试。Explorer 标签使用 List<User> 简名，详情使用限定名并正确转义尖括号。

一个明确的行为修正是：现有字段解析器把 T 直接写入 className。新建图应让 type 返回 descriptor 中的擦除类型，完整声明类型返回 T；依赖 f.type = 'T' 的查询需迁移。旧图仅剩字符串 T 时按原值读取，不能判断它是变量还是同名类。

Rust columns.rs、scan.rs、partition.rs 不能把新的类型引用当 StrId。快速路径需匹配已验证 layout 和 JVM profile，失败时回退通用读取。Kotlin mapped 属性和 CallSite 索引仅处理旧格式，不移植新 layout。验证 Rust 优化 WHERE、逐节点属性读取和完整结果输出的一致性，避免只修展示而留下漏查询。

## 验收

JVM profile 是通用 codec 的使用者。验收顺序先证明主方案的未知语言无损往返，再证明 JVM 兼容与新增信息：

- 验证嵌套泛型、通配符上下界、泛型数组、owner、递归 bounds、同名不同 scope、变量遮蔽、bridge 和隐含构造参数。
- 同一个编译 fixture 经 Kotlin 内存／Mmap 构图、IR 输出、Rust indexer、加载查询与容器往返，得到一致的完整类型和原有方法身份；Rust 重存后仍完整，不要求 Kotlin 加载新格式。
- 同一个 v3 fixture 对比 Kotlin legacy、Rust legacy 和 Rust 迁移后查询；单独断言 GRX／GRS、branchdefs 与资源缺失／空表，避免双方查询都没使用某字段就误判无损。
- 在没有 JDK／JAR 的环境执行 --from；验证 corpus 断点恢复、源内容变更、单图失败、ID 连续性、索引重建与切换回滚。
- v1、v2、v3 固定 fixture 的旧属性与边关系保持一致；非法引用、缺失表、schema 错配明确失败。
- 新旧查询路径、properties(n)、完整节点结果、跨图引用与 Explorer 展示一致；声明类型和推断类型不混淆。
- 测量原始 JAR → build → save → load → query 的全链路，并报告通用存储迁移和 JVM 泛型提取各自的容量与性能影响。

按 [CONVENTIONS.md](../CONVENTIONS.md) 执行相关模块测试、lint 和真实数据 benchmark；必须通过 benchmark-regression-gate。合成 fixture 仅证明正确性，不能作为 100M 节点性能或容量收益的实测证据。

## 主要影响位置

| 模块 | 主要路径 |
| --- | --- |
| core | Node、TypeStructure、Mmap 类型池、TypeHierarchyAnalysis 与身份／缓存 key |
| sootup | GenericSignatureParser、BytecodeSignatureReader、SootUpAdapter |
| Kotlin IR writer | 共同 schema、完整类型／声明／约束输出及来源状态 |
| webgraph 与 JVM 查询／展示 | 冻结 legacy 路径，保留对照测试；不增加新格式读取或查询 |
| Rust storage／build | node、graph、完整 legacy metadata importer、columns、container、source、IR indexer、--from 与 corpus 迁移 |
| Rust 查询与展示 | engine/props、engine/scan、engine/partition、materialize、Explorer helpers |

Swift、TS 等语言分别提供 profile 与提取器，通用 writer／reader 不增加对应语言分支。各语言专属分析能力独立实现，不影响上述存储契约。
