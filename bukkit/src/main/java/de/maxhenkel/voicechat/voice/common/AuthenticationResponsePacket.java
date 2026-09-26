package de.maxhenkel.voicechat.voice.common;

import de.maxhenkel.voicechat.util.FriendlyByteBuf;
import de.maxhenkel.voicechat.voice.transport.VoiceHandshake;

public class AuthenticationResponsePacket implements Packet<AuthenticationResponsePacket> {
    private byte[] proof;
    public AuthenticationResponsePacket() {}
    public AuthenticationResponsePacket(byte[] value) {
        if (value.length != VoiceHandshake.SIZE) throw new IllegalArgumentException("Invalid handshake length");
        proof = value.clone();
    }
    public byte[] getData() { return proof.clone(); }
    @Override public AuthenticationResponsePacket fromBytes(FriendlyByteBuf buf) {
        proof = new byte[VoiceHandshake.SIZE];
        buf.readBytes(proof);
        return this;
    }
    @Override public void toBytes(FriendlyByteBuf buf) { buf.writeBytes(proof); }
}
