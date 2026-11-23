# Simple Voice Chat TCP — Minecraft 26+

这是 [henkelmax/simple-voice-chat](https://github.com/henkelmax/simple-voice-chat) 的 TCP 分支。
本分支客户端使用独立 TCP 连接；当前 tcp/26.3 服务端新增可选 TCP/UDP 双协议接入，兼容协议 20 的原版 UDP 客户端，保留上游 Opus 编码、AES 加密、距离语音、群聊及插件 API。

设置 transport_mode=dual 后，原版 UDP 与本分支 TCP 玩家可以互相语音。新配置默认 dual；旧 tcp 配置在 allow_original_clients=true（默认）时自动按 dual 运行，不改写原值。开启方法、中央代理配置和兼容边界见 [双协议部署说明](docs/dual-transport.md)。

## 版本与安装

| 分支 | Minecraft | 目标平台 |
| --- | --- | --- |
| `tcp/26.1` | 26.1 / 26.1.1 / 26.1.2 | Fabric、NeoForge、Forge、Paper |
| `tcp/26.2` | 26.2 | Fabric、NeoForge、Forge、Paper |
| `tcp/26.3` | 26.3 | Fabric、NeoForge、Bukkit、Paper、Velocity、BungeeCord |
| `tcp/1.21.11` | 1.21.11 客户端 | Fabric、NeoForge、Forge、Paper |

客户端另提供 1.21.11 构建。各游戏版本分别构建；不要跨 Minecraft 版本使用同一个模组 JAR。所有客户端和后端已统一到协议 1021，Velocity/BungeeCord 代理 JAR 在 26.3 分发包。
请从本仓库发布页下载对应版本文件，原版下载链接提供的是 UDP 版本。

1. 服务端安装本分支对应平台的模组；Paper 服务器将 Paper JAR 放入 `plugins/`。
2. TCP 玩家安装本分支对应游戏版本的客户端模组；使用新增双协议服务端时，原版协议 20 客户端可通过 UDP 加入。客户端继续使用各自原有传输方式。
3. 直连后端时，在 `config/voicechat/voicechat-server.properties`（Paper 为 `plugins/voicechat/voicechat-server.properties`）中设置 `port=24454`，并放行/转发 **24454/TCP**；使用 transport_mode=dual 时，还需放行/转发 **24454/UDP**。
4. 语音 TCP 端口必须与 Minecraft 游戏端口不同。旧配置 `port=-1` 会改用 `24454`；**`port=0` 会拒绝启动语音服务**，必须使用固定端口。
5. 若使用端口映射或独立语音域名，将 `voice_host` 设为玩家可连接的 `域名:外部语音端口`。

局域网开放世界也使用配置的固定语音端口（默认 24454），游戏聊天栏会显示该端口。
Minecraft 代理可以使用 26.3 分支内置的集中 TCP 语音代理。每个后端服都安装同一分支的服务端插件，并在其配置中保留默认 `proxy_auto_detect=true`，自动识别游戏代理转发；后端不再监听玩家语音 TCP 连接，只通过 `voicechat:proxy_routing` 插件消息发送玩家状态、距离和目标 UUID。Velocity/BungeeCord 代理默认监听公共 `24454/TCP`（双协议模式另监听同端口 UDP），直接完成客户端认证、解密、路由和重新加密，普通距离/群组语音不经过子服链路。若子服扩展注册了麦克风、声音、距离事件或音频监听器，自动启用该子服的音频处理回传，以保证禁言、过滤、自定义路由与录音有效；插件主动播放的音频也通过代理发送。
代理配置文件为 `voicechat-proxy.properties`，至少确认 `port=24454`，并将玩家可访问的 `voice_host` 设置为代理地址。TCP/dual 模式下，代理的游戏端口和语音端口必须不同。

本分支 TCP 玩家进入没有语音插件的子服时，会收到“语音聊天在此子服不可用”的提示，客户端停止发送音频并关闭旧语音连接。子服 UUID 映射缺失或矛盾时，停止该玩家语音，客户端提示服务器内部错误，代理控制台记录玩家和子服信息。默认 `allow_spectator_voice=false`，旁观者无法发送或接收语音；离开旁观模式会恢复。语音暂时受限时可以保留认证连接和控制心跳，但不会发送麦克风数据。

当前集中模式的距离和群组目标仍限定在同一子服，没有全网跨子服群组目录。路由变化时发送完整快照，不变时每秒只发送短心跳。使用双协议构建时，代理默认 dual，子服在认证前自动同步代理的有效网络配置；TCP 客户端使用 1021，原版 UDP 客户端使用 20。旧服务端构建仍仅接受 1021。代理 JAR 从 `tcp/26.3` 分发包获取，客户端和后端则安装各自 Minecraft 版本的构建。
Folia 26.1.2 后端必须启用 `proxy_mode=true`；玩家位置和权限通过每个玩家的区域调度器读取。Folia 不执行需要服务器音频回传的插件处理钩子，若检测到此类插件会在控制台报告并停用该后端的语音。
本分支 TCP 协议号保持 `1021`；双协议服务端额外接受上游 UDP 协议 `20`。只有显式设置 `allow_original_clients=false` 且 `transport_mode=tcp` 时才拒绝原版 UDP 客户端。

当前 26.3 构建默认根据 Paper/Spigot 游戏转发设置或 FabricProxy-Lite 自动启用代理模式，无需手动开启上述 `proxy_mode`。未覆盖的转发集成仍可手动设置 `proxy_mode=true`。
配置项按网络、代理、语音规则、音频、玩家接入和高级设置排列，并附有注释。如需在游戏代理之后保留后端直连语音，请同时关闭 `proxy_auto_detect` 和 `proxy_mode`。普通 TUN 网络代理不会触发代理模式。

## 原版客户端与自动代理配置（tcp.3.3）

`tcp.3.4` 修复 Paper/Folia 的收包入口在语音认证前丢弃代理配置回复和版本消息的问题。出现 `Voice proxy configuration sync timed out` 表示后端已经进入代理模式，不应通过手动开启 `proxy_mode` 解决此错误。同步请求在第 1、3 秒有限重试，以容忍初次加入/切服期间代理连接尚未完成的情况；成功、断开或过期后不再重试，原 5 秒超时限制保持不变。

- 原版客户端继续使用 UDP，无需安装本分支客户端。服务端支持已发布的协议 20 客户端；CI 会下载 Modrinth 作者项目中对应 Minecraft 版本最新发布的 Fabric 客户端，校验 SHA-512 并检查其协议号。协议变更会使检查失败，不能把未来版本自动视为兼容。
- 新配置默认 `transport_mode=dual`、`allow_original_clients=true`。旧配置即使保留 `transport_mode=tcp`，默认也会按 dual 运行；仅 TCP 部署需显式设置 `allow_original_clients=false`。因此升级时必须确认语音端口 UDP 也已放行。
- `proxy_auto_detect=true` 默认识别 Paper/Folia 的 Velocity 转发、Spigot/BungeeCord 转发和 FabricProxy-Lite；直连环境不进入代理模式。手动 `proxy_mode=true` 用于未覆盖的转发集成；TUN/HTTP/SOCKS 不触发此模式。
- 代理和后端均安装本次构建。后端在玩家加入及每次语音认证前，通过私有插件消息请求配置；代理返回有效 `transport_mode`、`transport_preference`、公网语音端口和 `voice_host`。后端使用该运行时快照，最终客户端端点仍由代理改写。
- 同步带一次性请求标识，客户端来源的私有消息被代理拦截。未收到有效回复时不发放语音密钥，5 秒后向请求语音的玩家及控制台提示同步超时；没有语音客户端的玩家不弹出超时提示。旧代理插件必须和后端一起升级。
- 同步只统一网络入口，不覆盖后端配置文件、距离、权限、群组、限流、禁言和审核缓存。修改代理网络设置后完整重启代理；玩家重新连接时自动获取新快照。无玩家在线时插件消息无法传输，首次玩家加入时同步。
- 当前不承诺尚未发布的 2.7 / 协议 21 开发代码可用；协议号 20 与本分支 TCP 1021 独立协商。CI 通过也不等同于真实 Velocity + Folia 联机验证。

## 构建与验证

需要 JDK 25。Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`。

```powershell
.\gradlew.bat :fabric:build :paper:build :neoforge:build
# 26.3 的 Bukkit / 代理插件：
.\gradlew.bat :bukkit:build :velocity:build :bungeecord:build
# 仅 26.1 / 26.2：
.\gradlew.bat :forge:build
# 不需要下载 Minecraft 或 Gradle 依赖的 TCP 和代理协议测试：
pwsh -File scripts/test-tcp.ps1
pwsh -File scripts/test-proxy.ps1
pwsh -File scripts/test-runtime.ps1
python tests/client/run.py
```

产物位于各平台的 `build/libs/`，选择不带 `-sources` / `-javadoc` / `-dev` 后缀的模组或插件 JAR。
Linux/macOS 可运行 `bash scripts/test-tcp.sh` 和 `bash scripts/test-proxy.sh`。网络测试覆盖拆包、粘包、非法长度、并发发送、多客户端路由、关闭及重连；代理测试覆盖 AES-GCM 篡改检测和认证字段校验。
代理运行测试使用实际 TCP/UDP 连接验证混合语音、原版握手、序号重置及认证防重放、UUID 映射、路由、插件回传和子服可用性；客户端生命周期测试编译实际连接类，用游戏/音频边界替身验证重连、设备重载及停发。加密与压缩评估见 [docs/crypto-evaluation.md](docs/crypto-evaluation.md)。

协议和实现细节见 [docs/tcp-transport.md](docs/tcp-transport.md)。

TCP 遇到丢包时会按顺序重传，因此网络不稳定时可能增加语音延迟。
代理模式会让 Velocity/BungeeCord 成为语音明文的可信边界：代理必须能够读取每个玩家的 AES-GCM 密钥，才能按玩家目标转发后重新加密。当前仍使用 AES-GCM、随机 12 字节 IV 和 128 位认证标签；实现额外校验了密钥长度、密钥数组保护、短密文和TCP 代理会话的 Mic 序号回退/重复（UDP 保持上游序号语义）。Minecraft 离线模式下的插件消息不能单独作为安全的密钥交换，仍应保护游戏连接和代理主机。
分支保留上游授权和署名；以下为上游项目介绍，其中下载和文档链接指向原版项目。

---

[Modrinth](https://modrinth.com/mod/simple-voice-chat)
|
[CurseForge](https://legacy.curseforge.com/minecraft/mc-mods/simple-voice-chat)
|
[Discord](https://discord.gg/4dH2zwTmyX)
|
[Wiki](https://modrepo.de/minecraft/voicechat/wiki)
|
[FAQ](https://modrepo.de/minecraft/voicechat/faq)
|
[Credits](https://modrepo.de/minecraft/voicechat/credits)
|
[API](https://modrepo.de/minecraft/voicechat/api)

# Simple Voice Chat

A proximity voice chat for Minecraft with a variety of [addons](https://modrepo.de/minecraft/voicechat/addons) that offer additional features and functionalities.

:warning: **NOTE** This mod requires special setup on the server in order to work.
Please read the [wiki](https://modrepo.de/minecraft/voicechat/wiki/setup) for more information.

## TCP/UDP compatibility in this fork

For the 26.3 fork builds, the server can accept the original UDP protocol and the fork's TCP protocol. Set `transport_mode` in `voicechat-server.properties` to `udp`, `tcp`, or `dual`. The default is `dual`. With `allow_original_clients=true` (default), an older `tcp` setting also runs as `dual` without rewriting the file. Set that option to `false` to enforce TCP-only access.

When using `dual`, forward both TCP and UDP on the configured voice port. TCP and dual mode require a voice port different from the Minecraft game TCP port. `transport_preference` controls which transport a dual-capable fork client tries first; it does not enable a transport that `transport_mode` has disabled.

TCP clients require a matching client build from this fork. UDP mode remains compatible with the original UDP protocol. For Velocity or BungeeCord, install the matching fork build on the proxy and every backend, The backend automatically detects supported game forwarding and synchronizes effective network settings from the proxy before voice authentication.

<p align="center">
    <a href="https://discord.gg/4dH2zwTmyX">
        <img src="assets/discord.svg" width="300">
    </a>
    <br/>
    <i>Please join the Discord if you have questions!</i>
</p>

## Downloads

- [Fabric](https://modrinth.com/mod/simple-voice-chat/versions?l=fabric)
- [NeoForge](https://modrinth.com/mod/simple-voice-chat/versions?l=neoforge)
- [Forge](https://modrinth.com/mod/simple-voice-chat/versions?l=forge)
- [Bukkit/Spigot/Paper](https://modrinth.com/plugin/simple-voice-chat/versions?l=bukkit)
- [Quilt](https://modrinth.com/mod/simple-voice-chat/versions?l=quilt)
- [Velocity](https://modrinth.com/mod/simple-voice-chat/versions?l=velocity)
- [BungeeCord/Waterfall](https://modrinth.com/mod/simple-voice-chat/versions?l=bungeecord)

## Features

- Push to talk
- Voice activation
- Proximity voice chat
- Password protected groups
- Automatic voice activity detection
- Automatic microphone gain adjustment
- [Opus codec](https://opus-codec.org/)
- [RNNoise](https://jmvalin.ca/demo/rnnoise/) recurrent neural network noise suppression
- OpenAL audio
- Cross compatibility between Fabric, NeoForge, Forge, Quilt, Bukkit, Spigot and Paper
- Support for Velocity, BungeeCord and Waterfall
- Compatibility with [ModMenu](https://modrinth.com/mod/modmenu) (Use [ClothConfig](https://modrinth.com/mod/cloth-config) for a better configuration UI)
- Configurable push to talk key
- Microphone and speaker test playback
- Configurable voice distance
- Whispering
- Individual player volume adjustment
- Microphone amplification
- 3D sound
- AES encryption
- Audio recording with separate audio tracks
- A powerful [API](https://modrepo.de/minecraft/voicechat/api)
- Many [addons](https://modrepo.de/minecraft/voicechat/addons)

## Icons

|                  Icon                   | Description                                           |
|:---------------------------------------:|-------------------------------------------------------|
|      ![](assets/icon_talking.png)       | You are talking                                       |
|     ![](assets/icon_whispering.png)     | You are whispering                                    |
|   ![](assets/icon_other_talking.png)    | Player is talking                                     |
|  ![](assets/icon_other_whispering.png)  | Player is whispering                                  |
|  ![](assets/icon_microphone_muted.png)  | Microphone muted                                      |
|      ![](assets/icon_disabled.png)      | Voice chat disabled                                   |
|    ![](assets/icon_disconnected.png)    | Voice chat not connected<br/>Voice chat not installed |

## The GUI

You can open the voice chat GUI by pressing the `V` key.
This allows you to open the settings, group chats, mute yourself, disable the voice chat, start/stop a recording and hide all icons.

![](assets/screenshot_voice_chat_menu.png)

### Group Chats

Group chats allow you to talk to players that are not in your vicinity.
To open the group chat interface, either press the group button in the voice chat GUI or just press the group key.

To create a new group, just type a name in the text field and press the button at the bottom.

![](assets/screenshot_create_group.png)

Creating or joining a group will bring you into the group chat interface.
You will also see the heads of the group members in the top left corner of your screen.
Talking players will be outlined.
You can disable these icons by pressing the third button from the left.

![](assets/screenshot_group.png)

Players that are not in a group will see a group icon next to your head, indicating that you are in a group.

You can invite players to your group chat by entering the command `/voicechat invite <playername>` or from the social interactions screen.

### Settings

You can access the voice chat settings by pressing the `V` key and pressing the settings button.

This menu offers the ability to set up your voice chat audio settings.

By clicking the microphone button, you can test your microphone activation.

![](assets/screenshot_settings.png)

## Important Notes

This fork uses port `24454` **TCP and UDP** by default. Original protocol-20 clients can connect over UDP; TCP clients use this fork.
Without opening this port, the voice chat will not work.
This port can be changed in the server config.
Forward both enabled transports. In central proxy mode only the proxy owns the public voice listener; backends synchronize network settings automatically.

The voice chat is encrypted, but we don't guarantee the security of it. Use at your own risk!
