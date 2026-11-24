package dev.tukisg.packcache;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResourcePackCacheTest {
    @TempDir Path temp;
    HttpServer server;
    ResourcePackCache cache;
    byte[] body;
    String etag = "\"first\"";
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger transferred = new AtomicInteger();

    @BeforeEach void prepare() throws Exception {
        body = zip("first", true);
        cache = new ResourcePackCache(temp.resolve("cache"), temp.resolve("downloads"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pack", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("ETag", etag);
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                transferred.incrementAndGet();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach void finish() { server.stop(0); }

    @Test void repeatedHashNeedsNoNetwork() throws Exception {
        String hash = sha1(body);
        Path first = get("/pack", hash);
        assertEquals(first, get("/pack", hash));
        assertEquals(1, requests.get());
        assertArrayEquals(body, Files.readAllBytes(first));
    }

    @Test void unknownHashRevalidatesWithoutDownloadingAgain() throws Exception {
        Path first = get("/pack", null);
        assertEquals(first, get("/pack#different-fragment", null));
        assertEquals(2, requests.get());
        assertEquals(1, transferred.get());
    }

    @Test void unchangedBodyWithoutValidatorsKeepsExistingFile() throws Exception {
        Path first = get("/pack", null);
        var stamp = java.nio.file.attribute.FileTime.fromMillis(1234567890000L);
        Files.setLastModifiedTime(first, stamp);
        etag = "\"changed-validator\"";
        assertEquals(first, get("/pack", null));
        assertEquals(stamp, Files.getLastModifiedTime(first));
        assertEquals(2, transferred.get());
    }

    @Test void changedVersionUpdatesAutomatically() throws Exception {
        Path first = get("/pack", null);
        body = zip("second", true);
        etag = "\"second\"";
        Path second = get("/pack", null);
        assertNotEquals(first, second);
        assertArrayEquals(body, Files.readAllBytes(second));
        assertEquals(second, cache.offline(url("/pack")));
    }

    @Test void corruptionRedownloadsInsteadOfReusing() throws Exception {
        String hash = sha1(body);
        Path first = get("/pack", hash);
        Files.writeString(first, "broken");
        assertEquals(first, get("/pack", hash));
        assertArrayEquals(body, Files.readAllBytes(first));
        assertEquals(2, transferred.get());
    }

    @Test void wrongHashIsRejectedAndTemporaryFileRemoved() throws Exception {
        assertThrows(java.io.IOException.class, () -> get("/pack", "0".repeat(40)));
        try (var files = Files.list(temp.resolve("cache"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".part") || p.toString().endsWith(".zip")));
        }
    }

    @Test void invalidZipIsRejected() throws Exception {
        body = "not a zip".getBytes(StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> get("/pack", null));
    }

    @Test void packWithoutMetadataIsRejected() throws Exception {
        body = zip("none", false);
        assertThrows(java.io.IOException.class, () -> get("/pack", null));
    }

    @Test void importsMinecraftDownloadByVerifiedHash() throws Exception {
        String hash = sha1(body);
        Path existing = temp.resolve("downloads/00000000-0000-0000-0000-000000000001/" + hash);
        Files.createDirectories(existing.getParent());
        Files.write(existing, body);
        Path result = get("/pack", hash);
        assertArrayEquals(body, Files.readAllBytes(result));
        assertEquals(0, requests.get());
    }

    @Test void redirectsRevalidateOnlyAtTheEffectiveUrl() throws Exception {
        server.createContext("/redirect", exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("Location", "/pack");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        assertEquals(get("/redirect", null), get("/redirect", null));
        assertEquals(1, transferred.get());
    }

    @Test void redirectLoopIsBounded() {
        AtomicInteger redirects = new AtomicInteger();
        server.createContext("/loop", exchange -> {
            redirects.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        assertThrows(java.io.IOException.class, () -> get("/loop", null));
        assertEquals(6, redirects.get());
    }

    @Test void offlineFallbackRequiresAnIntactPreviouslyDownloadedPack() throws Exception {
        assertNull(cache.offline(url("/pack")));
        Path first = get("/pack", null);
        assertEquals(first, cache.offline(url("/pack")));
        Files.writeString(first, "broken");
        assertNull(cache.offline(url("/pack")));
    }

    @Test void matchingCannotIncludeUnrelatedServers() {
        PackCacheConfig config = new PackCacheConfig();
        assertTrue(config.matchesHost("play.bloodstone.boo:25565"));
        assertTrue(config.matchesHost("BLOODSTONE.BOO"));
        assertFalse(config.matchesHost("notbloodstone.boo"));
        assertFalse(config.matchesHost("bloodstone.boo.example.org"));
        assertFalse(config.matchesHost(null));
    }

    @Test void exhaustedStartupBudgetDoesNotStartNetworkRequests() throws Exception {
        assertThrows(java.net.SocketTimeoutException.class, () -> cache.get(url("/pack"), null, Proxy.NO_PROXY, Map.of(), 0));
        assertEquals(0, requests.get());
    }

    private URL url(String path) throws Exception {
        return new URL("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private Path get(String path, String hash) throws Exception {
        return cache.get(url(path), hash, Proxy.NO_PROXY, Map.of());
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static byte[] zip(String payload, boolean metadata) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(metadata ? "pack.mcmeta" : "other.txt"));
            zip.write(("{\"pack\":{\"pack_format\":75,\"description\":\"" + payload + "\"}}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
