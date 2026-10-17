# CI 脚本

[English](README.md) · 简体中文

CI 辅助脚本使用 TypeScript 编写，由 Bun 直接执行。运行时版本固定在
`package.json` 的 `packageManager` 字段中；`bun.lock` 固定开发依赖版本。
脚本使用内置模块及现有的 `gh`、Java 和二进制检查工具，没有运行时 npm 依赖。

| 目录 | 职责 |
| --- | --- |
| `benchmark/` | 校验 workflow 和产品版本身份后，发布或更新唯一的 benchmark 报告评论。 |
| `checks/` | 检查实际 CLI/JVM 启动行为，以及 Linux 发布二进制的静态链接情况。 |
| `release/` | 解析发布标签，支持恢复草稿上传，并发布内容不可变的 release。 |

仓库根目录 [`benchmark/`](../../benchmark/README.zh.md) 中的 Rust 控制器负责执行
benchmark、校验 correctness/OOM 证据并生成报告。TypeScript benchmark 辅助脚本
负责通过 GitHub 接口发布评论。

在本目录运行与 CI 相同的校验：

```sh
bun install --frozen-lockfile
bun run check
bun test
```

类型检查与运行时执行分开进行。测试使用临时 fixture 和模拟的 GitHub 接口，
不会实际发布 release 或评论。Rust workflow 还会用真实 Rust CLI、JVM frontend
和 Linux 二进制运行 `checks/` 中的检查。JVM 配置断言位于 `benchmark/jvm/`，
通过实际 frontend 进程内的 MXBean 读取生效配置；TypeScript 负责准备检查，
并比较保存的图产物。这些集成测试需要 JDK 17。

必需的单元测试 job 检查 PR 中的脚本。benchmark workflow 检查可信控制器快照
中的评论发布程序。评论 workflow 使用可信默认分支 workflow 版本中的发布程序，
将下载的报告视为数据读取。`.github/` 下的所有内容均受 owner 审查规则保护。
