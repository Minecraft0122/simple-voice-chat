package de.maxhenkel.voicechat.config;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.configbuilder.entry.ConfigEntry;
import de.maxhenkel.opus4j.OpusEncoder;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.voice.common.AudioUtils;

public class ServerConfig {

    public ConfigEntry<String> transportMode;
    public ConfigEntry<String> transportPreference;

    public ConfigEntry<Integer> voiceChatPort;
    public ConfigEntry<String> voiceChatBindAddress;
    public ConfigEntry<Double> voiceChatDistance;
    public ConfigEntry<Double> whisperDistance;
    public ConfigEntry<Codec> voiceChatCodec;
    public ConfigEntry<Integer> voiceChatMtuSize;
    public ConfigEntry<Integer> tcpRateLimit;
    public ConfigEntry<Integer> keepAlive;
    public ConfigEntry<Boolean> groupsEnabled;
    public ConfigEntry<String> voiceHost;
    public ConfigEntry<Boolean> allowRecording;
    public ConfigEntry<Boolean> spectatorInteraction;
    public ConfigEntry<Boolean> spectatorPlayerPossession;
    public ConfigEntry<Boolean> forceVoiceChat;
    public ConfigEntry<Integer> loginTimeout;
    public ConfigEntry<Double> broadcastRange;
    public ConfigEntry<Boolean> allowPings;
    public ConfigEntry<Boolean> useNatives;
    public ConfigEntry<Boolean> threadedServerSupport;
    public ConfigEntry<Boolean> proxyMode;
    public final ConfigEntry<Boolean> proxyAutoDetect;
    private boolean detectedProxy;
    public ConfigEntry<Boolean> allowSpectatorVoice;
    public ConfigEntry<Integer> pluginBandwidthLimitMbps;
    public ConfigEntry<Integer> serverBandwidthLimitMbps;
    public ConfigEntry<String> serverBandwidthMetricsFile;
    public ConfigEntry<Integer> bandwidthRecoveryPercent;
    public ConfigEntry<Integer> voiceTalkerLeaseSeconds;
    public ConfigEntry<Boolean> voiceModerationCacheEnabled;
    public ConfigEntry<Integer> voiceModerationCacheHours;
    public ConfigEntry<Integer> voiceModerationCacheMaxMb;

