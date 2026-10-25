# Folia 26.1.2 路由上传修复：2.6.24-tcp.2.1

## 原因与修复

tcp.2 的路由更新入口直接调用 MinecraftServer.execute()，Folia 会抛出
UnsupportedOperationException。提交失败后，routingUpdateQueued 未释放，后续路由更新
也被跳过。Velocity 虽已收到密钥，却收不到路由元数据，最终报告
`Backend did not upload usable UUID/routing metadata`。

修复改用现有平台调度适配层：Folia/Paper 使用全局区域调度器，再由现有实体调度器采集
玩家快照。提交失败、路由初始化失败和停服时释放更新标记；正常的 Folia 异步采集继续
持有标记，直到本批路由处理完成，防止多批更新重叠。

## 安装

- Folia 26.1.2 后端安装 `voicechat-paper-2.6.24-tcp.2.1+26.1.2.jar`。
- 停服后移走旧的 voicechat Paper JAR，只保留一个版本，再完整重启。
- Fabric JAR 用于 Fabric 客户端/服务端，不放进 Folia 的 plugins 目录。
- TCP 协议仍为 1021；原有 tcp.2 客户端和支持 1021 的代理可继续使用。
- 本次为 26.1.2 的 TCP 热修复，不包含 26.3 分支的 UDP/dual 或自动代理检测功能。

后端现有 voicechat-server.properties：

```properties
# 由 Velocity/BungeeCord 处理语音，Folia 后端上传身份和路由。
proxy_mode=true
# 保持多线程服务端支持；修改后完整重启。
threaded_server_support=true
```

这条故障由后端堆栈定位；代理日志中 `<unresolved>` 是后端地址的显示形式，
单凭该字符串不能判定 Docker DNS 故障。

## 验证范围

GitHub Actions 执行 TCP 传输测试、Folia 调度回归及 Fabric/Paper/NeoForge/Forge 构建。
调度回归提取实际生产调度方法与 Paper 适配方法，使用拒绝 MinecraftServer.execute 的
平台替身检查启动、去重、异步批次持有、异常恢复和停服。它不启动真实 Folia，
不替代升级后的双客户端听音、区域切换和代理切服验证。
