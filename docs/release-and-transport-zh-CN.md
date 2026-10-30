# TCP Voice Chat 发布与传输说明

## 发布拆分

客户端和服务端使用不同的 GitHub Release：

- `client` 发布只包含 Fabric、Forge、NeoForge、Quilt 客户端模组包。
- `server` 发布只包含 Paper、Bukkit、Velocity、BungeeCord 服务端或代理包。
- 每个 Minecraft 版本单独使用一个 tag，避免把不兼容的加载器或服务端包混在一起。

客户端首次连接游戏服务器时声明 UDP/TCP 能力。服务端根据 `transport_mode` 和 `transport_preference` 选择一个实际协议，并把选择结果返回客户端。客户端只会在收到成功的语音初始化数据后打开对应的语音 socket；语音未启用或初始化失败时不会发送语音数据。

## 配置顺序

配置文件的网络项按以下顺序出现：

1. `transport_mode`：启用 `tcp`、`udp` 或 `dual` 监听。
2. `transport_preference`：双协议客户端的首选顺序，例如 `tcp,udp` 或 `udp,tcp`。
3. `port`、`bind_address`、`voice_host`：监听端口、绑定地址和客户端连接地址。
4. `proxy_mode`、`proxy_auto_detect`：中央代理和自动代理识别。
5. 带宽保护与审核缓存配置。

`dual` 需要在防火墙、Docker 和云安全组同时放行 TCP 与 UDP。同一个端口号可以同时绑定两种传输协议。

## 服务端明文语音缓存

服务端网络层先完成语音包认证和解密，随后服务端处理的是 Opus 音频帧。启用 `voice_moderation_cache_enabled` 后，这些已解密的 Opus 帧会写入服务器磁盘的审核缓存，文件本身不再加密，便于服务器里的另一个程序读取。默认保留 12 小时，可用 `voice_moderation_cache_hours` 调整；设为 `false` 可停止写盘。

缓存的是压缩后的 Opus 帧，不是原始 PCM。缓存目录、priority/periodic 队列和 `.vcm` 文件格式以服务端包内的审核缓存实现为准。管理员应限制该目录的文件权限，并在向玩家开放服务器前告知语音留存规则。

## 兼容性边界

原版 UDP 客户端仍使用协议版本 20；本分支 TCP 客户端使用协议版本 1021，同时声明自己支持 UDP 和 TCP。服务端可通过 `transport_preference` 控制双协议客户端的选择，但不会把只支持单一协议的旧客户端强行切换到另一种协议。

Velocity/BungeeCord 代理必须和后端使用同一 `transport_mode`，并把外部语音端口的 TCP/UDP 流量都转发到代理。代理端会校验后端上传的玩家 UUID、路由元数据和已协商协议；后端未上传可用元数据时，客户端会被标记为不可用。
