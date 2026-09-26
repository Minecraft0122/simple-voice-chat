package de.maxhenkel.voicechat.voice.client;

import de.maxhenkel.voicechat.intercompatibility.ClientCompatibilityManager;
import de.maxhenkel.voicechat.voice.common.*;
import de.maxhenkel.voicechat.voice.transport.VoiceAvailability;
import java.util.function.BooleanSupplier;

public class ClientLifecycleTest {
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        if (!condition.getAsBoolean()) throw new AssertionError("Lifecycle timed out");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        ClientVoicechat client = new ClientVoicechat();
        ClientCompatibilityManager.INSTANCE.disconnected = client::onVoiceChatDisconnected;
        ClientCompatibilityManager.INSTANCE.connected = client::onVoiceChatConnected;
        try {
            client.connect(new InitializationData());
            ClientVoicechatConnection original = client.getConnection();
            check(!original.sendToServer(new NetworkMessage(new MicPacket(new byte[]{1}, false, 0))), "audio blocked before availability and authentication");
            TestSocket.sockets.getFirst().ready();
            await(() -> original.canSendAudio());
            for (int i = 0; i < 100; i++) original.nextAudioSequence();
            client.reloadAudio();
            check(client.getConnection() == original && original.nextAudioSequence() == 100, "audio reload keeps the TCP session and its sequence counter");
            TestSocket.sockets.getFirst().close();
            await(() -> TestSocket.sockets.size() == 2);
            check(client.getConnection() != null && client.getConnection() != original, "disconnect event does not cancel scheduled reconnect");
            ClientVoicechatConnection reconnected = client.getConnection();
            check(!reconnected.canSendAudio(), "reconnect waits for fresh availability before sending audio");
            TestSocket.sockets.get(1).ready();
            await(reconnected::canSendAudio);
            client.setAvailability(VoiceAvailability.SPECTATOR);
            check(client.getMicThread() == null && !reconnected.sendToServer(new NetworkMessage(new MicPacket(new byte[]{2}, false, 101))), "spectator status stops microphone and outbound audio");
            client.setAvailability(VoiceAvailability.AVAILABLE);
            check(reconnected.canSendAudio() && client.getMicThread() != null, "leaving spectator mode resumes audio without reauthentication");
            client.resetBackend();
            check(client.getConnection() == null && client.getInitializationData() == null && !client.canSendAudio(),
                    "backend switch discards previous credentials and stops audio immediately");
            client.setAvailability(VoiceAvailability.UNAVAILABLE);
            check(client.getConnection() == null && client.getInitializationData() == null, "unavailable backend closes session and clears credentials");
            int count = TestSocket.sockets.size();
            Thread.sleep(1200);
            check(TestSocket.sockets.size() == count, "unavailable backend does not keep reconnecting");
        } finally { client.close(); }
        System.out.println("Client lifecycle: 9 checks passed");
    }
}
