# acme：用于 C4 组件层级的小型应用测试样本

[English](README.md) · 简体中文

这个样本在 `com.acme.shop` 下的 5 个包中包含 6 个类，`main` 位于 `api` 包。
调用跨越多个包：`api -> orders -> billing -> inventory`，各包都使用 `common`。
构建为持久化图后，它会被识别为一个应用，包含运行时容器和多个组件；组件之间的
调用权重会转换为组件关系。库的图（例如由 graphite-core 构建的图）不会产生这些关系。

按 CI 使用的方式构建：

```bash
javac -d /tmp/acme-classes $(find backend/bench/fixtures/acme/src -name '*.java')
(cd /tmp/acme-classes && jar cfe /tmp/acme.jar com.acme.shop.api.Main com)
java -jar frontend/jvm/query/build/libs/graphite.jar build /tmp/acme.jar -o /tmp/acme-graph --include com.acme
```

`backend/bench/parity.py` 使用图 ID `acme`，对这个样本的所有 C4 层级和格式进行比较。
