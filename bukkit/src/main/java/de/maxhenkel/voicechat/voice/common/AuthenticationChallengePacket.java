package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.voice.transport.VoiceHandshake;

public class AuthenticationChallengePacket implements Packet<AuthenticationChallengePacket> {
    private byte[] challenge;
    public AuthenticationChallengePacket() {}
    public AuthenticationChallengePacket(byte[] value) {
        if (value.length != VoiceHandshake.SIZE) throw new IllegalArgumentException("Invalid handshake length");
        challenge = value.clone();
    }
    public byte[] getData() { return challenge.clone(); }
    @Override public AuthenticationChallengePacket fromBytes(FriendlyByteBuf buf) {
        challenge = new byte[VoiceHandshake.SIZE];
        buf.readBytes(challenge);
        return this;
    }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeBytes(challenge); }
}
