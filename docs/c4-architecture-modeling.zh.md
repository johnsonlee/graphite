# C4 架构建模

## 目标

Graphite 从已经保存的 code graph 中自动推导 C4 架构视图，不依赖用户手写的
architecture files。
生成模型必须先遵守 C4 语义，再使用 Java archive layout、bytecode metadata、
调用关系、resources 和 artifact provenance 作为证据。Package、jar、call cluster
本身都不是 C4 概念。

输出面向两类使用者：

- 需要结构化架构模型并能继续查询的 AI Agent。
- 需要可读 C4 图做 review 的工程师。

## 设计决策

这一节记录主要建模决策，以及被明确放弃的替代方案。

Graphite 按以下顺序做每个建模决策：

1. **C4 语义合法性**：这个 element 能否合法地成为 Context、Container 或
   Component 概念？
2. **证据强度**：是否有直接 graph/archive 证据，还是只有较弱的 namespace/name
   信号？
3. **架构可读性**：视图是在解释系统结构，还是只是把 raw call/dependency 数量
   原样反映出来？
4. **性能安全性**：这个决策能否基于持久化 graph summary 完成，而不是重新解析
   bytecode 或展开完整 graph？

当这些因素冲突时，语义正确性优先于完整性。宁可不输出有疑问的 C4 element，也
不要用弱证据画出一张看起来很确定的架构图。

| 决策 | 选择的方向 | 放弃的替代方案 | 理由 |
|------|------------|----------------|------|
| 从 C4 语义出发 | 先按 C4 定义 Context、Container、Component，再把 Java 证据映射上去 | 直接把 jar、package 或 call cluster 当成 C4 元素 | 同一个 jar 可能是 library、executable 或 nested dependency。C4 概念必须跨 archive layout 保持稳定。 |
| 保持单一架构 endpoint | 使用 `GET /api/architecture/c4`，通过 `level` 和 content negotiation 选择视图 | 拆成 context/container/component/model 多个 endpoint | Agent 只需要发现一个能力，再按需请求 level/format，避免 API discovery 碎片化。 |
| Artifact provenance 优先 | 先使用 class origins 和 artifact dependency metadata，再考虑 package name | 从 `org.apache` 这类 package prefix 猜 library identity | Package prefix 在不同项目间有歧义。Artifact provenance 绑定到实际提供 class 的 archive。 |
| Plain library jar 是 system，不是 container | Library jar 在 context level 作为 subject software system | 给每个被分析 jar 都虚构一个 application/container | C4 container 是 runtime boundary。Library jar 只有被 host application 嵌入后才有运行时边界。 |
| Context level 折叠依赖细节 | 当 sub-artifacts 形成一个高层依赖故事时显示为 library family | 在 context diagram 画出每个 sub-artifact | Context diagram 要解释系统 landscape。Artifact 细节保留在 model evidence 和 lower-level views 中。 |
| 生成视图必须全自动 | 不要求用户手写 architecture model files | 让用户手工补缺失的 C4 元素 | 只有 saved graph 是 source of truth，Agent 才能可靠使用。精度不足应该通过 evidence extraction/inference 改进。 |
| Diagram 是 summary | 使用 transitive reduction、fan-in reduction、短 label 和 edge caps | 把模型中的每条关系都画出来 | Diagram 服务人工 review。完整证据保留在 Structurizr JSON 中。 |
| 复用持久化 graph evidence | C4 rendering 不重新跑 bytecode analysis | 为架构输出再次解析所有 jars | C4 必须守住 4G heap gate，并随被选中的 architecture elements 扩展，而不是随完整 bytecode corpus 扩展。 |

## C4 官方参考

Graphite 的模型对齐 C4 官方 diagram levels 和 abstractions：

