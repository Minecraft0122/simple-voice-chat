package de.maxhenkel.voicechat.voice.transport;

/** Stable wire reasons, shared by client, backend and proxy. */
public final class VoiceAvailability {
    public static final int AVAILABLE = 0;
    public static final int WAITING = 1;
    public static final int UNAVAILABLE = 2;
    public static final int INTERNAL_ERROR = 3;
    public static final int SPECTATOR = 4;
    public static final int NO_PERMISSION = 5;
    public static final int DISABLED = 6;
    private VoiceAvailability() {}

    public static String translationKey(int status) {
        return switch (status) {
            case AVAILABLE -> "message.voicechat.available";
            case WAITING -> "message.voicechat.waiting_for_server";
            case UNAVAILABLE -> "message.voicechat.unavailable_on_server";
            case SPECTATOR -> "message.voicechat.spectator_disabled";
            case NO_PERMISSION -> "message.voicechat.no_speak_permission";
            case DISABLED -> "message.voicechat.voice_disabled";
            default -> "message.voicechat.server_internal_error";
        };
    }
}
