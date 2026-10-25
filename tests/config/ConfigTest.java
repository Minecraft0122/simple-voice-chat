package de.maxhenkel.voicechat.voice.transport;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.config.ServerConfig;
import de.maxhenkel.voicechat.config.ProxyConfig;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

public class ConfigTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0], "voicechat-server.properties");
        // Deliberately old order, custom values and missing new options.
        Files.writeString(file, "max_voice_distance=96.0\nproxy_mode=false\nvoice_host=voice.test:25580\nport=25580\ntransport_mode=dual\n");
        Function<ConfigBuilder, ServerConfig> factory = CONFIG_FACTORY;
        ServerConfig c = ConfigBuilder.builder(factory).path(file).saveSyncAfterBuild(true).build();
        check(c.voiceChatPort.get() == 25580 && c.voiceChatDistance.get() == 96D, "preserve numeric settings");
        check(c.transportMode.get().equals("dual") && c.voiceHost.get().equals("voice.test:25580"), "preserve endpoint and transport");
        check(c.proxyAutoDetect.get() && !c.proxyMode.get(), "automatic detection defaults on, manual mode off");
        String text = Files.readString(file);
        List<String> keys = text.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split("=",2)[0]).toList();
        check(keys.subList(0,6).equals(List.of("transport_mode","port","bind_address","voice_host","proxy_mode","proxy_auto_detect")), "network then proxy key order: " + keys);
        check(text.contains("代理") && text.contains("dual") && text.contains("重启"), "persist bilingual comments");
        check(keys.size() == new HashSet<>(keys).size(), "no duplicate keys");
        for (var field : ServerConfig.class.getFields()) {
            if (field.get(c) instanceof de.maxhenkel.configbuilder.entry.ConfigEntry<?> entry) {
                check(entry.getComments().length > 0, "missing comments for " + entry.getKey());
            }
        }
        c.detectProxy(id -> false);
        check(!c.isProxyMode(), "standalone remains direct");
        org.spigotmc.SpigotConfig.bungee = true;
        c.detectProxy(id -> false);
        check(c.isProxyMode(), "Spigot forwarding enables proxy mode");
        check(!c.proxyMode.get() && Files.readString(file).contains("proxy_mode=false"), "do not latch detected state into saved config");
        c.proxyAutoDetect.set(false);
        c.detectProxy(id -> false);
        check(!c.isProxyMode(), "opt out supports direct voice behind game proxy");
        c.proxyMode.set(true);
        c.detectProxy(id -> false);
        check(c.isProxyMode(), "manual true overrides detection opt out");
        c.proxyMode.set(false); c.proxyAutoDetect.set(true);
        org.spigotmc.SpigotConfig.bungee = false;
        var paper = io.papermc.paper.configuration.GlobalConfiguration.get();
        paper.proxies.velocity.enabled = true;
        c.detectProxy(id -> false);
        check(c.isProxyMode(), "Paper Velocity forwarding enables proxy mode");
        paper.proxies.velocity.enabled = false;
        com.destroystokyo.paper.PaperConfig.velocitySupport = true;
        c.detectProxy(id -> false);
        check(c.isProxyMode(), "legacy Paper forwarding enables proxy mode");
        com.destroystokyo.paper.PaperConfig.velocitySupport = false;
        c.detectProxy(id -> id.equals("fabricproxy-lite"));
        check(c.isProxyMode(), "Fabric forwarding mod enables proxy mode");
        c.detectProxy(id -> false);
        check(!c.isProxyMode(), "restart resolution does not retain previous detection");
        check(ProxyEnvironment.detect(id -> false, new ClassLoader(null) {}) == null, "missing optional APIs are safe");
        check(ProxyEnvironment.detect(id -> id.equals("fabricproxy-lite"), new ClassLoader(null) {}).equals("FabricProxy-Lite"), "Fabric works without Bukkit APIs");
        var proxies = paper.proxies; paper.proxies = null;
        check(ProxyEnvironment.detect(id -> false) == null, "uninitialized Paper config is safe");
        paper.proxies = proxies;
        System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("socksProxyHost", "127.0.0.1");
        check(ProxyEnvironment.detect(id -> false) == null, "OS and HTTP/SOCKS proxy settings do not enable game proxy mode");
        Path proxyFile = Path.of(args[0], "voicechat-proxy.properties");
        ConfigBuilder.builder(ProxyConfig::new).path(proxyFile).saveSyncAfterBuild(true).build();
        String proxyText = Files.readString(proxyFile);
        check(proxyText.contains("代理") && proxyText.contains("TCP") && proxyText.contains("UDP"), "proxy comments are saved");
        ServerConfig reloaded = ConfigBuilder.builder(factory).path(file).saveSyncAfterBuild(true).build();
        check(reloaded.voiceChatPort.get() == 25580 && reloaded.transportMode.get().equals("dual"), "reloading retains custom values");
        System.out.println("ConfigTest: " + checks + " checks passed");
    }
}
