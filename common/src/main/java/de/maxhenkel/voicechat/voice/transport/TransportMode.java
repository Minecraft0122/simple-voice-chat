package de.maxhenkel.voicechat.voice.transport;

import java.util.Locale;

public enum TransportMode {
    TCP, UDP, DUAL;

    public boolean tcp() { return this != UDP; }
    public boolean udp() { return this != TCP; }
    public boolean supports(VoiceProtocol protocol) {
        return protocol != null && (protocol == VoiceProtocol.TCP ? tcp() : udp());
    }
    public VoiceProtocol select(String preference, int clientCapabilities) {
        String value = preference == null ? "tcp,udp" : preference;
        for (String item : value.split(",")) {
            VoiceProtocol protocol;
            try {
                protocol = VoiceProtocol.valueOf(item.trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                continue;
            }
            if (supports(protocol) && (clientCapabilities & protocol.capabilityBit()) != 0) return protocol;
        }
        return null;
    }
    public static TransportMode parse(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException e) { throw new IllegalArgumentException(
                "transport_mode must be tcp, udp or dual", e); }
    }
}
