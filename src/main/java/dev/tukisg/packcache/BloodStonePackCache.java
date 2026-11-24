package dev.tukisg.packcache;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.server.packs.DownloadQueue;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BloodStonePackCache implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("BloodStonePackCache");
    private static final Executor IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "BloodStone pack cache");
        thread.setDaemon(true);
        return thread;
    });
    static volatile PackCacheConfig config;
    private static ResourcePackCache cache;
    static StartupPacks startup;

    @Override
    public void onInitializeClient() {
        try {
            config = PackCacheConfig.load(FabricLoader.getInstance().getConfigDir().resolve("bloodstone-pack-cache.json"));
            startup = new StartupPacks(config, FabricLoader.getInstance().getGameDir());
        } catch (Exception error) {
            LOGGER.warn("Cannot read pack cache configuration; using Minecraft downloader", error);
        }
    }

    public static PackDownloader wrap(PackDownloader original, Executor mainThread, User user, Proxy proxy) {
        return (requests, callback) -> {
            PackCacheConfig settings = config;
            if (settings == null || !settings.enabled) {
                original.download(requests, callback);
                return;
            }
            ServerData server = Minecraft.getInstance().getCurrentServer();
            boolean bloodstone = server != null && settings.matchesHost(server.ip)
                    || startup != null && startup.isBloodStoneConnection();
            Map<UUID, DownloadQueue.DownloadRequest> snapshot = new LinkedHashMap<>(requests);
            Map<String, String> headers = headers(user);
            CompletableFuture.supplyAsync(() -> prepare(snapshot, settings, bloodstone, proxy, headers), IO)
                    .whenComplete((prepared, error) -> mainThread.execute(() -> {
                        if (error != null) {
                            LOGGER.warn("Pack cache unavailable; using Minecraft downloader", error);
                            original.download(snapshot, callback);
                        } else if (prepared.remaining().isEmpty()) {
                            callback.accept(new DownloadQueue.BatchResult(prepared.cached(), java.util.Set.of()));
                        } else {
                            original.download(prepared.remaining(), result -> {
                                Map<UUID, Path> downloaded = new HashMap<>(prepared.cached());
                                downloaded.putAll(result.downloaded());
                                callback.accept(new DownloadQueue.BatchResult(downloaded, result.failed()));
                            });
                        }
                    }));
        };
    }

    private static Prepared prepare(Map<UUID, DownloadQueue.DownloadRequest> requests, PackCacheConfig settings,
                                    boolean bloodstone, Proxy proxy, Map<String, String> headers) {
        Map<UUID, Path> cached = new LinkedHashMap<>();
        Map<UUID, DownloadQueue.DownloadRequest> remaining = new LinkedHashMap<>();
        for (var entry : requests.entrySet()) {
            var request = entry.getValue();
            if (!bloodstone && !settings.matchesHost(request.url().getHost())) {
                remaining.put(entry.getKey(), request);
                continue;
            }
            try {
                String sha1 = request.hash() == null ? null : request.hash().toString();
                Path path = cache().get(request.url(), sha1, proxy, headers);
                cached.put(entry.getKey(), path);
                if (startup != null) {
                    startup.remember(entry.getKey(), request.url());
                    startup.recordDownload(path, request.url());
                }
            } catch (Exception error) {
                LOGGER.warn("Could not cache a resource pack; using Minecraft downloader: {}", error.toString());
                remaining.put(entry.getKey(), request);
            }
        }
        return new Prepared(cached, remaining);
    }

    static synchronized ResourcePackCache cache() throws java.io.IOException {
        if (cache == null) {
            Path game = FabricLoader.getInstance().getGameDir();
            Path configured = Path.of(config.cacheDirectory);
            cache = new ResourcePackCache(configured.isAbsolute() ? configured : game.resolve(configured), game.resolve("downloads"));
        }
        return cache;
    }

    public static StartupPacks startup() {
        return startup;
    }

    static Map<String, String> headers(User user) {
        var version = SharedConstants.getCurrentVersion();
        return Map.of(
                "X-Minecraft-Username", user.getName(),
                "X-Minecraft-UUID", user.getProfileId().toString().replace("-", ""),
                "X-Minecraft-Version", version.name(),
                "X-Minecraft-Version-ID", version.id(),
                "X-Minecraft-Pack-Format", String.valueOf(version.packVersion(PackType.CLIENT_RESOURCES)),
                "User-Agent", "Minecraft Java/" + version.name());
    }

    private record Prepared(Map<UUID, Path> cached, Map<UUID, DownloadQueue.DownloadRequest> remaining) {}
}