    public ServerConfig(ConfigBuilder builder, Voicechat.Loader loader) {

        builder.header(String.format("%s server config v%s", CommonCompatibilityManager.INSTANCE.getModName(), CommonCompatibilityManager.INSTANCE.getModVersion()));

        transportMode = builder.stringEntry("transport_mode", "tcp",
                "[Network / 网络] tcp = fork protocol 1021; udp = upstream protocol 20; dual = both. Default: tcp.",
                "tcp：本分支客户端；udp：原版 UDP 客户端；dual：同时接入两者。修改后重启生效。",
                "Dual binds TCP and UDP on the same address and port; forward BOTH in firewalls and containers.",
                "dual 必须同时放行 TCP/UDP；服务端兼容两种客户端，不会改变客户端使用的协议。");
        de.maxhenkel.voicechat.voice.transport.TransportMode.parse(transportMode.get());
        transportPreference = builder.stringEntry("transport_preference", "tcp,udp",
                "Protocol preference for dual-capable clients. Supported values: tcp,udp or udp,tcp.",
                "双协议客户端优先使用的协议；可选 tcp,udp 或 udp,tcp。只控制首选顺序，不会禁用 transport_mode 中的协议。");

        voiceChatPort = builder
                .integerEntry("port", 24454, -1, 65535,
                        "Voice port (default 24454). No backend listener in proxy mode; port=0 is always invalid.",
                        "默认 24454；TCP/dual 必须与游戏 TCP 端口不同；代理模式下后端不绑定该端口。",
                        "Audio packets use the transports selected by transport_mode on the port number",
                        "specified here, independently of other networking used for the game server.",
                        "Forward each enabled transport. TCP/dual requires a separate port from Minecraft.",
                        "The legacy value '-1' uses 24454; sharing the game TCP port is not supported.",
                        "Port 0 is forbidden and prevents the voice service from starting.",
                        "This option only works on dedicated servers."
                );

        voiceChatBindAddress = builder
                .stringEntry("bind_address", "",
                        "The server IP address to bind the voice chat to",
                        "Leave blank to use the 'server-ip' property from the 'server.properties' config file",
                        "To bind to the wildcard IP address, use '*'",
                        "This option only works on dedicated servers"
                );

        voiceHost = builder
                .stringEntry("voice_host", "",
                        "The hostname that clients should use to connect to the voice chat",
                        "This may also include a port, e.g. 'example.com:24454' or just a port, e.g. '24454'",
                        "Leave blank to use the game hostname. Both transports use the same public endpoint.",
                        "留空使用游戏服务器地址；dual 的 TCP/UDP 公网地址和端口必须一致。代理模式请在代理端配置。",
                        "This option only works on dedicated servers"
                );

        proxyMode = builder.booleanEntry("proxy_mode", false,
                "Folia: use the matching Paper backend JAR and enable proxy_mode; restart after changes",
                "Folia 请使用对应游戏版本的 Paper 插件并开启此项，修改后完整重启",
                "[Proxy / 代理] Force central voice proxy mode. Default: false; automatic detection still applies.",
                "强制启用中央语音代理；false 时仍可自动识别。true 时后端仅上报路由，不监听语音端口。",
                "Install this fork on Velocity/BungeeCord and every backend, with matching transport_mode.",
                "代理与后端必须安装本分支并设置相同的 transport_mode；普通 TUN/HTTP/SOCKS 代理不属于此模式。");

        proxyAutoDetect = builder.booleanEntry("proxy_auto_detect", true,
                "Automatically enable proxy mode when supported game-proxy forwarding is enabled.",
                "默认 true：启动时识别 Paper Velocity、Spigot/BungeeCord 转发及 FabricProxy-Lite。",
                "The detected mode is logged and used for this run; it does not overwrite proxy_mode in this file.",
                "自动检测结果仅在本次运行生效并输出日志，不回写 proxy_mode。修改配置后请重启。",
                "Without this fork's voice proxy plugin, disable BOTH proxy_auto_detect and proxy_mode for direct voice.",
                "若游戏代理未安装本分支语音插件而需要后端直连，请将两个代理选项均设为 false。");

        threadedServerSupport = builder
                .booleanEntry("threaded_server_support", Voicechat.Loader.PAPER.equals(loader),
                        "Folia: keep threaded server support enabled for region-threaded servers.",
                        "Folia：区域线程服务端必须保持此项开启；修改后请完整重启。",
                        "If the voice chat should support servers that run multiple threads, like Folia",
                        "Disabling this may slightly improve performance, but can cause issues on servers that don't run everything on a single thread"
                );

        voiceChatDistance = builder
                .doubleEntry("max_voice_distance", 48D, 1D, 1_000_000D,
                        "[Voice rules / 语音规则] Audible distance (blocks)"
                );

        whisperDistance = builder
                .doubleEntry("whisper_distance", 24D, 1D, 1_000_000D,
                        "The distance to which the voice can be heard when whispering"
                );

        broadcastRange = builder
                .doubleEntry("broadcast_range", -1D, -1D, Double.MAX_VALUE,
                        "The range in which the voice chat should broadcast audio",
                        "A value less than 0 means 'max_voice_distance'"
                );

        groupsEnabled = builder
                .booleanEntry("enable_groups", true,
                        "If group chats are allowed"
                );

        allowRecording = builder
                .booleanEntry("allow_recording", true,
                        "If players are allowed to record the voice chat audio"
                );

        allowSpectatorVoice = builder.booleanEntry("allow_spectator_voice", false, "Allow spectators to send and receive voice chat. Disabled by default.");

        pluginBandwidthLimitMbps = builder.integerEntry("plugin_bandwidth_limit_mbps", 0, 0, Integer.MAX_VALUE,
                "[Voice protection / 语音保护] Stop admitting new talkers at this plugin voice payload rate; 0 disables the limit.",
                "插件语音收发速率达到此值后停止接纳新的说话者；0 表示不限制。单位 Mbps。",
                "Existing talkers keep a short lease; frames from new talkers are rejected until usage recovers.",
                "已开始说话的玩家可在租约期内继续；流量恢复后才接纳新的说话者。");
        serverBandwidthLimitMbps = builder.integerEntry("server_bandwidth_limit_mbps", 0, 0, Integer.MAX_VALUE,
                "Stop admitting new talkers at this whole-server network rate; requires a fresh metrics file. 0 disables the limit.",
                "整台服务器网卡收发速率达到此值后停止接纳新的说话者；需要有效的网卡指标文件；0 表示不限制。单位 Mbps。");
        serverBandwidthMetricsFile = builder.stringEntry("server_bandwidth_metrics_file", "config/voicechat/network-bandwidth.properties",
                "A local monitor updates rx_bytes_per_second, tx_bytes_per_second and sampled_at_millis once per second.",
                "由本机监控程序每秒更新 rx_bytes_per_second、tx_bytes_per_second、sampled_at_millis。配置整机上限时，缺失或过期指标会停止接纳新说话者。");
        bandwidthRecoveryPercent = builder.integerEntry("bandwidth_recovery_percent", 80, 1, 99,
                "Resume new talkers only below this percentage of the configured limit.",
                "流量低于上限的此百分比后恢复接纳新说话者，用于避免限流状态频繁切换。");
        voiceTalkerLeaseSeconds = builder.integerEntry("voice_talker_lease_seconds", 3, 1, 30,
                "How long an active talker keeps admission after their last audio frame.",
                "玩家最后一个音频包之后保留说话者准入的秒数。");
        voiceModerationCacheEnabled = builder.booleanEntry("voice_moderation_cache_enabled", true,
                "Cache plaintext Opus microphone frames for an external moderation program. Enabled by default in this build; files are unencrypted.",
                "是否将已解密的 Opus 语音片段以明文写入磁盘供外部审核；本版默认开启，文件不加密。如需停止保存请设为 false。");
        voiceModerationCacheHours = builder.integerEntry("voice_moderation_cache_hours", 12, 1, 168,
                "Delete cached voice segments after this many hours.",
                "语音片段在磁盘上保留的小时数，到期自动删除；默认 12 小时。");
        voiceModerationCacheMaxMb = builder.integerEntry("voice_moderation_cache_max_mb", 4096, 64, Integer.MAX_VALUE,
                "Maximum disk space for cached voice segments; oldest unreported segments are removed first.",
                "语音缓存最大磁盘空间，超过后优先删除最早的未举报片段。单位 MB。");

        spectatorInteraction = builder
                .booleanEntry("spectator_interaction", false,
                        "If spectators are allowed to talk to other players"
                );

        spectatorPlayerPossession = builder
                .booleanEntry("spectator_player_possession", false,
                        "If spectators can talk to players they are spectating"
                );

        voiceChatCodec = builder
                .enumEntry("codec", Codec.VOIP,
                        "[Audio / 音频] The Opus codec",
                        "Valid values are 'VOIP', 'AUDIO', and 'RESTRICTED_LOWDELAY'"
                );

        voiceChatMtuSize = builder
                .integerEntry("mtu_size", AudioUtils.MAX_OPUS_PAYLOAD_SIZE, 512, 2048,
                        "The maximum size that audio packets are allowed to have (in bytes)",
                        "Setting this to lower values might cause issues"
                );

        keepAlive = builder
                .integerEntry("keep_alive", 1000, 1000, Integer.MAX_VALUE,
                        "The frequency at which keep-alive packets are sent (in milliseconds)",
                        "Setting this to a higher value may result in timeouts"
                );

        forceVoiceChat = builder
                .booleanEntry("force_voice_chat", false,
                        "[Player access / 玩家接入] Kick players without the voice chat mod"
                );

        loginTimeout = builder
                .integerEntry("login_timeout", 10_000, 100, Integer.MAX_VALUE,
                        "The amount of time the server should wait to check if a player has the mod installed (in milliseconds)",
                        "Only relevant when 'force_voice_chat' is set to 'true'"
                );

        tcpRateLimit = builder
                .integerEntry("tcp_rate_limit", 16, -1, 1024,
                        "[Advanced / 高级] Maximum Minecraft control packets per player per second (not voice audio)",
                        "Set this to -1 to disable the rate limit - This must be greater than 0 in all other cases",
                        "This only applies to voice chat packets that are sent through Minecrafts networking",
                        "This affects actions like opening/joining/leaving voice chat groups or general state changes like disabling/enabling the voice chat"
                );

        allowPings = builder
                .booleanEntry("allow_pings", true,
                        "If the voice chat server should reply to external pings"
                );

        useNatives = builder
                .booleanEntry("use_natives", true,
                        "If the mod should load native libraries on dedicated servers",
                        "This is mostly relevant for voice chat addons"
                );

    }

