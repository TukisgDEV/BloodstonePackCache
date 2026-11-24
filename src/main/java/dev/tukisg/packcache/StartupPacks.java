package dev.tukisg.packcache;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.tukisg.packcache.mixin.PackAccessor;
import dev.tukisg.packcache.mixin.FileResourcesSupplierAccessor;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources.FileResourcesSupplier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StartupPacks {
    private static final Logger LOGGER = LoggerFactory.getLogger("BloodStonePackCache");
    private static final Gson JSON = new Gson();
    private final PackCacheConfig config;
    private final Path game;
    private final Path knownFile;
    private final Map<String, String> known = new LinkedHashMap<>();
    private final Map<Path, Pack> preloaded = new LinkedHashMap<>();
    private final Map<Path, String> preloadAddresses = new LinkedHashMap<>();
    private final Map<Path, String> downloadedAddresses = new java.util.concurrent.ConcurrentHashMap<>();
    private final AppliedPackState appliedPacks = new AppliedPackState();
    private boolean initialized;
    private boolean disabledAfterFailure;
    private boolean active = true;
    private String connectedHost;

    public StartupPacks(PackCacheConfig config, Path game) {
        this.config = config;
        this.game = game;
        Path directory = Path.of(config.cacheDirectory);
        this.knownFile = (directory.isAbsolute() ? directory : game.resolve(directory)).resolve("known-packs.json");
    }

    public RepositorySource source(RepositorySource original) {
        return consumer -> {
            initialize();
            List<Pack> requested = new ArrayList<>();
            original.loadPacks(requested::add);
            if (!active || disabledAfterFailure) {
                requested.forEach(consumer);
                return;
            }
            for (Pack pack : requested) {
                Path path = pathOf(pack);
                String address = path == null ? null : downloadedAddresses.get(path);
                if (address == null || !config.preloadPacks.contains(address)) continue;
                Path previous = preloadAddresses.entrySet().stream()
                        .filter(entry -> address.equals(entry.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (previous != null && !previous.equals(path)) {
                    Pack updated = createPreloadedPack(address, path, preloaded.get(previous).getId().split("/")[1]);
                    if (updated != null) {
                        preloaded.remove(previous);
                        preloadAddresses.remove(previous);
                        preloaded.put(path, updated);
                        preloadAddresses.put(path, address);
                    }
                }
            }
            PackOrdering.arrange(preloaded, requested, StartupPacks::pathOf).forEach(consumer);
        };
    }

    private void initialize() {
        if (initialized) return;
        initialized = true;
        if (!config.enabled || !config.preloadAtStartup) return;
        loadKnown();
        Map<String, String> urls = new LinkedHashMap<>();
        config.preloadPacks.forEach(url -> urls.put(url, url));
        config.startupPacks.forEach(url -> urls.put(url, url));
        var configuredNames = config.startupPacks.stream().map(StartupPacks::fileName).toList();
        new ArrayList<>(known.values()).reversed().stream().filter(url -> !configuredNames.contains(fileName(url)))
                .forEach(url -> urls.put(url, url));
        Minecraft client = Minecraft.getInstance();
        int index = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for (String address : urls.keySet()) {
            try {
                URL url = ResourcePackCache.withoutFragment(new URL(address));
                Path path;
                try {
                    long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remaining <= 0) throw new IOException("Startup download budget reached");
                    path = BloodStonePackCache.cache().get(url, null, client.getProxy(), BloodStonePackCache.headers(client.getUser()), remaining);
                } catch (IOException error) {
                    path = BloodStonePackCache.cache().offline(url);
                    if (path == null) throw error;
                    LOGGER.info("Using a saved BloodStone pack while the resource host is unavailable");
                }
                if (!config.preloadPacks.contains(address)) continue;
                Path normalized = path.toAbsolutePath().normalize();
                Pack pack = createPreloadedPack(address, normalized, String.format("%03d", index++));
                if (pack != null) {
                    preloaded.put(normalized, pack);
                    preloadAddresses.put(normalized, address);
                }
            } catch (Exception error) {
                LOGGER.warn("Could not preload a BloodStone pack; it will load on joining: {}", error.toString());
            }
        }
        LOGGER.info("Prepared {} BloodStone packs for the initial resource load", preloaded.size());
    }

    private static Pack createPreloadedPack(String address, Path path, String index) {
        try {
            URL url = new URL(address);
            String hash = path.getFileName().toString().replace(".zip", "");
            return Pack.readMetaAndCreate(
                    new PackLocationInfo("bloodstone-preload/" + index + "/" + hash,
                            Component.literal("BloodStone: " + Path.of(url.getPath()).getFileName()), PackSource.SERVER, Optional.empty()),
                    new FileResourcesSupplier(path), PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.TOP, false));
        } catch (Exception error) {
            LOGGER.warn("Could not prepare a BloodStone pack: {}", error.toString());
            return null;
        }
    }

    public void recordDownload(Path path, URL url) throws IOException {
        downloadedAddresses.put(path.toAbsolutePath().normalize(), ResourcePackCache.withoutFragment(url).toExternalForm());
    }

    private void loadKnown() {
        try {
            Path log = game.resolve("downloads/log.json");
            if (Files.isRegularFile(log)) {
                try (var lines = Files.lines(log)) {
                    lines.forEach(line -> {
                        try {
                            JsonObject data = JSON.fromJson(line, JsonObject.class);
                            URL url = ResourcePackCache.withoutFragment(new URL(data.get("url").getAsString()));
                            if (config.matchesHost(url.getHost()) && data.has("file")) {
                                known.put(data.get("id").getAsString(), url.toExternalForm());
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            if (Files.isRegularFile(knownFile)) {
                Map<String, String> saved = JSON.fromJson(Files.readString(knownFile), new TypeToken<LinkedHashMap<String, String>>() {}.getType());
                if (saved != null) saved.forEach((id, url) -> { if (id != null && url != null) known.put(id, url); });
            }
        } catch (Exception error) {
            LOGGER.warn("Could not read previously used BloodStone packs: {}", error.toString());
        }
    }

    public synchronized void remember(UUID id, URL url) {
        try {
            String address = ResourcePackCache.withoutFragment(url).toExternalForm();
            if (address.equals(known.put(id.toString(), address))) return;
            Files.createDirectories(knownFile.getParent());
            Path temporary = Files.createTempFile(knownFile.getParent(), "known-", ".part");
            try {
                Files.writeString(temporary, JSON.toJson(known));
                Files.move(temporary, knownFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception error) {
            LOGGER.warn("Could not save the BloodStone pack list: {}", error.toString());
        }
    }

    public boolean resourcesAlreadyApplied(Minecraft client) {
        if (!active || !initialized || preloaded.isEmpty() || disabledAfterFailure || !client.isGameLoadFinished()
                || client.getOverlay() != null) return false;
        client.getResourcePackRepository().reload();
        List<String> expected = client.getResourcePackRepository().getSelectedPacks().stream().map(Pack::getId).toList();
        boolean unchanged = appliedPacks.matches(expected);
        if (unchanged) LOGGER.info("Reusing already applied BloodStone resources");
        return unchanged;
    }

    public void beginResourceLoad(List<String> packs) {
        appliedPacks.begin(packs);
    }

    public void completeResourceLoad() {
        appliedPacks.complete();
    }

    public void recovery() {
        disabledAfterFailure = true;
        appliedPacks.invalidate();
    }

    public boolean connectTo(String host) {
        connectedHost = host;
        return setActive(config.matchesHost(host));
    }

    public boolean isBloodStoneConnection() {
        return config.matchesHost(connectedHost);
    }

    public void menuTick(Minecraft client) {
        if (client.level == null && (client.screen instanceof net.minecraft.client.gui.screens.TitleScreen
                || client.screen instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen)) {
            connectedHost = null;
            if (setActive(true)) client.reloadResourcePacks();
        }
    }

    private boolean setActive(boolean enabled) {
        if (active == enabled) return false;
        active = enabled;
        return initialized && !preloaded.isEmpty() && !disabledAfterFailure;
    }

    private static String fileName(String address) {
        try {
            return Path.of(new URL(address).getPath()).getFileName().toString();
        } catch (Exception error) {
            return address;
        }
    }

    private static Path pathOf(Pack pack) {
        var supplier = ((PackAccessor) pack).bloodstone$getResources();
        return supplier instanceof FileResourcesSupplierAccessor file ? file.bloodstone$getContent().toPath().toAbsolutePath().normalize() : null;
    }
}