| Graphite View | C4 References |
|---------------|---------------|
| Context | [System context diagram](https://c4model.com/diagrams/system-context), [Software system abstraction](https://c4model.com/abstractions/software-system) |
| Container | [Container diagram](https://c4model.com/diagrams/container), [Container abstraction](https://c4model.com/abstractions/container) |
| Component | [Component diagram](https://c4model.com/diagrams/component), [Component abstraction](https://c4model.com/abstractions/component) |

## C4 语义

### Context

Context 是系统环境视图。它展示一个 subject software system、与它交互的人，
以及直接连接的外部软件系统。它不是依赖清单，也不是 package 列表。

这遵循 C4 system context diagram 的语义：context 关注 scope 内的 software
system、直接连接的人，以及直接连接的软件系统。

Graphite 对 context elements 的映射如下：

| C4 元素 | 含义 | 证据 |
|---------|------|------|
| Person | 与 subject 交互的人或调用角色，例如 operators、HTTP clients 或 library 的 host application | executable role、endpoint evidence、library role |
| Subject software system | 当前被分析的 application 或 library | entrypoint evidence、manifest metadata、dominant internal boundary、class provenance |
| External software system | 图外直接协作者，且没有被证明是 packaged library | referenced-but-absent classes、API/resource evidence |
| External library system | 与 subject 相关的第三方软件依赖 | class origins、artifact dependency metadata、对 dependency artifact 的直接调用 |
| Runtime platform | subject 或 libraries 使用的 Java/Kotlin/Scala runtime services | runtime namespace references |

对于 executable jar、war 或 distribution，subject 是通过 entrypoints、manifest
metadata、reachability 和 provenance 发现的可运行系统。对于普通 library jar，
subject 就是 library 自身；Graphite 不会凭空给它套一个 application container。
如果证据支持，缺失的 host application 会表达为外部 consumer role。

### Container

Container 是运行时/部署边界视图。C4 container 是为了让 software system 工作而
必须运行的 application 或 data store。它不是 Docker 专有概念，也不等同于 jar、
package、namespace、module 或 utility cluster。

这遵循 C4 container diagram 和 abstraction 的语义：container 是 application 或
data store 这类 runtime boundary，不是代码打包单位。

Graphite 只有在存在 runtime-boundary 证据时，才把 Java archive 形态映射为
container：

| Java 形态 | Container 解释 |
|-----------|----------------|
| Executable application jar 或 distribution | JVM application/service process |
| WAR | 被部署的 web application boundary |
| Spring Boot archive | 从 `Start-Class` 或 reachable entrypoint evidence 发现的真实 application runtime，而不是 boot launcher |
| Batch/console executable | 以 `main(String[])` 为根的 process boundary |
| Serverless function | 存在 handler/entrypoint evidence 时的 function runtime boundary |
| Owned database/schema/blob store | 代码或配置证据能证明 ownership 时的 data-store container |
| Plain library jar | 不生成 C4 container；jar 只是嵌入 host runtime 的代码组织方式 |
| Third-party dependency jar | External library context，不是 subject container |

Package cluster 可以解释 runtime boundary 内部的责任，但没有独立 runtime/deployment
证据时，不能提升为 container。

### Component

Component 是 container 内的代码级 building block。一个 component 是同一 runtime
container 内的一组内聚责任，由 classes/interfaces 实现。它不是 raw class、
package、jar 或 namespace。

这遵循 C4 component abstraction：component 是 container 内、隐藏在明确接口后的
相关功能集合，不是独立部署单元。

Graphite 会选择代表性的 capability groups 作为 components。Classes 和 packages
只是命名、责任和关系的证据；默认不会变成 component identity。

只有存在 container 时才输出 component view。Plain library-only graph 仍然可以有
context 和 dependency evidence，但在 host/runtime boundary 未知时，不会生成 C4
component view。

## Evidence Model

C4 推导消费已经持久化的 graph evidence：

| Evidence | 作用 |
|----------|------|
| Method nodes | 内部 class/package ownership、public API surface、entrypoints |
| Call-site nodes | 内部责任、外部依赖使用、关系权重 |
| Member annotations | HTTP/API endpoints、framework entrypoints、interface hints |
| Class origins | Artifact 级依赖身份 |
| Artifact dependencies | Library-to-library 关系 |
| Resource paths | Manifest/config/API-resource 线索 |
| Referenced-but-absent classes | 保存 graph 外部的 external systems 或 dependencies |

Artifact provenance 优先于 namespace guessing。Graph build 阶段，SootUp loader
从 `JavaSootClass.classSource` 和为 archive/directory 创建的
`AnalysisInputLocation` 记录每个 class 的来源。这样 C4 inference 可以把
`lucene-core-9.12.0.jar` 识别成 artifact dependency，而不是从 `org` 或
`org.apache` 这类 package fragments 猜身份。

当 `--include` 过滤导致依赖 class 没有全部加载时，Graphite 仍会尽量使用轻量的
archive/resource evidence，避免 dependency identity 退化成 package-name fallback。

## 推导规则

### Subject Boundary

Graphite 首先判断 graph 表示的是可执行系统还是 library。

可执行证据包括：

- `main(String[])`。
- `Start-Class` 这类 manifest 或 launcher metadata。
- Framework endpoint evidence。
- 从 entrypoint 出发可达的内部 graph。

Library 证据包括：

- 没有有效 runtime entrypoint。
- Public API surface 存在，但 runtime closure 不完整。
- 依赖来自 expected to be supplied by host 的 referenced-but-absent classes。

随后根据 dominant class provenance、package-prefix ownership 和 call evidence
推导内部 system boundary。当 child prefix 明显支配 parent 时继续向下收敛，因此
共享 namespace 下的不同项目会通过证据区分，而不是靠 hardcoded 名字。

### External Systems And Libraries

External context candidates 来自 internal-to-external calls 和 referenced-but-absent
classes。

身份识别顺序：

1. Artifact provenance。
2. Runtime platform namespaces。
3. Referenced-but-absent class families。
4. 没有更强证据时才使用 namespace fallback。

当 context level 下合并成 library family 更清晰时，多个第三方 artifacts 可以折叠。
例如 Lucene Core、Queries、Highlighter 可以在 context 中显示为一个 `Lucene`
external library，同时 artifact-level evidence 仍然保留。

Artifact dependency metadata 用来保留 library-to-library 的方向，因此高层图可以
表达：

```text
Application -> Lucene -> Java Runtime
```

而不是把所有依赖都拍平成 application 的直接依赖。

### Container Responsibilities

当存在 runtime boundary 时，内部证据会被组织成责任区域。分组信号包括 method
volume、cohesion、incoming/outgoing calls、endpoint evidence 和 external
dependency usage。

典型责任类别：

| Category | Signal |
|----------|--------|
| Interface | HTTP 或 framework entrypoint evidence |
| Orchestration | 对内部 capabilities 的 fan-out 高 |
| Integration | 外部依赖使用高 |
| Capability | 内聚的 domain/internal responsibility |
| Shared foundation | 被多个内部区域调用，fan-in 高 |

这些类别描述 subject runtime 内部的责任。除非同时存在 runtime/deployment 证据，
否则它们不是独立部署单元。

Text renderer 会把这些责任类别映射成 Graphite-specific diagram sublayers：

| Renderer Sublayer | 含义 |
|-------------------|------|
| Runtime Boundary | 推导出的 subject executable/deployable runtime boundary |
| Interface Adapters | 面向入口的责任，例如 HTTP 或 framework adapters |
| Coordination | outbound fan-out 高的责任，包括 orchestration 和 integration work |
| Internal Capabilities | 没有更强 interface/shared 信号的内聚 domain/internal responsibilities |
| Shared Foundation | 被其他内部区域大量调用、inbound fan-in 高的责任 |

这些 sublayers 只是 layout aids，不是 C4 官方 abstraction。它们的作用是让
container/component diagrams 可读，同时保持 C4 element semantics 不变。

### Relationships

Raw call direction 是证据，但不总是最终 architecture direction。Graphite 会规范化
关系，让图表达 ownership 和 dependency flow：

| 场景 | 方向 |
|------|------|
| Interface/orchestrator uses capability | upper layer -> lower capability |
| Capability uses shared foundation | capability -> shared foundation |
| Library uses another library | library user -> depended-on library |
| Application/library uses runtime | application/library -> runtime |

Callback-heavy 代码仍可能隐藏 ownership direction。这种情况下关系应该被视为较低
置信度的证据。

## 输出契约

单一 API：

```http
GET /api/architecture/c4?level=context|container|component|all&format=json|dsl|mermaid|plantuml
```

`Accept` content negotiation 优先于 `format=`。

所有格式都从同一个推导出的 Structurizr workspace 渲染。JSON 和 DSL 暴露完整的
结构化模型。Mermaid 和 PlantUML 输出该模型的可读 text-diagram slice，可以在图
输出中省略节点/边，但不会从 workspace model 中删除它们。

| Format | Content Type | 用途 |
|--------|--------------|------|
| Structurizr workspace JSON | `application/vnd.structurizr+json` | Agent 可读架构模型 |
| Structurizr DSL | `text/vnd.structurizr.dsl` | Structurizr-compatible text workspace |
| Mermaid | `text/vnd.mermaid` | Markdown/浏览器快速预览 |
| PlantUML | `text/vnd.plantuml` | Diagram rendering |

JSON 和 DSL 是架构模型。Mermaid 和 PlantUML 是为可读性优化后的渲染视图。

Graphite-specific evidence 存在 element 和 relationship properties 中，例如：

| Property | 含义 |
|----------|------|
| `graphite.kind` | inferred element kind |
| `graphite.architectureType` | rendering/type classification |
| `graphite.responsibility` | inferred responsibility statement |
| `graphite.whySelected` | element 出现在 view 中的原因 |
| `graphite.weight` | relationship evidence count |
| `graphite.evidence` | structured evidence summary |

## Diagram Readability

渲染图有意比底层模型更小。

Text renderer 会应用：

- Top-down layering，符合架构阅读顺序。
- 短 label。
- Transitive edge reduction：`A -> B -> C` 已经能说明路径时，抑制冗余 `A -> C`。
- Fan-in reduction：shared target 只保留最强证据边。
- Component view 和 Mermaid rendering limit 的 edge caps。

需要完整证据的 Agent 应该查询 Structurizr JSON model，而不是依赖 diagram text。

## 性能约束

C4 inference 在 graph load 之后运行，不能重新跑 bytecode analysis，也不能再次解析
每个 dependency jar。

设计约束：

| 约束 | 设计响应 |
|------|----------|
| 4G heap gate | 只使用持久化 graph metadata 和有界 summary |
| large call graphs | 按被选中的 architecture element pair 聚合 calls |
| large dependency sets | 选择 top-weighted dependencies，并折叠 families |
| large diagrams | 对 text-rendered edges 做 cap 和 reduction |
| artifact identity | graph build 阶段推导 provenance，C4 rendering 阶段复用 |

Renderer 应该随被选中的 architecture model 规模增长，而不是随被分析应用的每个
class 或每条 raw call 增长。

## 视图预算与启发式阈值

数字限制是 renderer budgets，不是 C4 语义定义。Structurizr workspace JSON 和
Structurizr DSL 是给 agent 消费的结构化模型，不因为 diagram readability 被裁剪。
Mermaid 和 PlantUML 是文本图渲染，因此在完整模型推导完成之后，才在 rendering
plan 层应用节点/边预算。

| Budget | Default | 决策依据 |
|--------|---------|----------|
| Text context/container visible elements | 12 | 足够容纳 subject、primary actors、runtime 和最强 collaborators，同时仍能放进一屏。 |
| Text component visible elements | 16 | Component view 比 container 更深一层，因此允许略高密度，但避免变成 class diagram。 |
| Text diagram edges | 200 | 明显低于 GitHub Mermaid 500-edge 渲染限制，PlantUML 使用同一 cap 保持格式间密度一致。 |
| Component relationship edges | 12 | 保持 component view 的代表性；完整 relationship evidence 保留在 Structurizr JSON。 |
| Per-component incoming/outgoing caps | 每边 2 | 防止一个 hub component 在视觉上支配整张图。 |
| Namespace fallback depth | 2-3 segments | 避免 `org.apache` 这类过宽名字，同时不过拟合深层 implementation package。Artifact provenance 优先。 |
| System-boundary descent | max 4 segments、85% dominance、3x runner-up separation | 区分共享 namespace 下的 sibling projects，同时偏向稳定、略宽的 software-system boundary，避免过拟合。 |

如果这些默认值生成了误导性视图，优先修 evidence 或 heuristic，而不是写项目特例。

## 当前语义限制

- Business personas、user journeys、deployment topology、owned data stores 和
  serverless functions 如果没有留下 code、resource、manifest 或 runtime-entrypoint
  evidence，就无法精确推导。
- Namespace fallback 的置信度低于 artifact provenance。
- Package-unit clustering 是启发式的，只能作为内部责任证据，不能当作部署真相。
- Component view 是代表性架构视图，不是 class diagram。
