package de.maxhenkel.voicechat.voice.transport;

import java.util.Locale;

public enum TransportMode {
    TCP, UDP, DUAL;

    public boolean tcp() { return this != UDP; }
    public boolean udp() { return this != TCP; }
    public boolean supports(VoiceProtocol protocol) {
        return protocol != null && (protocol == VoiceProtocol.TCP ? tcp() : udp());
    }
    public static TransportMode parse(String value) {
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException e) { throw new IllegalArgumentException(
                "transport_mode must be tcp, udp or dual", e); }
    }
}
