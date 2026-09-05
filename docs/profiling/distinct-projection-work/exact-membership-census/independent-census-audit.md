# 独立候选规模审计

**PASS（限定口径）**：直接读取原 UTF-16 二进制和实际 sidecar 的四属性 ID 目录，独立复算的每 term/每 property 支持集计数，与原 TSV census 的全部 8×64 行一致。没有执行 Java、查询、新导出、构建或性能测量。

导出验证：GST1 magic、连续 0..63 图号、2,793,940 个字符串、5,046,935 节点、UTF-16 长度/逐字符串往返/EOF/gzip CRC 完整；每图字符串唯一。64 个 graph.strings 现 hash 同 exporter 日志与既有 fixture 收据，64 个 sidecar hash 同既有收据；每个 header 的 S/N/四属性 unique count 与实际目录相同。JAR 前后相等来自原 terminal 收据，本审计未重 hash 大 JAR。

| Case | Hit graphs | 属性支持 entries | 按 term 去属性重用后的 union IDs 总和 | 四属性 predicate-position 元素和 |
|---|---:|---:|---:|---:|
| global-wide-wrapped-case-insensitive-distinct-targeted | 2 | 2 | 2 | 8 |
| global-wide-wrapped-case-insensitive-distinct-dense | 64 | 284080 | 223183 | 892732 |
| or-four-broad | 55 | 856 | 646 | 2584 |
| or-four-single-early | 1 | 7 | 4 | 16 |
| or-four-single-middle | 1 | 7 | 4 | 16 |
| or-four-single-late | 1 | 7 | 4 | 16 |
| or-four-few-early-late | 2 | 8 | 4 | 16 |
| or-four-all | 64 | 477669 | 375581 | 1502324 |

三层必须分开：

1. **属性支持集**：实际 caller_class/name、callee_class/name 各自使用并匹配的 string IDs。原 census.py 按 TSV 字符串集合正确推导这一层；它是 per-property 内容统计。
2. **四属性 union 的匹配 IDs**：从 sidecar 四属性目录取 union，按 term 去重匹配。View.kt:69 的 key 只有 transform/mode/expected，同词跨属性复用同一个 IntArray。其私有方法（:205）在 trigram anchor 中过滤，未按当前 property 再过滤；因此原 per-property sizes 不能当作其返回数组长度。本审计得到 union 上的语义匹配数；原始 anchor 的完整重放由主分析独立完成。
3. **全 S 地址域**：导出完整 stringTable 是为了验证 ID→字符串、最大索引域和假设稠密数组 payload。trigram 的 usedCallSiteTrigramStringIds（MappedCallSiteStringIndex.kt:2407）只收四属性 union；不能把 S 或全表字符串匹配量写成实际 mapped 候选。

所有被 sidecar 使用的属性字符串和 terms 均为 ASCII，独立 Python lower 在这个域与现有 lowercase 匹配一致；未用未使用的 Unicode 字符串推断候选。sidecar 目录 ID/末端 offsets 已核查递增/范围和每属性末端 N，文件整体 hash 与既有完整输入凭据相等；没有声称重新运行整个持久化 validator。

原 census 的 P×S、4×S、S 和 8×ceil(4S/64) 仅是假定数组 payload 算术。表中 predicate-position 元素和说明同词候选被四属性引用的计数，不能同时称作四个独立 IntArray 的实际分配量；当前 raw 路径 exactMatchingStringIds.map(::IntOpenHashSet) 是否建立重复 set 是另一个对象层问题。selected tuple feasibility、early return、未执行图、reservation、对象头、GC 及重叠生命周期都不由此 census 决定。

源位置：MappedCallSiteStringIndexView.kt:69–82、205–241；MappedCallSiteStringIndex.kt:2407–2422；MappedWebGraphBackedGraph.kt:481–541、3079–3094。全部 per-graph 原值、输入 hash 和导出证明在 independent-census-audit.json；脚本为 independent-census-audit.py。
