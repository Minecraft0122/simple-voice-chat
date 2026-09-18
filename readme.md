# Simple Voice Chat TCP — Minecraft 26+

这是 [henkelmax/simple-voice-chat](https://github.com/henkelmax/simple-voice-chat) 的 TCP 分支。
客户端和服务端的语音数据均通过独立 TCP 连接传输，保留上游 Opus 编码、AES 加密、距离语音、群聊及插件 API。

## 版本与安装

| 分支 | Minecraft | 目标平台 |
| --- | --- | --- |
| `tcp/26.1` | 26.1 / 26.1.1 / 26.1.2 | Fabric、NeoForge、Forge、Paper |
| `tcp/26.2` | 26.2 | Fabric、NeoForge、Forge、Paper |
| `tcp/26.3` | 26.3 | Fabric、NeoForge、Paper |

仅维护 26+。各游戏版本分别构建；不要跨 Minecraft 版本使用同一个模组 JAR。
请从本仓库对应分支的 Actions 构建产物获取文件，原版下载链接提供的是 UDP 版本。

1. 服务端安装本分支对应平台的模组；Paper 服务器将 Paper JAR 放入 `plugins/`。
2. **所有玩家也必须安装本分支对应游戏版本的客户端模组**。仅替换服务器插件无法让原版客户端改用 TCP。
3. 在 `config/voicechat/voicechat-server.properties`（Paper 为 `plugins/voicechat/voicechat-server.properties`）中设置 `port=24454`，并放行/转发 **24454/TCP**。
4. 语音 TCP 端口必须与 Minecraft 游戏端口不同。旧配置 `port=-1` 会改用 `24454`；`port=0` 会选择空闲端口。
5. 若使用端口映射或独立语音域名，将 `voice_host` 设为玩家可连接的 `域名:外部TCP端口`。

局域网开放世界时保留自动分配的独立语音端口，游戏聊天栏会显示该端口。
Minecraft 代理可以继续转发游戏连接，但语音必须直连后端或通过普通 TCP 端口转发。
**不支持上游 UDP 语音代理（Velocity / BungeeCord）、UDP 检测工具或替换为 UDP 的第三方 socket 实现。**
26.3 中继承的旧 Bukkit / 语音代理代码不属于本分支构建和支持范围。
本分支的协议兼容编号为 `1020`，原版 UDP 客户端会收到版本不兼容提示。

## 构建与验证

需要 JDK 25。Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`。

```powershell
.\gradlew.bat :fabric:build :paper:build :neoforge:build
# 仅 26.1 / 26.2：
.\gradlew.bat :forge:build
# 不需要下载 Minecraft 或 Gradle 依赖的真实 TCP 网络测试：
pwsh -File scripts/test-tcp.ps1
```

产物位于各平台的 `build/libs/`，选择不带 `-sources` / `-javadoc` / `-dev` 后缀的模组或插件 JAR。
Linux/macOS 可运行 `bash scripts/test-tcp.sh`。网络测试覆盖拆包、粘包、非法长度、并发发送、多客户端路由、关闭及重连。
协议和实现细节见 [docs/tcp-transport.md](docs/tcp-transport.md)。

TCP 遇到丢包时会按顺序重传，因此网络不稳定时可能增加语音延迟。
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

This fork uses port `24454` **TCP** by default. Both server and clients must use this fork.
Without opening this port, the voice chat will not work.
This port can be changed in the server config.
See the TCP installation instructions above; upstream UDP setup instructions do not apply to this fork.

The voice chat is encrypted, but we don't guarantee the security of it. Use at your own risk!
