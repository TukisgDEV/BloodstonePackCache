package dev.tukisg.packcache;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;

public final class ResourcePackCache {
    private static final Gson JSON = new Gson();
    private static final long MAX_PACK_BYTES = 250L * 1024 * 1024;
    private final Path directory;
    private final Path vanillaDownloads;
    private final Map<Path, FileStamp> verified = new HashMap<>();

    public ResourcePackCache(Path directory, Path vanillaDownloads) throws IOException {
        this.directory = directory;
        this.vanillaDownloads = vanillaDownloads;
        Files.createDirectories(directory);
    }

    public Path get(URL url, String expectedHash, Proxy proxy, Map<String, String> headers) throws IOException {
        return get(url, expectedHash, proxy, headers, 60000);
    }

    public Path get(URL url, String expectedHash, Proxy proxy, Map<String, String> headers, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (expectedHash != null && !expectedHash.matches("[a-fA-F0-9]{40}")) {
            throw new IOException("Invalid SHA-1");
        }
        if (expectedHash != null) {
            expectedHash = expectedHash.toLowerCase(java.util.Locale.ROOT);
            Path cached = contentPath(expectedHash);
            if (isValid(cached, expectedHash)) {
                touch(cached);
                return cached;
            }
            Path imported = importVanilla(expectedHash);
            if (imported != null) {
                return imported;
            }
        }
        url = withoutFragment(url);
        String key = hash(url.toExternalForm().getBytes(StandardCharsets.UTF_8), "SHA-256");
        Path metadataFile = directory.resolve(key + ".json");
        Entry previous = readEntry(metadataFile);
        boolean reusable = previous != null && (expectedHash == null || expectedHash.equals(previous.sha1()))
                && isValid(contentPath(previous.sha1()), previous.sha1());
        HttpURLConnection connection = connect(url, proxy, headers, reusable ? previous : null, deadline);
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED && reusable) {
                Path cached = contentPath(previous.sha1());
                touch(cached);
                return cached;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Resource pack HTTP status " + status);
            }
            if (connection.getContentLengthLong() > MAX_PACK_BYTES) {
                throw new IOException("Resource pack exceeds 250 MiB");
            }
            Path temporary = Files.createTempFile(directory, "download-", ".part");
            try {
                String sha1 = download(connection, temporary, deadline);
                if (expectedHash != null && !expectedHash.equals(sha1)) {
                    throw new IOException("Resource pack SHA-1 mismatch");
                }
                validateZip(temporary);
                Path result = contentPath(sha1);
                if (!isValid(result, sha1)) replace(temporary, result);
                verified.put(result, stamp(result));
                Entry current = new Entry(sha1, connection.getHeaderField("ETag"),
                        connection.getHeaderField("Last-Modified"), connection.getURL().toExternalForm());
                writeEntry(metadataFile, current);
                return result;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(URL initial, Proxy proxy, Map<String, String> headers, Entry previous, long deadline)
            throws IOException {
        URL url = initial;
        for (int redirect = 0; redirect <= 5; redirect++) {
            if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) {
                throw new IOException("Unsupported resource pack protocol");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
            connection.setConnectTimeout(Math.min(10000, remaining(deadline)));
            connection.setReadTimeout(Math.min(30000, remaining(deadline)));
            connection.setInstanceFollowRedirects(false);
            headers.forEach(connection::setRequestProperty);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (previous != null && url.toExternalForm().equals(previous.effectiveUrl())) {
                if (previous.etag() != null) {
                    connection.setRequestProperty("If-None-Match", previous.etag());
                } else if (previous.lastModified() != null) {
                    connection.setRequestProperty("If-Modified-Since", previous.lastModified());
                }
            }
            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException error) {
                connection.disconnect();
                throw error;
            }
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("Resource pack redirect has no location");
            }
            url = new URL(url, location);
        }
        throw new IOException("Too many resource pack redirects");
    }

    private String download(HttpURLConnection connection, Path destination, long deadline) throws IOException {
        MessageDigest digest = digest("SHA-1");
        long total = 0;
        connection.setReadTimeout(Math.min(30000, remaining(deadline)));
        try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                remaining(deadline);
                total += count;
                if (total > MAX_PACK_BYTES) {
                    throw new IOException("Resource pack exceeds 250 MiB");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        long expectedLength = connection.getContentLengthLong();
        if (expectedLength >= 0 && total != expectedLength) {
            throw new IOException("Incomplete resource pack download");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Path importVanilla(String sha1) throws IOException {
        if (!Files.isDirectory(vanillaDownloads)) {
            return null;
        }
        try (var folders = Files.newDirectoryStream(vanillaDownloads)) {
            for (Path folder : folders) {
                Path source = folder.resolve(sha1);
                if (!Files.isRegularFile(source) || Files.size(source) > MAX_PACK_BYTES) {
                    continue;
                }
                Path temporary = Files.createTempFile(directory, "import-", ".part");
                try {
                    Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                    if (!sha1.equals(hashFile(temporary))) {
                        continue;
                    }
                    validateZip(temporary);
                    Path result = contentPath(sha1);
                    replace(temporary, result);
                    verified.put(result, stamp(result));
                    return result;
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
        return null;
    }

    private boolean isValid(Path file, String sha1) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_PACK_BYTES) {
            return false;
        }
        FileStamp current = stamp(file);
        if (current.equals(verified.get(file))) {
            return true;
        }
        if (!sha1.equals(hashFile(file))) {
            return false;
        }
        try {
            validateZip(file);
        } catch (IOException error) {
            return false;
        }
        verified.put(file, current);
        return true;
    }

    private Entry readEntry(Path file) {
        try {
            Entry entry = JSON.fromJson(Files.readString(file), Entry.class);
            return entry != null && entry.sha1() != null && entry.sha1().matches("[a-f0-9]{40}") ? entry : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private void writeEntry(Path file, Entry entry) throws IOException {
        Path temporary = Files.createTempFile(directory, "metadata-", ".part");
        try {
            Files.writeString(temporary, JSON.toJson(entry));
            replace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path contentPath(String sha1) {
        return directory.resolve(sha1 + ".zip");
    }

    public Path offline(URL url) throws IOException {
        String key = hash(withoutFragment(url).toExternalForm().getBytes(StandardCharsets.UTF_8), "SHA-256");
        Entry entry = readEntry(directory.resolve(key + ".json"));
        return entry != null && isValid(contentPath(entry.sha1()), entry.sha1()) ? contentPath(entry.sha1()) : null;
    }

    public static URL withoutFragment(URL url) throws IOException {
        String value = url.toExternalForm();
        int fragment = value.indexOf('#');
        return fragment < 0 ? url : new URL(value.substring(0, fragment));
    }

    private void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
            verified.put(file, stamp(file));
        } catch (IOException ignored) {
        }
    }

    private static FileStamp stamp(Path file) throws IOException {
        return new FileStamp(Files.size(file), Files.getLastModifiedTime(file));
    }

    private static int remaining(long deadline) throws IOException {
        long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (millis <= 0) throw new java.net.SocketTimeoutException("Resource pack request timed out");
        return (int) Math.min(Integer.MAX_VALUE, millis);
    }

    private static void validateZip(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("pack.mcmeta") == null) {
                throw new IOException("Resource pack has no pack.mcmeta");
            }
        }
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = digest("SHA-1");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(byte[] bytes, String algorithm) {
        return HexFormat.of().formatHex(digest(algorithm).digest(bytes));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Entry(String sha1, String etag, String lastModified, String effectiveUrl) {}
    private record FileStamp(long bytes, FileTime modified) {}
}
