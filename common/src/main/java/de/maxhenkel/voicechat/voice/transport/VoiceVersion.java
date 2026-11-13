package de.maxhenkel.voicechat.voice.transport;

/** Release identifiers are diagnostic data, not a replacement for wire negotiation. */
public final class VoiceVersion {
    private VoiceVersion() {}

    public static String sanitize(String version) {
        if (version == null || version.isBlank()) return "unknown";
        return version.replaceAll("[^A-Za-z0-9.+_/-]", "?");
    }

    public static boolean sameRelease(String first, String second) {
        // Minecraft suffixes differ when a proxy links multiple supported game versions.
        return release(first).equals(release(second));
    }

    public static boolean matches(String client, int clientProtocol, String server, int serverProtocol) {
        return sameRelease(client, server) && clientProtocol == serverProtocol;
    }

    private static String release(String version) {
        String safe = sanitize(version);
        int suffix = safe.indexOf('+');
        return suffix < 0 ? safe : safe.substring(0, suffix);
    }

    public static String mismatch(String client, int clientProtocol, String server, int serverProtocol) {
        return "[Voice Chat] Voice chat version mismatch / 语音版本不一致: client / 客户端=" + sanitize(client)
                + " (protocol=" + clientProtocol + "), server / 服务端=" + sanitize(server)
                + " (protocol=" + serverProtocol + "). Install matching TCP Voice Chat releases / 请安装同一发行版本的语音客户端与服务端。";
    }

    public static String unverified(int clientProtocol, String server) {
        return "[Voice Chat] Client release unavailable / 客户端未提供发行版本: client protocol / 客户端协议="
                + clientProtocol + ", server / 服务端=" + sanitize(server)
                + ". Please update the voice client for version diagnostics / 请更新语音客户端以启用版本检查。";
    }
}
