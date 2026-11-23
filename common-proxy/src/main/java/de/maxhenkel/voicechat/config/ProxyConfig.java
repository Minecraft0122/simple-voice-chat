package de.maxhenkel.voicechat.config;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.configbuilder.entry.ConfigEntry;
import de.maxhenkel.voicechat.BuildConstants;

public class ProxyConfig {

    public ConfigEntry<String> transportMode;
    public ConfigEntry<String> transportPreference;
    public ConfigEntry<Boolean> allowOriginalClients;
    public ConfigEntry<Integer> port;
    public ConfigEntry<String> bindAddress;
    public ConfigEntry<String> voiceHost;
    public ConfigEntry<Boolean> allowPings;

    public ProxyConfig(ConfigBuilder builder) {
        builder.header(String.format("Simple Voice Chat proxy config v%s", BuildConstants.MOD_VERSION));

        transportMode = builder.stringEntry("transport_mode", "dual",
                "[Network / 网络] tcp = fork protocol 1021; udp = upstream protocol 20; dual = both. Default: dual.",
                "tcp：本分支客户端；udp：原版 UDP 客户端；dual：同时接入两者。修改后重启生效。",
                "Proxy mode and preference are synchronized to matching backends before authentication. Dual binds both protocols.",
                "代理是网络配置的唯一来源，后端自动同步；dual 必须同时放行防火墙/容器的 TCP 和 UDP。修改后重启代理。");
        de.maxhenkel.voicechat.voice.transport.TransportMode.parse(transportMode.get());
        transportPreference = builder.stringEntry("transport_preference", "tcp,udp",
                "[Network / \u7f51\u7edc] Client transport preference, in order: tcp,udp or udp,tcp.",
                "\u5ba2\u6237\u7aef\u4f20\u8f93\u4f18\u5148\u987a\u5e8f\uff0c\u53ef\u586b tcp,udp \u6216 udp,tcp\u3002\u53ea\u4f1a\u9009\u62e9\u53cc\u65b9\u90fd\u652f\u6301\u7684\u534f\u8bae\u3002",
                "This only affects negotiation; transport_mode still controls which listeners are enabled.");
        allowOriginalClients = builder.booleanEntry("allow_original_clients", true,
                "Default true permits published upstream UDP clients, upgrading old tcp settings to dual at runtime.",
                "默认 true：兼容原版 UDP 客户端，旧 tcp 配置自动按 dual 运行；需同时放行 TCP/UDP。",
                "Set false to keep an intentionally TCP-only endpoint. Synchronized to backends through effective transport mode.",
                "仅 TCP 部署请设 false；代理的有效模式会自动同步后端，无需修改后端同名设置。");
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

    public String getTransportMode() {
        return allowOriginalClients.get() && de.maxhenkel.voicechat.voice.transport.TransportMode.parse(transportMode.get()) == de.maxhenkel.voicechat.voice.transport.TransportMode.TCP ? "dual" : transportMode.get();
    }

}
