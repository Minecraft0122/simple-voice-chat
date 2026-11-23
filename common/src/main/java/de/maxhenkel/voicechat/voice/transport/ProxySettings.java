package de.maxhenkel.voicechat.voice.transport;

import java.io.*;

/** Network settings owned by the public voice proxy; never includes keys or gameplay rules. */
public record ProxySettings(TransportMode mode, String preference, int port, String voiceHost) {
    public ProxySettings {
        if (mode == null || !("tcp,udp".equals(preference) || "udp,tcp".equals(preference))
                || port < 1 || port > 65535 || voiceHost == null || voiceHost.length() > 255
                || voiceHost.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid voice proxy network settings");
        }
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1); // Independently versioned; unknown schemas must fail closed.
            out.writeUTF(mode.name()); out.writeUTF(preference); out.writeInt(port); out.writeUTF(voiceHost);
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    public static ProxySettings decode(byte[] payload) {
        if (payload.length > 1024) throw new IllegalArgumentException("Oversized proxy settings");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != 1) throw new IllegalArgumentException("Unsupported proxy settings schema");
            ProxySettings settings = new ProxySettings(TransportMode.parse(in.readUTF()), in.readUTF(), in.readInt(), in.readUTF());
            if (in.available() != 0) throw new IllegalArgumentException("Trailing proxy settings data");
            return settings;
        } catch (IOException e) { throw new IllegalArgumentException("Invalid proxy settings", e); }
    }
}
