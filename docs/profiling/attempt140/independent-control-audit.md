# Attempt 140 真实 36 查询 control 独立审计

**Correctness PASS**：离线逐项比较 `fork-001-rows.jsonl` 与固定 v3 catalog，
36 查询共 **6,171 行**的四列值、行顺序、每行完整 provenance 均精确相同；
独立序列化重算的 digest 与全部 TSV 行一致，DISTINCT 无重复 tuple，ordinary row 每行只有一个来源。
没有重新执行 Java、查询、构建或性能任务。

12 条纯四词 OR 包含真实谓词命中单图早/中/晚、2 图、55 图和 64 图，rows / DISTINCT 均覆盖。
必须区分 catalog 的全谓词命中图数与 TSV `hitGraphIds`：后者是 LIMIT 后返回行的 provenance 并集。
例如 pure-four-all 的原查询命中 64 图，rows 返回行来源是 1 图，DISTINCT 返回行来源是 2 图；
这与完整 oracle 相符，不能要求 LIMIT 后来源并集等于全部谓词命中图。
本次逐行比较保留这一差别，没有把来源清单缩成行数或 digest 代替完整比较。

64 图、1,088 个文件的采集前后内容 receipt 精确相同，其文件哈希也等于 run.json 的
`graphContentSha256`。Candidate 的记录哈希
`fb58d962d349a5c526bec229b996f08d94800702688ed202c1e7e771c3e4e357`
与构建 receipt 相符。Catalog/workloads/adapter/runner/verifier 的当前小文件哈希与输入记录一致，
已编译 adapter class 哈希一致。绑定的 run.py 仅在循环前后输入哈希检查成功、图前后内容一致后
写 `status=complete`；它没有单独输出 after-JAR hash map，本审计未虚构该字段。
为避免与 root 的 old34 计时干扰，本审计没有重新 hash 大 JAR 或真实图文件。

这只是一个 correctness control，不是配对收益、每查询 P95、稳定性证明或候选接受。
Old34 仍须完整三组并满足每组 P95 严格进步，再按既定条件决定是否执行 v3 三组。
逐查询完整审计和输入哈希见 [independent-control-audit.json](independent-control-audit.json)，
复算脚本为 [independent-control-audit.py](independent-control-audit.py)。
