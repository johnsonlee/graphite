# 本地证据归档范围

纳入顶层实验计划、源码/构建/控制/门槛审计、命令与日志；source-snapshot、tool-source、checks-test-results、old34-pairs、v3-control-final。完整原值与失败边界均保留，未执行新采集。

排除 candidate clone、全部 JAR/.class/classes、publish/、pr-before.json、pr-body-template.md、生成的压缩包和外部manifest本身。PR操作草稿不是测量证据。v3 runner class不入包，但既有run/审计保留其身份；Java源码另有快照。

[完整逐文件manifest](archive-manifest.json)含SHA与大小，[直接复制建议](direct-copy-list.json)保留相对路径。尚未复制到candidate或root，也未commit/推送。
