package de.maxhenkel.voicechat.voice.transport;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

/** Detects loaded game-server forwarding settings, never client messages or OS proxies. */
public final class ProxyEnvironment {
    private ProxyEnvironment() {}

    public static String detect(Predicate<String> modLoaded) {
        return detect(modLoaded, ProxyEnvironment.class.getClassLoader());
    }

    static String detect(Predicate<String> modLoaded, ClassLoader loader) {
        if (enabled(loader, "io.papermc.paper.configuration.GlobalConfiguration", "get", "proxies", "velocity", "enabled")) {
            return "Paper Velocity forwarding";
        }
        if (enabled(loader, "org.spigotmc.SpigotConfig", null, "bungee")) {
            return "Spigot/BungeeCord forwarding";
        }
        if (enabled(loader, "com.destroystokyo.paper.PaperConfig", null, "velocitySupport")) {
            return "legacy Paper Velocity forwarding";
        }
        // FabricProxy-Lite is a dedicated forwarding mod: presence means forwarding is required.
        if (modLoaded.test("fabricproxy-lite")) return "FabricProxy-Lite";
        return null;
    }

    private static boolean enabled(ClassLoader loader, String name, String factory, String... fields) {
        try {
            Class<?> type = Class.forName(name, false, loader);
            Object value = null;
            if (factory != null) {
                Method method = type.getMethod(factory);
                value = method.invoke(null);
                if (value == null) return false;
                type = value.getClass();
            }
            for (String namePart : fields) {
                Field field = type.getField(namePart);
                if (value == null && !Modifier.isStatic(field.getModifiers())) return false;
                value = field.get(value);
                if (value == null) return false;
                type = value.getClass();
            }
            return Boolean.TRUE.equals(value);
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // Optional platform integrations must not prevent a standalone server from starting.
            return false;
        }
    }
}
