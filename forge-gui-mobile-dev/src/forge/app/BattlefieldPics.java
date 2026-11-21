package forge.app;

import com.badlogic.gdx.Gdx;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.util.BuildInfo;
import forge.util.ImageFetcher;
import forge.util.ImageUtil;
import forge.util.ScryfallRateLimiter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Missing card and token pictures. Cards go in Forge's Cache\\pics\\cards;
 * tokens go in Cache\\pics\\tokens. Named Scryfall fetch, plus Forge's
 * ImageFetcher when a t: key is on the card.
 */
final class BattlefieldPics {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "battlefield-pics");
        t.setDaemon(true);
        return t;
    });

    private static final Set<String> queued = ConcurrentHashMap.newKeySet();
    private static final Set<String> failed = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger pending = new AtomicInteger();
    private static final AtomicInteger got = new AtomicInteger();
    private static volatile Consumer<String> note;

    static void listen(Consumer<String> listener) {
        note = listener;
    }

    static boolean failed(String name) {
        return name != null && failed.contains(fold(name));
    }

    static boolean busy() {
        return pending.get() > 0;
    }

    static void wantDeck(Deck deck) {
        if (deck == null) return;
        List<String> names = new ArrayList<>();
        for (Map.Entry<DeckSection, CardPool> section : deck) {
            CardPool pool = section.getValue();
            if (pool == null) continue;
            for (Map.Entry<PaperCard, Integer> row : pool) {
                if (row.getKey() != null) names.add(row.getKey().getName());
            }
        }
        want(names);
    }

    static void want(Collection<String> names) {
        if (names == null) return;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) unique.add(name.trim());
        }
        for (String name : unique) want(name);
    }

    static void want(String name) {
        if (name == null || name.isBlank()) return;
        String key = fold(name);
        if (failed.contains(key) || queued.contains(key)) return;
        if (BattlefieldPrototype.hasScan(name)) return;
        queued.add(key);
        pending.incrementAndGet();
        WORK.execute(() -> fetchNamed(name, key));
    }

    static boolean tokenFailed(String name) {
        return name != null && failed.contains(tokenKey(name));
    }

    static void wantToken(String name) {
        wantToken(name, null);
    }

    /** Printed name on the tile, plus Forge's t: key when the match has one. */
    static void wantToken(String name, String imageKey) {
        if (name == null || name.isBlank()) return;
        String key = tokenKey(name);
        if (failed.contains(key) || queued.contains(key)) return;
        if (BattlefieldPrototype.hasTokenScan(name)) return;
        queued.add(key);
        pending.incrementAndGet();
        askForgeToken(name, imageKey);
        WORK.execute(() -> fetchToken(name, key));
    }

    static void arrived(Path file) {
        if (file != null) arrivedName(file.getFileName().toString());
    }

    private static void arrivedName(String name) {
        failed.remove(fold(name));
        failed.remove(tokenKey(name));
        post(() -> {
            BattlefieldPrototype.scansChanged(name);
            say();
        });
    }

    private static void fetchToken(String name, String key) {
        try {
            if (BattlefieldPrototype.hasTokenScan(name)) {
                arrivedName(name);
                return;
            }
            Path dest = tokenDestFor(name);
            if (Files.isRegularFile(dest) && Files.size(dest) > 20_000L) {
                arrivedName(name);
                return;
            }
            String imageUrl = scryfallTokenImage(name);
            if (imageUrl == null) imageUrl = namedImageUrl(name);
            if (imageUrl == null) {
                failed.add(key);
                return;
            }
            if (!saveUrl(imageUrl, dest)) {
                failed.add(key);
                return;
            }
            got.incrementAndGet();
            arrivedName(name);
        } catch (Exception failedDownload) {
            System.err.println("Token picture: " + name + " — " + failedDownload.getMessage());
            failed.add(key);
        } finally {
            pending.decrementAndGet();
            post(BattlefieldPics::say);
        }
    }

    /**
     * Forge's t: key (script|set|collector) writes the official token file.
     * The board still wants the printed-name file from {@link #fetchToken}.
     */
    private static void askForgeToken(String name, String imageKey) {
        if (imageKey == null || !imageKey.startsWith("t:") || !imageKey.contains("|")) return;
        try {
            if (GuiBase.getInterface() == null) return;
            ImageFetcher fetcher = GuiBase.getInterface().getImageFetcher();
            if (fetcher == null) return;
            fetcher.fetchImage(imageKey, () -> arrivedName(name));
        } catch (RuntimeException ignored) {
            /* Scryfall-by-name still runs. */
        }
    }

    private static String scryfallTokenImage(String name) throws Exception {
        String printed = tokenPrinted(name);
        String q = "type:token name:\"" + printed.replace("\"", "") + "\"";
        String url = "https://api.scryfall.com/cards/search?q="
                + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&unique=art&order=released&dir=desc";
        String body = readText(url);
        if (body == null || body.isBlank()) return null;
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException badJson) {
            return null;
        }
        if (!root.has("data") || !root.get("data").isJsonArray()) return null;
        JsonArray data = root.getAsJsonArray("data");
        String exact = null;
        String any = null;
        String want = fold(printed);
        for (JsonElement el : data) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject card = el.getAsJsonObject();
            String cardName = card.has("name") && card.get("name").isJsonPrimitive()
                    ? card.get("name").getAsString() : "";
            String img = imageUri(card);
            if (img == null) continue;
            if (any == null) any = img;
            if (fold(tokenPrinted(cardName)).equals(want)) {
                exact = img;
                break;
            }
        }
        return exact != null ? exact : any;
    }

    private static String namedImageUrl(String name) {
        return "https://api.scryfall.com/cards/named?exact="
                + URLEncoder.encode(tokenPrinted(name), StandardCharsets.UTF_8)
                + "&format=image&version=normal";
    }

    private static String imageUri(JsonObject card) {
        String url = uriFrom(card);
        if (url != null) return url;
        if (!card.has("card_faces") || !card.get("card_faces").isJsonArray()) return null;
        JsonArray faces = card.getAsJsonArray("card_faces");
        if (faces.isEmpty() || !faces.get(0).isJsonObject()) return null;
        return uriFrom(faces.get(0).getAsJsonObject());
    }

    private static String uriFrom(JsonObject node) {
        if (node == null || !node.has("image_uris") || !node.get("image_uris").isJsonObject()) return null;
        JsonObject uris = node.getAsJsonObject("image_uris");
        if (uris.has("normal") && uris.get("normal").isJsonPrimitive()) {
            return uris.get("normal").getAsString();
        }
        if (uris.has("large") && uris.get("large").isJsonPrimitive()) {
            return uris.get("large").getAsString();
        }
        return null;
    }

    private static String readText(String url) throws Exception {
        ScryfallRateLimiter.acquire(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            ScryfallRateLimiter.noteIfRateLimited(code, url, conn.getHeaderField("Retry-After"));
            conn.disconnect();
            return null;
        }
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static boolean saveUrl(String url, Path dest) throws Exception {
        ScryfallRateLimiter.acquire(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            ScryfallRateLimiter.noteIfRateLimited(code, url, conn.getHeaderField("Retry-After"));
            conn.disconnect();
            return false;
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".tmp");
        try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        } finally {
            conn.disconnect();
        }
        if (Files.size(tmp) < 1000) {
            Files.deleteIfExists(tmp);
            return false;
        }
        Files.deleteIfExists(dest);
        Files.move(tmp, dest);
        return true;
    }

    private static Path tokenDestFor(String name) {
        String stem = ImageUtil.toMWSFilename(tokenPrinted(name));
        if (stem == null || stem.isBlank()) stem = tokenPrinted(name).replace('/', ' ');
        return tokensDir().resolve(stem + ".fullborder.jpg");
    }

    static Path tokensDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            Path dir = Path.of(local, "Forge", "Cache", "pics", "tokens");
            try {
                Files.createDirectories(dir);
                return dir;
            } catch (Exception ignored) {
                // Fall through.
            }
        }
        Path fallback = Path.of("pics", "tokens").toAbsolutePath().normalize();
        try {
            Files.createDirectories(fallback);
        } catch (Exception ignored) {
            // Caller will fail the write.
        }
        return fallback;
    }

    private static String tokenPrinted(String name) {
        String text = name.trim();
        String folded = text.toLowerCase(Locale.ROOT);
        if (folded.endsWith(" token")) text = text.substring(0, text.length() - 6).trim();
        return text;
    }

    private static String tokenKey(String name) {
        return "t:" + fold(tokenPrinted(name));
    }

    private static void fetchNamed(String name, String key) {
        try {
            if (BattlefieldPrototype.hasScan(name)) {
                arrivedName(name);
                return;
            }
            Path dest = destFor(name);
            if (Files.isRegularFile(dest) && Files.size(dest) > 20_000L) {
                arrivedName(name);
                return;
            }
            String url = "https://api.scryfall.com/cards/named?exact="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&format=image&version=normal";
            ScryfallRateLimiter.acquire(url);
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("User-Agent", BuildInfo.getUserAgent());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                ScryfallRateLimiter.noteIfRateLimited(code, url, conn.getHeaderField("Retry-After"));
                conn.disconnect();
                failed.add(key);
                return;
            }
            Files.createDirectories(dest.getParent());
            Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".tmp");
            try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            } finally {
                conn.disconnect();
            }
            if (Files.size(tmp) < 1000) {
                Files.deleteIfExists(tmp);
                failed.add(key);
                return;
            }
            Files.deleteIfExists(dest);
            Files.move(tmp, dest);
            got.incrementAndGet();
            arrivedName(name);
        } catch (Exception failedDownload) {
            System.err.println("Card picture: " + name + " — " + failedDownload.getMessage());
            failed.add(key);
        } finally {
            pending.decrementAndGet();
            post(BattlefieldPics::say);
        }
    }

    private static Path destFor(String name) {
        String stem = ImageUtil.toMWSFilename(name);
        if (stem == null || stem.isBlank()) stem = name.replace('/', ' ');
        return picsDir().resolve(stem + ".fullborder.jpg");
    }

    static Path picsDir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            Path dir = Path.of(local, "Forge", "Cache", "pics", "cards");
            try {
                Files.createDirectories(dir);
                return dir;
            } catch (Exception ignored) {
                // Fall through.
            }
        }
        Path fallback = Path.of("pics", "cards").toAbsolutePath().normalize();
        try {
            Files.createDirectories(fallback);
        } catch (Exception ignored) {
            // Caller will fail the write.
        }
        return fallback;
    }

    private static void say() {
        Consumer<String> listener = note;
        if (listener == null) return;
        int left = pending.get();
        int have = got.get();
        if (left <= 0) {
            listener.accept(have > 0
                    ? "Pictures ready (" + have + "). Start, Host, or Join."
                    : null);
            return;
        }
        listener.accept("Getting pictures… " + have + " saved, " + left + " still coming.");
    }

    private static void post(Runnable work) {
        if (Gdx.app != null) Gdx.app.postRunnable(work);
        else work.run();
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
    }
}
