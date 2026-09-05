# Attempt 141 v3 控制回放独立审计

**通过：36 条查询、6,171 个输出行的值、行顺序、逐行完整 graphIds 和 digest 均与固定 v3 oracle 精确一致。** 独立脚本直接读取原 JSONL、TSV、catalog 和 receipts，没有运行 `verify_run.py`，没有 Java、构建或查询。

核对全部 36 个 ID 与顺序，workload Base64 解码后的完整查询及 SHA256、投影列、DISTINCT 标志、完整命中图 census、returned graphIds、成功状态、正延迟、64 图范围及 per-query-cold 模式。六种纯四关键词 OR 的 rows/DISTINCT 均包含在其中；逐查询完整/返回图数见 JSON，二者没有混为同一个概念。

重新核对 run.json 所有记录的输入文件哈希，包括 trusted/candidate JAR、manifest、catalog、workloads、adapter/runner/verifier 源码；冻结 candidate 与 build 收据相同。实际命令使用 candidate JAR 和已核验 class hash，Java 17、CPU4、all/per-query-cold 配置一致。64 图的 1,088 个 before/after 文件记录完全相同，before receipt 的 SHA256 与 run.json 一致。

边界：图内容哈希来自控制运行已记录的前后收据；父线程计时期间没有再次读取图内容。oracle 是固定哈希的独立推导结果，本审计没有重导出图或重算 oracle。一次正确性控制不构成性能验收，不计算三次/单次 P95，也不推断收益。

可复算文件：`independent-control-audit.py`；输入哈希及全部逐查询签名：`independent-control-audit.json`。
