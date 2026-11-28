# Graphite universal schema：逻辑模型草案

[English](universal-schema-logical-model.md) | 简体中文

状态：设计评审稿。依据已合并的 [#231 产品范围](universal-schema-scope.zh.md)，兼容基线采用 [70f908cf 的 README](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md)。下文的名字描述逻辑角色；Cypher 标签、文件格式、索引和网络 API 另行设计。

## 1. 模型要让用户沿什么线索查下去？

从一份 IPA 出发，用户可以先找到扩展，再找到扩展自己的可执行映像，最后问这个映像声明加载了哪些库。从一个退款接口反向查找，则要经过服务版本、路由配置、客户端操作，最后落到调用点。两条路径的共同需要是：**对象要能定位，关系要有明确含义，跨边界的对应要有依据，结果要能说明覆盖范围。**

因此建议让每张图发布一批可独立解释的事实。在选定多张图后，分析可以提出有条件的关联，同时保留各图原来的身份和结论。补入 dSYM 或切换服务路由配置都适用这个规则。#169 中的显式引用、带命名空间的定义可用于表达此模型；接受本草案不要求采用其记录布局或 codec。[历史原型 #169](https://github.com/johnsonlee/graphite/pull/169)

## 2. 对象、作用域和身份

| 逻辑对象 | 身份和关系约束 | 它让哪个问题有答案 |
| --- | --- | --- |
| 快照（Snapshot） | 一组固定输入、选项和生产者执行所发表的事实。对象引用至少限定到快照及其局部标识。一个注册名可以先后指向不同快照。 | 昨天与今天名为 orders 的图是否真的是同一次构建？ |
| 产物内容与成员（Artifact、Member） | 内容可以是文件或有清单的集合。每个成员出现有自己的标识、一个父容器、成员名和所指内容；同名条目仍可按出现位置区分。内容相等可由相同摘要算法和摘要验证；无内容身份时只保留局部引用。 | 同一份库被哪两个应用打包？同一份 ELF 单独提交和从 APK 取出能否复用内容事实？ |
| 表示（Representation） | 在特定输入范围和解释上下文中观察内容的一种方式。可依赖多个输入，例如编译器索引、源码和构建目标；一个文件也可给出多个表示，例如两个 Mach-O slice。解析事实可按内容复用；依赖搜索路径、编译目标的结论要另带上下文。 | 为什么同一 Swift 源文件在两个模块中的声明不能直接合并？ |
| 位置（Location） | 由表示、坐标系、必要的所有者和点或半开区间组成。坐标系定义单位和原点，例如某方法的字节码偏移、某节内的字节偏移、slice 内文件偏移、映像的链接时虚拟地址。不同坐标间的映射本身是事实，可一对多、部分定义或无对应。 | 能否把这个地址定位到文件或源码？地址属于哪个映像？ |
| 程序对象 | 声明、链接符号、代码范围、调用点、值、类型使用、资源和操作各自可独立存在。声明键由领域约定并限定到所属模块、编译上下文；出现位置和声明也可分别引用。有名符号可无代码体，代码可无名，一项声明可有多个代码实例。 | 查一个未被使用的枚举声明、一个未解析的符号引用或一个内联实例时，应该返回什么？ |
| 系统视图（SystemView） | 由显式的服务清单、所选快照、可得的服务版本及静态环境输入组成。带依据的归属关系连接服务与产物成员或表示，一个服务可对应多张图，同一内容可出现在多个服务。未知版本保持未知。 | 结论究竟适用于哪一组服务、哪个环境？ |

**只有作用域内的身份可以直接连接。** 图中节点 7、另一个图中的节点 7、两个映像中的地址 0x1000 均为不同引用。名称、反混淆后的显示文本和 URL 路径只提供匹配线索。跨快照关联应声明关系种类，例如“字节内容相同”“调试信息描述此映像”“此配置下可绑定至该接口”。三者分别允许内容复用、源码关联和影响分析；它们不会自动合并节点。对旧图的公开标识保留原有查询投影。

## 3. 一条事实可以表达什么

用下列记法描述事实。括号中为具名角色，方括号表示有序序列。记法只用于评审逻辑与样例。

```text
Definition = (命名空间, 名称, 语义版本, 各角色的类型与基数, 等价及覆盖条件)
Fact       = (事实标识, 定义, 作用域, 各角色的值, 依据)
Basis      = 直接观察(提取执行, 输入中的定位)
           | 推导(规则及版本, 前提事实引用, 解释上下文)
```

- **角色和值。** 角色可以是必需、可选或可重复，并声明重复值及顺序是否有意义。值包括有类型的标量、显式空值、对象或事实引用以及结构化集合。定义决定相等和精度。缺字段、显式空值、空序列和未提取的事实分别解释。一次调用的两个实参可指向同一个值；关系双方相同也可以因角色、序位或出现位置而有多条事实。
- **依据与计数。** 输入定位可以是表项、成员路径、源码范围或遗留图记录。旧图可证明“该图记录了此项”，原始源码位置未知就保持未知。规则推导可追溯至输入事实，含循环的程序关系仍需有实际观察支撑。两个来源支持同一个调用点时保留两份依据；统计调用点按调用点身份去重，统计证据按依据计数。存在相互矛盾的观察时保留来源和上下文，并把依赖唯一结论的查询标为未决。
- **引用与解析分开。** 调用点可以观察到一个名称或符号引用而尚无已知目标。候选关系逐项记录目标、规则、适用条件；只有证据充分时才发表在给定条件下确定的绑定。一个候选的存在也不能证明候选集合已列全。
- **演进。** 新格式可以发布新定义，并声明与公共定义之间有依据的投影。相同定义版本须保持角色含义、身份和结果精度。把“显式实参”改成“有效参数”需要另一个定义；旧投影继续依照原语义。通用消费者可以读懂结构和引用；执行特定类型推理需要相应能力。对超出解释能力且可能影响答案的事实，读者须明确拒绝有关操作或整图。

建议从以下**可以被独立证伪**的公共关系开始。各领域保留其原生谓词，例如 ELF 的动态表项或 Mach-O 的 load command。

| 关系（示意） | 保证的含义 |
| --- | --- |
| `contains(parent, member, content)` | 指定内容在该父容器中出现；成员身份保留归属。 |
| `declaresDependency(image, requestedLibrary)` | 映像的装载元数据请求该库，可保留原始名称及限定条件。能否解析到具体成员由绑定关系表示。 |
| `refersTo(site, reference)`、`candidate(reference, target, context)` | 区分输入中的引用与解析所得的可能目标。 |
| `argument(call, ordinal, value, role)`、`flowsTo(value, use, context)` | 实参顺序和来源有据可查；数据流保留分析边界，不把一个客户端值直接连成另一个服务的形参。 |
| `provides(serviceRevision, operation, handler)`、`requests(site, operationRef)` | 接口声明和客户端操作可各自查询；跨服务绑定须有协议、目标和路由等匹配依据。 |
| `corresponds(left, right, kind, context)` | 关联的种类决定可得出的结论。源码、索引、映像和调试记录之间允许多对多。 |

## 4. 空结果、部分结果与查询边界

覆盖声明针对 **（快照或系统清单，事实定义及版本，所选范围，提取条件）**。允许整批声明再附作用域更小的例外。完整表示对这个具体问题已枚举完毕；部分表示已有观测且有已知缺口；未知表示缺少足以判定的声明。不能用“这个图含有某种节点”推导整图覆盖。采集于哪次执行、为何跳过、何处损坏应可区分。已声明必需的数据损坏时，受影响查询返回完整性错误。

对一次查询，先确定对象域和所需事实，再合并每个范围的覆盖。正向匹配可报告其已有证据；缺口下的计数只能描述已确认集合。否定、精确计数和唯一绑定还需要覆盖所有可能改变答案的范围及解析候选。最后单独检查查询是否完整消费结果；超预算或取消不能产生“零匹配”的证明。旧 Cypher 的行、null 和错误契约继续有效；这里定义的是结论成立的条件，不指定如何在 API 中携带它们。[已有方法体完整性语义](analysis-completeness.md)

## 5. 用基线输入反推约束

| 输入 / 事实 | 拿模型回答的问题和预期 | 会暴露的建模错误 |
| --- | --- | --- |
| JAR、fat JAR、WAR、class 目录 | 同名方法按所属声明和完整 JVM descriptor 区分。Hive 的两个 `radixSort` 重载分别接收一维和二维 byte 数组，应得到两个声明。包内嵌套 JAR 另保留成员链和类路径选择。 | 只用显示签名去重会丢重载；相同库内容不能消除其在不同包中的归属。[已核对的 classfile 清单](declared-types-corpus-identity-audit.md) |
| APK、AAR | APK 中 DEX 的声明、资源、各 ABI 原生库分别归属各成员；AAR 的 classes.jar 与资源保留各自来源。对同一声明，已读方法签名、已分析方法体、可用控制流是不同覆盖。 | 发现一个原生库不会生成 JNI 绑定或调用边；声明存在不能证明方法体已分析。[已有输入与配置](../README.md#inputs-by-language) |
| 已编译 JS 文件与目录 | 对 `f(x=1)`，`f()` 和 `f(1)` 都可能记录有效值 1。现有记录仅保留有效参数时，显式实参个数未知。对 `g(x,x)` 则需要两个序位。扫描单文件和扫描目录的输入范围分别记录。 | 用有效值重建源码实参，或对两条实参按值去重。[现有 JS 行为](typescript-frontend.md) |
| Swift package、Xcode project/workspace、index store + 源码，含 Objective-C | 同一源码出现在不同编译目标时保留各自的索引上下文。声明可以独立于使用点保存。Signal 的往返记录中，10,027 项枚举元数据即使没有对应常量节点也必须保留；已声明的空参数列表仍为 `[]`。 | 从使用点倒推出声明全集，会把“已知为空”变成“从未记录”。[Signal 往返核对](apple-frontend-validation.md) |
| Graph IR 导入、图目录、.graphite 归档 | IR 的独立方法和枚举批次均进入事实清单。旧图把 class literal 投为整数零，只能保留其历史投影。支持 tag 16 的新图可给出 `ClassConstant` 描述符；IR v1 的 Node oneof 未声明它。 | 编解码器能读取 header 或三项 trailer 计数相同，都不能证明每类事实已传递。旧 reader 遇到影响答案的新标签必须正确回答或拒绝。[IR 定义](../ir/graphite_ir.proto) · [tag 16 行为](webgraph-storage.md#class-literals-node-tag-16) |

兼容性按同一输入、构建选项和旧图核对 Kotlin 库、CLI、两个既有引擎以及 REST、MCP、Explorer 的公开投影。原始事实种类、标识、属性和值、顺序与重复、错误和覆盖缺口都属于对照范围。有意改变公开结果仍需按 #231 逐项声明和评审。上表是模型的反例集，产品级全量对照属于实现验收。

## 6. 用原生样本核对三类边界

### IPA 与 Mach-O：包含、装载请求、候选绑定

在临时目录构建了 arm64 iOS 主程序 Demo 及动态库 Pay。Demo 的 C 入口引用外部函数 `pay`，库定义该函数；将两份二进制放入具有 IPA 成员布局的 ZIP。下面是独立工具实际读到的记录及其对应关系。样本只检验包布局与静态元数据，未安装或执行。[Apple 的 IPA 包结构说明](https://developer.apple.com/library/archive/qa/qa1798/_index.html)

| 工具观察 | 模型中的事实 |
| --- | --- |
| `unzip -Z1 demo.ipa` 列出 `Payload/Demo.app/Demo` 和 `Payload/Demo.app/Frameworks/Pay.framework/Pay` | 两次成员出现、两份内容、两个独立映像表示。 |
| 对 Demo 执行 `otool -l` 读到 `LC_LOAD_DYLIB @rpath/Pay.framework/Pay` 及 `LC_RPATH @executable_path/Frameworks` | 主映像声明依赖，另有属于该映像的搜索条件。其对系统库的另一项装载请求可以保持外部引用。 |
| 对 Pay 执行 `otool -D` 得到同一 install name；`nm -gU` 有 `_pay` 的定义；Demo 的 `nm -u` 有未定义的 `_pay` | 应用搜索条件、包内位置、架构和库身份支持一个静态候选。符号引用与导出各自成立；仅凭两张符号表无法定位具体调用点或断言实际执行。 |

**反事实检验。** 移除包内 Pay，Demo 的装载请求仍成立，包内候选消失。另放入一个未被引用的 framework，只增加包含关系。对多架构 Mach-O，arm64 和 x86_64 各有自己的地址域及 build UUID；补入 dSYM 时按架构与 UUID 匹配，只给相应 slice 增加源码关联。文件名相同但 UUID 不同不能建立绑定。后两项为规范推演。[Apple 的 build UUID 与 dSYM 规范](https://developer.apple.com/documentation/technotes/tn3178-checking-for-and-resolving-build-uuid-problems)

### ELF：同一个数字可以是两种位置

将同一 Demo 源编译为 AArch64 ELF 对象。实际执行 `readelf -h`、`readelf -Ws`、`readelf -rW`，分别得到 ELF64、类型 REL；已定义的 main 与未定义的 pay；以及位于 .text 偏移 0x10、引用 pay 的 R_AARCH64_CALL26 重定位。答案是“此对象的 .text+0x10 有一个对 pay 的重定位引用”。其逻辑位置包含对象表示、节和节内偏移。此样本没有给出依赖库名，也没有最终装载地址。[ELF 重定位规范](https://gabi.xinuos.com/elf/06-reloc.html)

对可装载 ELF 再做两个边界推演。若 PT_LOAD 的文件长度为 0x100、内存长度为 0x180，则段内偏移 0x120 落在零初始化区：它有映像位置，没有对应文件字节。若同一共享库以独立文件和 APK 成员两种方式输入，按同一内容所读出的动态依赖请求应一致；匹配到哪一份库仍取决于此次选择的搜索上下文。stripped 输入的局部函数名可以未知，同时已完整读取的动态依赖仍可查询。[ELF 装载规范](https://gabi.xinuos.com/elf/07-pheader.html)

## 7. 跨服务查询：退款接口变更会影响谁？

设 staging 的清单列出 orders、billing、payments、sandbox-pay 和 legacy-worker。orders 与 billing 都包含同一 SDK。orders 的调用点 C7 产生对别名 pay 的 POST /refunds 请求，billing 只有 SDK 成员证据。payments 和 sandbox-pay 各自提供同名操作。staging 配置将 pay 指向 payments；test 配置将其指向 sandbox-pay。legacy-worker 已列入清单，其调用事实未采集。每个服务名在本例中代表视图已选定的一个版本。

查询从 payments 的 handler 反向连接已提供操作、所选环境中的路由和客户端请求，然后按调用点返回来源。下例是可执行的关系推演，在仓库根目录运行 `python3 -m doctest docs/universal-schema-logical-model.zh.md` 即可核对。元组是对具名角色的简写，三组事实分别代表调用点提取、配置解析和提供方声明。故意加入同路径的两个服务及另一个环境，防止只按路径匹配也能通过样例。

```pycon
>>> requests = [('orders', 'C7', 'pay', 'POST', '/refunds')]
>>> routes = [('staging', 'pay', 'payments'), ('test', 'pay', 'sandbox-pay')]
>>> providers = [('payments', 'POST', '/refunds', 'H1'),
...              ('sandbox-pay', 'POST', '/refunds', 'H2')]
>>> def callers(environment, target, bindings):
...     return sorted({(caller, site, handler)
...                    for caller, site, alias, verb, path in requests
...                    for env, name, service in bindings
...                    if (env, name, service) == (environment, alias, target)
...                    for owner, method, route, handler in providers
...                    if (owner, method, route) == (service, verb, path)})
>>> callers('staging', 'payments', routes)
[('orders', 'C7', 'H1')]
>>> callers('test', 'payments', routes)
[]
>>> callers('staging', 'payments', [])
[]
>>> callers('test', 'sandbox-pay', routes)
[('orders', 'C7', 'H2')]
>>> ambiguous = routes + [('staging', 'pay', 'sandbox-pay')]
>>> [service for service in ('payments', 'sandbox-pay')
...  if callers('staging', service, ambiguous)]
['payments', 'sandbox-pay']
>>> inventory = {'orders', 'billing', 'payments', 'sandbox-pay', 'legacy-worker'}
>>> covered_requests = inventory - {'legacy-worker'}
>>> matches = callers('staging', 'payments', routes)
>>> (len({caller for caller, _, _ in matches}), sorted(inventory - covered_requests))
(1, ['legacy-worker'])

```

**如何解释查询结果。** 第一行是一处静态可关联调用点，依据是 C7、staging 路由和 H1 的提供方声明。billing 对 SDK 的包含关系不会进入这条连接。第二行是按 test 配置未得到 payments 绑定；第三行在缺少配置时也返回空集，但 C7 的目标仍未决。第四行给出同一调用点在另一个配置下的候选。清单中的 legacy-worker 缺覆盖，因此第一行的已确认调用者数为 1，系统总数未知。若 staging 同时给出两条均可能生效的路由，保留两项候选及条件；仅凭其中一条不能报告唯一目标。

现有拓扑按配置的 graphId 执行规则，对 (source, target, protocol) 汇总权重并去重 operation 和 evidence。可将上面的调用点结果投影到服务对，同时保留详细依据供反查；既有规则的权重仍按其原定义计算。旧工作流的每个注册图仍映射到一个服务，C4、拓扑与按 graphId 分组的 endpoints 保持既有契约。若要证明跨服务字段级数据流，还需序列化字段、协议版本以及对端解码映射等证据，单个端点匹配只能支持操作级影响。[拓扑查询契约](topology-graph.md) · [现有跨图规则示例](https://github.com/johnsonlee/graphite/blob/70f908cf6acf4b80ce74d038cecba07f119a544b/README.md#L948-L965)

## 8. 评审结论与待验证项

基线案例迫使模型保留独立声明、值角色和按事实族划分的覆盖；原生样本迫使位置带坐标域，并把包含、引用和绑定分开；多服务查询则要求把归属、环境、规则依据和清单完整性纳入查询范围。这些是可以用结果差异检验的模型约束。

本轮验证包括核对仓库已有案例、独立读取原生样本，以及执行上面的多服务关系推演。它验证逻辑表达能力。真实 IPA 多 bundle、universal Mach-O 与配套 dSYM 的成对提取、stripped ELF 共享库，以及生产查询端到端兼容仍待实现验收。下一轮最值得评审的是：哪些公共谓词已有足够精确的身份与覆盖条件，哪些领域仍应保留独立谓词；以及跨服务或跨快照推导在什么条件下能够从候选升级为确定绑定。
