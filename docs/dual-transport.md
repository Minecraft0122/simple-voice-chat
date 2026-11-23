# TCP / UDP 双协议服务端

本实现位于 tcp/26.3 分支。其他游戏版本分支需要分别移植和构建；不能跨 Minecraft 版本混用模组 JAR。

## 开启方式

新安装默认双协议。旧 transport_mode=tcp 在 allow_original_clients=true（默认）时自动按 dual 运行；只允许 TCP 时显式将该选项设为 false。配置后完整重启：

    transport_mode=dual
    port=24454
    voice_host=voice.example.com:24454

- tcp：本分支客户端协议 1021；仅当 allow_original_clients=false 才保持仅 TCP。
- udp：仅接受上游客户端协议 20。
- dual：默认值，同时监听同一地址、同一端口数字的 TCP 和 UDP。

dual 模式需要同时放行 24454/TCP 和 24454/UDP，容器映射也必须分别配置。
voice_host 同时用于两种协议；此版不支持为 TCP、UDP 公告不同的公网端口。
TCP/dual 的语音端口不能与 Minecraft TCP 端口相同。port=0 仍禁止；port=-1 仍映射到 24454。
如果任一启用的传输绑定失败，整个语音监听启动失败，并释放已绑定的另一个端口，不会静默降为单协议。

客户端不需要新增设置：上游客户端继续使用 UDP，本分支客户端继续使用 TCP。
两者通过服务端共同的距离、群组、耳语、权限及插件处理链互通，Opus 音频无需为了协议互通而重新编码。
本功能没有为 TCP 客户端增加自动 UDP 回退，也没有为原版客户端增加 TCP 支持。

## 支持范围

| 环境 | 要求 |
| --- | --- |
| 直连 Mod 服务端 | 使用包含本改动的构建，配置 transport_mode=dual |
| Bukkit / Paper | 使用包含本改动的插件，配置 transport_mode=dual |
| Velocity / BungeeCord 中央代理 | 代理及所有参与后端均使用包含本改动的构建 |
| 原版 UDP 客户端 | 使用匹配游戏版本且兼容协议号为 20 的上游客户端 |
| 当前 TCP 客户端 | 兼容协议号为 1021，无须升级客户端协议 |

不接受其他兼容协议号，也不保证所有历史 2.x 客户端可用。
原有格式基线为协议 20。新增 CI 检查会下载作者 Modrinth 项目中对应 Minecraft 版本最新发布的 Fabric 客户端，校验 SHA-512 并检查实际协议常量；最新版一旦改变协议，检查会失败。上游 2.7 开发分支的协议 21 尚未适配，不会冒充协议 20 接入。

## 中央代理

后端 voicechat-server.properties：

    # 默认自动识别受支持的游戏转发配置，在运行中启用代理模式
    proxy_auto_detect=true
    # 未覆盖的转发集成可设为 true，强制开启代理模式
    proxy_mode=false
    # 直连时使用；代理模式自动同步代理的有效网络配置
    transport_mode=dual

代理 voicechat-proxy.properties：

    # TCP/UDP 同时监听，防火墙和容器必须放行两种协议
    transport_mode=dual
    port=24454
    # 客户端能访问的公网地址；两种协议使用相同地址和端口
    voice_host=voice.example.com:24454

### 自动代理模式与配置顺序

启动时读取已加载的 Paper Velocity、Spigot/BungeeCord、旧版 Paper Velocity 转发设置，
也识别 Fabric 服务端加载的 FabricProxy-Lite。检测成功会记录来源，在监听语音端口、
返回握手端口和处理路由之前启用代理模式。此时后端不监听语音端口，向代理返回端口 -1。
检测结果不改写文件中的 proxy_mode；移除转发配置后重启即可恢复直连。
普通 TUN、HTTP、SOCKS 网络代理和客户端发送的插件消息均不会触发此功能。

自动检测只能识别游戏代理环境，不能证明代理已经安装语音插件；代理端仍需安装本分支插件。
其他转发集成（包括其他 NeoForge 代理模组）请手动设置 proxy_mode=true。
若使用游戏代理但需要后端直连语音，请同时设置 proxy_auto_detect=false 和 proxy_mode=false。
proxy_mode=true 始终强制启用代理模式，不受自动检测开关影响。

配置按「网络 → 代理 → 语音规则 → 音频 → 玩家接入 → 高级」排列。
升级保留现有配置值，补齐新选项及说明注释；修改部署方式、端口或协议后请重启。

只需要在代理开放公网语音 TCP/UDP 端口。后端仍通过可信 Minecraft 插件消息上报身份和路由，
代理按接收者的会话密钥和传输方式发送音频；音频插件及 API 音频沿用现有后端回传通道。
切服清除 UDP/TCP 会话、凭据和路由；旧后端数据不能继续授权转发。
完全未经修改的上游后端不提供中央代理所需路由信息，不在此实现的支持范围内。
距离和群组仍局限于当前子服，现有 Folia 限制保持不变。

原版客户端没有本分支的 availability 扩展，因此不会获得其专用状态提示或客户端停麦指令。
服务端仍执行旁观者、权限、禁用和路由过期检查，拒绝不允许的音频。
TCP 专用的 0x0B/0x0C/0x0D 消息及 Minecraft availability 消息不会发送给 UDP 客户端。

