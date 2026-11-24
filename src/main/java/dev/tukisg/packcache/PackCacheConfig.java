package dev.tukisg.packcache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class PackCacheConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public boolean enabled = true;
    public boolean preloadAtStartup = true;
    public List<String> preloadPacks = List.of(
            "http://resources2.bloodstone.boo:8880/BloodStone.zip",
            "http://resources2.bloodstone.boo:8880/BloodStoneFactions.zip");
    public List<String> startupPacks = List.of(
            "http://resources2.bloodstone.boo:8880/BloodStone.zip",
            "http://resources2.bloodstone.boo:8880/BloodStoneFactions.zip");
    public String cacheDirectory = Files.isDirectory(Path.of("E:/Codex"))
            ? "E:/Codex/cache/bloodstone-resourcepacks" : "cache/bloodstone-resourcepacks";
    public List<String> serverDomains = List.of("bloodstone.boo");

    public static PackCacheConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            PackCacheConfig config = new PackCacheConfig();
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJson(config));
            return config;
        }
        PackCacheConfig config = JSON.fromJson(Files.readString(file), PackCacheConfig.class);
        if (config == null || config.cacheDirectory == null || config.cacheDirectory.isBlank()
                || config.preloadPacks == null || config.preloadPacks.stream().anyMatch(java.util.Objects::isNull)
                || config.startupPacks == null || config.startupPacks.stream().anyMatch(java.util.Objects::isNull)
                || config.serverDomains == null || config.serverDomains.isEmpty()
                || config.serverDomains.stream().anyMatch(domain -> domain == null || domain.isBlank())) {
            throw new IOException("Invalid pack cache configuration");
        }
        if (config.preloadPacks.equals(List.of("http://resources2.bloodstone.boo:8880/BloodStone.zip"))
                && config.startupPacks.contains("http://resources2.bloodstone.boo:8880/BloodStoneFactions.zip")) {
            config.preloadPacks = List.of("http://resources2.bloodstone.boo:8880/BloodStone.zip",
                    "http://resources2.bloodstone.boo:8880/BloodStoneFactions.zip");
        }
        return config;
    }

    public boolean matchesHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        for (String domain : serverDomains) {
            String allowed = domain.toLowerCase(Locale.ROOT);
            if (normalized.equals(allowed) || normalized.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }
}
