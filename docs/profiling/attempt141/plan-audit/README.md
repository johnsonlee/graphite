# Attempt 141：独立计划审计

本目录只核对候选计划、候选 ID 及分支条件，不是性能实验或候选验收。没有执行 Java、build、测试、查询或新导出，没有修改 production。实际 isolated clone 已提供并完成只读审查，未发现阻断缺陷，见 [source-review.md](source-review.md)；以下保留计划阶段的边界，构造取消方案的最终判断以 source review 为准。

## 量化依据与门槛

独立读取 UTF-16 string export 和 sidecar 四属性 ID union，重算 19 terms×64 graphs，**1,216 个 exactCount/排序 ID SHA256** 与主 `mapped-candidates.json` 全部一致。这里是实际 used-ID 域上的精确匹配，不是 TSV per-property 支持集，也不是全部 stringTable 字符串。脚本 [audit-branches.py](audit-branches.py)、全部原值 [branch-census.json](branch-census.json)、表 [branch-census.md](branch-census.md)。

设 `S=stringTable.size`，原第 j 个 `IntOpenHashSet(IntArray)` 的输入长度为 `m_j`。默认 load factor 为 0.75，`n_j=HashCommon.arraySize(m_j,0.75f)`，key 的 IntArray 长度为 `n_j+1`，原 key payload `H=Σ_j 4(n_j+1)`。四属性复用同词 IntArray 时，主 raw 仍逐 predicate position 构造 set，必须计四次 set 的 key payload；不能因 IntArray 引用复用只计一次。空 set 也有 n=2 的 key 数组。已有 [javap](int-open-hash-set.javap.txt) 与固定版本 [HashCommon source](https://raw.githubusercontent.com/vigna/fastutil/8.5.13/src/it/unimi/dsi/fastutil/HashCommon.java) 支持该公式；本地保存来源及 [receipt](capacity-sources.json)。

| Case | 实际有候选的图 | S<=H 的图 | 候选图保留 hash | eligible S 总和 | eligible H 总和 |
|---|---:|---:|---:|---:|---:|
| old targeted | 2 | 0 | 2 | 0 | 0 |
| old dense | 64 | 64 | 0 | 2,793,940 B | 7,013,376 B |
| four broad | 55 | 0 | 55 | 0 | 0 |
| four single early/middle/late（各自） | 1 | 0 | 1 | 0 | 0 |
| four few early/late | 2 | 0 | 2 | 0 | 0 |
| four all | 64 | 64 | 0 | 2,793,940 B | 11,619,328 B |

全部 512 graph/case 条件都已核对；表不表示运行时一定构造。原 exact-all-empty、selected tuple 不可行、源选择及 LIMIT 可以在构造前返回。H/S 只比较本次被替换数组 payload：不含 exact IntArrays（保持不变）、对象头/alignment、临时 list、reservations、GC、重叠寿命或峰值。**S<=H 不是“实际堆内存不增加”或“CPU/延迟必下降”。**

## OR 真值证明与语义边界

原 exact 分支对 node 计算 `OR_j (nodeStringId[property_j] in exact_j)`。位表可定义 `M[id]` 的第 p 位为 `OR_{j:property_j=p} (id in exact_j)`，于是节点接受条件为 `OR_p bit(M[nodeStringId[p]],p)`，两者等价。

- 位是四个**物理 predicate property**，不是 predicate ordinal 或 projected column index。需要覆盖 >8 predicates、重复 property、重复 projection、同一字符串跨属性、某些 exact 数组为空、重复 IDs、ID=0/最后一个 ID。
- 相同 property 上不同 term/transform/mode 的 exact 结果取 union 仍保持纯 OR 真值；必须保留每个 predicate 已生成的 exact 数组，不按词文本合并不同 transform。不能把这个表应用到 AND 或不完整 exact 条件。
- 原 predicates.indices.any 和 property List 可保持源码原样，但同一 property 的后续 predicate 命中可使较早 predicate 的位表检查就返回 true。因此最终结果保持，**每个 predicate 的实际测试次数并不保证不变**。原 exact set.contains 分支没有每 predicate 的 work charge 或其他副作用，per-node accounting 仍在进入 callback 前一次；这才是允许合并的依据。
- `exactMatchingStringIds==null` 时保持原 lazy string match-state 分支、predicate 顺序及 transformation；不能先读位表或把不支持的 predicate 当 false。原 unsupported/bounds/selectedValues/null 和原 raw/retained/mapped 路由不改。
- 位表在 worker 提交前一次构造，之后只读；不跨调用保存。原 selected-string-ID/membership cache、共享 lazy states、调度/pool、原 validator/format、source order、DISTINCT/LIMIT/provenance 都不改。

## 需要逐项检查的实现细节

1. **额外预检范围。** 应先用列表/数组长度确定 H，原 sparse fallback 仅承担 O(predicateCount) 轻量选择，不应先遍历所有候选才判断密度。若 dense 有独立候选范围预检再填表，应准确记录是两遍 `Σ m_j`，加 `S` 零初始化；不能称“只换一个 load、没有额外工作”。不应先构建原 hash sets 再选择 byte mask。
2. **算术。** 用 Long 累计 `4*(n+1)`，避免 Int overflow。库 arraySize 可能对极大 expected 抛异常；不能吞掉本来应该传播的异常后改变结果。若自行实现 capacity，注意 Java float 除法与 double 公式在极大值上可能不同；本 census 复现了 binary32 语义。早达到 S 可有饱和比较，但不能漏掉后续结构/范围有效性校验。
3. **ID/列表边界。** 正确匹配数目必须与 predicates/property indexes 对齐；每 ID 要在 `[0,S)`。无效 ID 若从优化 helper 返回“不可用”，必须走完整原 hash fallback，不能部分截断数据或把已写位表直接发布。若改为抛错，要说明为何与原行为一致（原 set 可以容纳负数/大 ID，byte array 则不能）。小 S / S=0 亦需明确边界。
4. **取消与 work。** 构造可能读大量 IDs，应在有界间隔检查 thread interruption 和共享 request cancellation；新检查可以选择 batch consumer.consume(0) 检查取消且不加 graph work；实际实现沿用旧内存构造的 budget 口径，只检查 thread interruption，此选择已在 source review 独立判断为合理，并明确 signal-only 取消边界。不能调用普通 consume() 来“检查取消”而改变 workcounter。部分构造异常应局部丢弃，不发布共享表；原 per-node BufferedGraphWorkConsumer 的 consume/flush 与 worker join 保持。
5. **错误与回退。** 保持 selected feasibility 早返回在原 set 构造前；不因位表优化提前做全局 allocation。dense 无法安全构造时若 fallback，原 exact sets 仍在相同位置构造并保持语义。异常不能吞成成功空结果；已有内存预算不是因 payload 门槛自动豁免。

此方向与 136 的 predicate iterator 改写、140 的属性 List→IntArray、138 的 posting projection 均不同，但也不从这些失败尝试或近期 JIT 时刻推断收益。稀疏 targeted 保持 hash 是密度条件的结果，并不说明它的瓶颈已经解决。

## 验收协议

即使计划和正确性通过，也不是接受 141。必须保持原真实 64 图，36-query correctness control；原 34 三组完整配对、全部 oracle、原逐行/资源回归约束及**每一组 P95 严格进步**；通过后追加 v3 三组，纯四词 OR 单图/多图完整值/顺序/provenance 且无重复双阈值回归；最终 exact-head CI 全绿。失败原值保留、不得改阈值/以三次样本称每 query P95/以本 census 的字节差称收益。CallSite 池没有被该候选移除，最终 10x/去池目标仍另需完成。
