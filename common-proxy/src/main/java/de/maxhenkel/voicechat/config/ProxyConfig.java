package de.maxhenkel.voicechat.config;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.configbuilder.entry.ConfigEntry;
import de.maxhenkel.voicechat.BuildConstants;

public class ProxyConfig {

    public ConfigEntry<String> transportMode;
    public ConfigEntry<Integer> port;
    public ConfigEntry<String> bindAddress;
    public ConfigEntry<String> voiceHost;
    public ConfigEntry<Boolean> allowPings;

    public ProxyConfig(ConfigBuilder builder) {
        builder.header(String.format("Simple Voice Chat proxy config v%s", BuildConstants.MOD_VERSION));

        transportMode = builder.stringEntry("transport_mode", "tcp",
                "[Network / 网络] tcp = fork protocol 1021; udp = upstream protocol 20; dual = both. Default: tcp.",
                "tcp：本分支客户端；udp：原版 UDP 客户端；dual：同时接入两者。修改后重启生效。",
                "Set the same mode on every backend. Dual binds both protocols to the configured port.",
                "代理和后端的 transport_mode 必须一致；dual 必须同时放行防火墙/容器的 TCP 和 UDP。");
        de.maxhenkel.voicechat.voice.transport.TransportMode.parse(transportMode.get());
        port = builder
                .integerEntry("port", 24454, 0, 65535,
                        "The voice port (default 24454). This proxy owns the public voice listener.",
                        "默认 24454；只需在代理开放语音端口；代理模式的后端不监听语音端口。",
                        "Audio packets use the transports selected by transport_mode on the port number",
                        "specified here, independently of other networking used for the game server.",
                        "Use a separate port from Minecraft and forward every enabled transport.",
                        "Port 0 is forbidden and prevents the voice service from starting."
                );

        bindAddress = builder
                .stringEntry("bind_address", "",
                        "The proxy IP address to bind the voice chat to",
                        "Leave blank to use the proxy bind address",
                        "To bind to the wildcard IP address, use '*'",
                        "留空使用代理绑定地址；* 监听所有网卡；不要填写域名作为监听地址。"
                );

        voiceHost = builder
                .stringEntry("voice_host", "",
                        "The hostname that clients should use to connect to the voice chat",
                        "This may also include a port, e.g. 'example.com:24454' or just a port, e.g. '24454'",
                        "Leave blank to use the game proxy hostname. Both transports use the same endpoint.",
                        "留空使用游戏代理地址；dual 的 TCP/UDP 公网地址和端口必须一致。"
                );

        allowPings = builder
                .booleanEntry("allow_pings", true,
                        "[Advanced / 高级] Reply to external voice diagnostics (default true).",
                        "是否回应外部语音连通性检测；不影响游戏服务器列表 Ping。"
                );
    }

}