## 协议与安全边界

Minecraft secret 请求中的版本决定玩家的协议，实际语音入口必须与之匹配。
同一游戏登录期间不能用重复 secret 请求切换协议，避免把 TCP 会话降级为原版认证。
TCP 继续使用 challenge/proof、HKDF 分方向密钥以及严格递增的音频序号。
UDP 使用上游 shared-secret AES-GCM、Authenticate/Ack、ConnectionCheck/Ack 和 keepalive。
UDP 的保护强度保持上游语义，不具备 TCP 扩展的逐连接 challenge 或同等防重放保证。

原版 MicThread 在重载音频设备后重置序号，而且 UDP 可能乱序，所以 UDP 不采用 TCP 的严格递增检查。
来自另一 UDP 端点的认证包不能替换仍存活的会话；地址变化需要原会话超时或游戏会话重建。
认证后的数据包同时绑定玩家 UUID 和端点；TCP 端点还包含每次连接独有的标识。
同一个 IP:port 的 TCP、UDP 不会混淆，关闭 TCP 不会删除同地址的 UDP 会话。

UDP 数据报上限为 4096 字节，超限数据报整包丢弃，业务包仍受原有更小的大小限制。
接收队列有界，TCP、UDP 有独立队列并交替调度；坏包、UDP 密文篡改不会关闭其他正常会话。
UDP 未通过认证前不建立持久代理会话。公网部署仍应保护 Minecraft 身份/密钥通道及后端网络。

## 插件 API

保留 VoicechatSocket / RawUdpPacket 的公开名称和方法，不强制插件重新编译。
默认实现返回带协议的 VoiceEndpoint；TCP 还带连接标识。插件应把 SocketAddress 当作不透明端点，
不要假设它必然是 InetSocketAddress。需要远端地址时可以读取 VoiceEndpoint.remote()。
既有自定义 TCP socket 返回普通 SocketAddress 时仍按 TCP 处理。
自定义 UDP/双协议 socket 必须给 UDP 包使用 VoiceEndpoint.udp(remote)，并自行实现对应 send 路由；
覆盖默认 socket 的第三方插件不会自动获得双协议支持，需单独验证。

## 验证

JDK 25：

    pwsh -File scripts/test-tcp.ps1
    pwsh -File scripts/test-proxy.ps1
    pwsh -File scripts/test-runtime.ps1
    python tests/client/run.py

Linux/macOS 对应使用 scripts/test-tcp.sh、test-proxy.sh、test-runtime.sh。
传输测试覆盖同数字端口双监听、同源地址分离、绑定回滚、UDP 超限丢弃、关闭与重连。
混合代理测试独立按上游布局序列化 UDP 包，覆盖 TCP→UDP、UDP→TCP、UDP→UDP、群组/耳语、
重排/序号重置、重复认证、拒绝跨协议认证、插件回传、切服及旧密钥失效。

直连数据包测试需要 Gradle 已解析的 Netty 4.1 依赖：

    python tests/direct/run.py

它编译 common 和 Bukkit 的实际 NetworkMessage、ClientConnection、加密及包类，
使用平台边界替身验证版本/端点匹配、身份一致性、认证前拒绝音频及收发密钥选择。
它不启动 Minecraft，也不能代替真实游戏中的声卡、平台加载器和插件联调。

上线前应使用两个匹配游戏版本的真实客户端（原版 UDP + 本分支 TCP），验证双方听音、距离/群组/耳语、
禁用/权限/旁观者、麦克风设备重载、UDP NAT 超时重连和代理切服。

## 自动配置同步

Paper/Folia 请使用 tcp.3.4 或更新的后端构建：tcp.3.3 的 Paper 专用入口可能在语音认证前丢弃配置回复，形成认证互相等待。`Voice proxy configuration sync timed out` 本身说明已经进入代理模式，不表示需要手动开启代理模式。新构建在 1、3 秒重试未完成的请求，应答完成后立即停止；5 秒仍未应答才提示。代理端 tcp.3.3 已支持此同步协议，建议两端使用本次同版本构建以便排查。

代理和后端均需升级到 tcp.3.3 或更新的匹配构建。保留 proxy_auto_detect=true 即可根据游戏转发配置自动选择代理模式，无需手动修改 proxy_mode。未覆盖的自定义转发仍可手动强制开启。

玩家加入或申请语音密钥时，后端主动发送带一次性标识的配置请求。代理返回有效传输模式、传输优先顺序、公网语音端口、voice_host。后端仅在运行时应用网络快照；公网端点由代理写入返回客户端的 secret。距离、权限、群组、审核及限流规则不参加覆盖。新配置默认 dual；旧 tcp 配置也因 allow_original_clients=true 默认启用 UDP。代理是网络设置来源，后端同名设置不需要手动对齐。

5 秒内未得到匹配回复就停止该次语音认证，并提示正在请求语音的玩家和管理员检查代理插件版本。没有语音客户端的玩家不显示同步超时消息。过期、重复、伪造客户端来源的配置消息不能完成认证。更改代理网络配置后完整重启代理并让玩家重连，后端配置文件保持原样。该机制依赖受保护的后端游戏连接；后端游戏端口不应允许绕过代理直连。没有在线玩家时不建立独立配置连接，在首次玩家加入时同步。