    /** Resolve at startup before validating or opening a voice listener. */
    public void detectProxy(java.util.function.Predicate<String> modLoaded) {
        detectedProxy = false;
        if (!proxyAutoDetect.get() || proxyMode.get()) return;
        String source = de.maxhenkel.voicechat.voice.transport.ProxyEnvironment.detect(modLoaded);
        detectedProxy = source != null;
        if (detectedProxy) {
            Voicechat.LOGGER.info("Automatically enabled proxy_mode: {}. No backend voice listener; install the matching voice plugin on the proxy.", source);
        }
    }

    public boolean isProxyMode() {
        return proxyMode.get() || detectedProxy;
    }

    public enum Codec {
        VOIP(OpusEncoder.Application.VOIP, OpusEncoderMode.VOIP), AUDIO(OpusEncoder.Application.AUDIO, OpusEncoderMode.AUDIO), RESTRICTED_LOWDELAY(OpusEncoder.Application.LOW_DELAY, OpusEncoderMode.RESTRICTED_LOWDELAY);

        private final OpusEncoder.Application application;
        private final OpusEncoderMode mode;

        Codec(OpusEncoder.Application application, OpusEncoderMode mode) {
            this.application = application;
            this.mode = mode;
        }

        public OpusEncoder.Application getApplication() {
            return application;
        }

        public OpusEncoderMode getMode() {
            return mode;
        }
    }

}
