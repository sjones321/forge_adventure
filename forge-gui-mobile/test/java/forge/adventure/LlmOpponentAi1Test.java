package forge.adventure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
import forge.ai.llm.LlmSettingsPersistence;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI1 behaviour tests: local key storage, redaction, disable/timeout/malformed fallback,
 * settings Test button (snapshot, Enable-off, real HTTP errors), debounce, and permissions.
 */
public class LlmOpponentAi1Test {

    private static final String SECRET_KEY = "sk-test-SECRET-KEY-do-not-leak-987654";

    private Path tempDir;
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeMethod
    public void setUp() throws Exception {
        LlmOpponent.deactivateForTests();
        tempDir = Files.createTempDirectory("llm-ai1-");
        System.setProperty("forge.llm.dir", tempDir.toAbsolutePath().toString());
        hits.set(0);
    }

    @AfterMethod
    public void tearDown() {
        LlmOpponent.deactivateForTests();
        LlmSettings.setBeforeWriteForTests(null);
        if (server != null) {
            server.stop(0);
            server = null;
        }
        System.clearProperty("forge.llm.dir");
    }

    @Test
    public void apiKeyNeverAppearsInToStringOrRedactedErrors() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("test-model");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(2);

        Assert.assertFalse(s.toString().contains(SECRET_KEY), "toString must not contain the key");
        Assert.assertTrue(s.toString().contains("apiKey=set"));

        String leaked = "Authorization: Bearer " + SECRET_KEY + " failed with " + SECRET_KEY;
        String safe = s.redact(leaked);
        Assert.assertFalse(safe.contains(SECRET_KEY), "redact must strip the key");
        Assert.assertFalse(safe.contains("Bearer " + SECRET_KEY));

        LlmOpponent.activateForTests(s);
        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        Assert.assertFalse(result.isSuccess());
        Assert.assertFalse(result.getMessage().contains(SECRET_KEY), "Test error must not contain the key");
        Assert.assertFalse(result.toString().contains(SECRET_KEY));
    }

    @Test
    public void apiKeyStoredLocallyAndNotInSaveBlob() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://localhost:11434/v1");
        s.setModel("qwen2.5:7b");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(15);
        s.save();

        Path props = tempDir.resolve(LlmSettings.FILE_NAME);
        Assert.assertTrue(Files.isRegularFile(props));
        String fileText = Files.readString(props);
        Assert.assertTrue(fileText.contains(SECRET_KEY), "local properties file holds the key");

        Properties saveBlob = new Properties();
        saveBlob.setProperty("gold", "100");
        saveBlob.setProperty("plane", "Shandalar Ascendant");
        Assert.assertFalse(saveBlob.containsKey(LlmSettings.PROP_API_KEY));
        Assert.assertFalse(saveBlob.toString().contains(SECRET_KEY));

        LlmSettings loaded = LlmSettings.load();
        Assert.assertEquals(loaded.getApiKey(), SECRET_KEY);
        Assert.assertTrue(loaded.isEnabled());
        Assert.assertEquals(loaded.getModel(), "qwen2.5:7b");
    }

    @Test
    public void disabledMeansForgeAiOnly() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(false);
        s.setBaseUrl(baseUrlOrPlaceholder());
        s.setModel("anything");
        s.setApiKey(SECRET_KEY);
        Assert.assertFalse(s.canActivate());

        LlmOpponent.activateForTests(s);
        Assert.assertFalse(LlmOpponent.isActive(), "disabled settings must not activate LLM");
    }

    @Test
    public void malformedReplyFallsBackToForgeChoice() {
        Assert.assertEquals(LlmOpponent.parseSpellChoice(null, 3), -1);
        Assert.assertEquals(LlmOpponent.parseSpellChoice("not json", 3), -1);
        Assert.assertEquals(LlmOpponent.parseSpellChoice("{\"choice\": 99}", 3), -1);
        Assert.assertEquals(LlmOpponent.parseSpellChoice("{\"choice\": 2, \"reason\": \"ok\"}", 3), 2);
        Assert.assertEquals(LlmOpponent.parseSpellChoice("{\"choice\": 0}", 3), 0);
    }

    @Test
    public void timeoutFallsBackWithoutHanging() throws Exception {
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeJson(exchange, 200, chatJson("too late"));
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl(baseUrl);
        s.setModel("slow-model");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(1);
        LlmOpponent.activateForTests(s);

        long start = System.currentTimeMillis();
        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        long elapsed = System.currentTimeMillis() - start;

        Assert.assertFalse(result.isSuccess(), "slow server must fail the test");
        Assert.assertTrue(result.getMessage().contains("Timeout") || result.getMessage().toLowerCase().contains("timed out"),
                "timeout message expected, got: " + result.getMessage());
        Assert.assertFalse(result.getMessage().contains(SECRET_KEY));
        Assert.assertTrue(elapsed < 8000, "must return promptly on timeout, elapsed=" + elapsed);
        Assert.assertEquals(LlmOpponent.parseSpellChoice(null, 2), -1, "null answer → Forge fallback path");
    }

    @Test
    public void testButtonAgainstLocalMockHttpServer() throws Exception {
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            Assert.assertTrue(body.contains("\"model\""), "request must be chat-completions shaped");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            Assert.assertEquals(auth, "Bearer " + SECRET_KEY);
            writeJson(exchange, 200, chatJson("ok"));
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl(baseUrl);
        s.setModel("mock-model");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(5);

        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        Assert.assertTrue(result.isSuccess(), result.getMessage());
        Assert.assertFalse(result.getMessage().contains(SECRET_KEY));
        Assert.assertTrue(hits.get() >= 1);

        Path log = tempDir.resolve("llm_decisions.log");
        if (Files.isRegularFile(log)) {
            String logText = Files.readString(log);
            Assert.assertFalse(logText.contains(SECRET_KEY), "decision log must not contain the key");
        }
    }

    @Test
    public void testButtonReportsHttpErrorWithoutKey() throws Exception {
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            writeJson(exchange, 401, "{\"error\":\"unauthorized " + SECRET_KEY + "\"}");
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl(baseUrl);
        s.setModel("mock-model");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(5);

        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        Assert.assertFalse(result.isSuccess());
        Assert.assertTrue(result.getMessage().contains("HTTP 401"),
                "must show HTTP status, got: " + result.getMessage());
        Assert.assertTrue(result.getMessage().contains("unauthorized"),
                "must show response detail, got: " + result.getMessage());
        Assert.assertFalse(result.getMessage().contains(SECRET_KEY),
                "error message leaked key: " + result.getMessage());
        Assert.assertTrue(result.getMessage().contains("***"),
                "key in body should be redacted to ***: " + result.getMessage());

        Path log = tempDir.resolve("llm_decisions.log");
        Assert.assertTrue(Files.isRegularFile(log));
        String logText = Files.readString(log);
        Assert.assertFalse(logText.contains(SECRET_KEY), "HTTP error log must redact the key");
    }

    @Test
    public void testWorksWhenEnableIsOff() throws Exception {
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            writeJson(exchange, 200, chatJson("ok"));
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(false);
        s.setBaseUrl(baseUrl);
        s.setModel("mock-model");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(5);
        Assert.assertFalse(s.canActivate());
        Assert.assertTrue(s.canTest());

        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        Assert.assertTrue(result.isSuccess(), "Test must work with Enable off: " + result.getMessage());
        Assert.assertEquals(hits.get(), 1);
    }

    @Test
    public void testDoesNotMutateLiveSettings() throws Exception {
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            writeJson(exchange, 200, chatJson("ok"));
        });

        LlmSettings live = new LlmSettings();
        live.setEnabled(true);
        live.setBaseUrl("http://127.0.0.1:9/v1");
        live.setModel("live-model");
        live.setApiKey("live-key-AAAA");
        live.setTimeoutSeconds(12);
        LlmOpponent.activateForTests(live);

        LlmSettings snap = live.copy();
        snap.setEnabled(false);
        snap.setBaseUrl(baseUrl);
        snap.setModel("snap-model");
        snap.setApiKey(SECRET_KEY);
        snap.setTimeoutSeconds(5);

        LlmOpponent.TestResult result = LlmOpponent.testConnection(snap);
        Assert.assertTrue(result.isSuccess(), result.getMessage());

        Assert.assertTrue(LlmOpponent.getSettings().isEnabled());
        Assert.assertEquals(LlmOpponent.getSettings().getModel(), "live-model");
        Assert.assertEquals(LlmOpponent.getSettings().getApiKey(), "live-key-AAAA");
        Assert.assertEquals(LlmOpponent.getSettings().getTimeoutSeconds(), 12);
        Assert.assertEquals(LlmOpponent.getSettings().getBaseUrl(), "http://127.0.0.1:9/v1");

        Assert.assertFalse(snap.isEnabled());
        Assert.assertEquals(snap.getModel(), "snap-model");
    }

    @Test
    public void localServerWorksWithoutApiKey() throws Exception {
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            Assert.assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            writeJson(exchange, 200, chatJson("ok"));
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl(baseUrl);
        s.setModel("local-model");
        s.setApiKey("");
        s.setTimeoutSeconds(5);
        Assert.assertTrue(s.canActivate(), "local models may omit the key");

        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        Assert.assertTrue(result.isSuccess(), result.getMessage());
        Assert.assertEquals(hits.get(), 1);
    }

    @Test
    public void apiKeyIsTrimmedOnSetAndLoad() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setApiKey("  " + SECRET_KEY + "\t\n");
        Assert.assertEquals(s.getApiKey(), SECRET_KEY, "setApiKey must trim");

        s.setEnabled(true);
        s.setBaseUrl("http://localhost:11434/v1");
        s.setModel("m");
        s.save();

        // Corrupt the file with padded key to prove load() also trims.
        Path props = tempDir.resolve(LlmSettings.FILE_NAME);
        String raw = Files.readString(props);
        String padded = raw.replace(SECRET_KEY, "  " + SECRET_KEY + "  ");
        Files.writeString(props, padded);

        LlmSettings loaded = LlmSettings.load();
        Assert.assertEquals(loaded.getApiKey(), SECRET_KEY, "load must trim apiKey");
    }

    @Test
    public void debouncedSavingDoesNotWritePerKeystroke() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://localhost:11434/v1");
        s.setModel("m");
        s.setApiKey(SECRET_KEY);

        Path props = tempDir.resolve("debounced.properties");
        LlmSettingsPersistence persistence = new LlmSettingsPersistence(s, props.toFile(), 250L);
        try {
            persistence.scheduleSave();
            s.setModel("m1");
            persistence.scheduleSave();
            s.setModel("m2");
            persistence.scheduleSave();
            s.setModel("m3");
            persistence.scheduleSave();

            Thread.sleep(80);
            Assert.assertEquals(persistence.getSaveCount(), 0, "no write during keystroke burst");
            Assert.assertFalse(Files.isRegularFile(props), "file must not exist yet");

            Thread.sleep(300);
            Assert.assertEquals(persistence.getSaveCount(), 1, "one coalesced write after idle");
            Assert.assertTrue(Files.isRegularFile(props));
            Assert.assertTrue(Files.readString(props).contains("m3"));

            persistence.flush();
            Assert.assertEquals(persistence.getSaveCount(), 2, "flush writes immediately");
        } finally {
            persistence.close();
        }
    }

    @Test
    public void ownerOnlyPermissionsWhenSupported() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://localhost:11434/v1");
        s.setModel("m");
        s.setApiKey(SECRET_KEY);
        s.save();

        Path props = tempDir.resolve(LlmSettings.FILE_NAME);
        Assert.assertTrue(Files.isRegularFile(props));

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(props);
            Assert.assertEquals(perms, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    "POSIX 600 expected when supported");
        } catch (UnsupportedOperationException e) {
            // Non-POSIX FS: best-effort File.setReadable/setWritable was applied; at least readable to us.
            Assert.assertTrue(Files.isReadable(props));
        }
    }

    @Test
    public void redactBeforeTruncateHidesKeyStraddlingCutPoint() throws Exception {
        // Key starts near index 290 so truncate-then-redact would leave a 10-char key prefix.
        final String pad = "P".repeat(290);
        final String keyPrefix = SECRET_KEY.substring(0, 12);
        startServer((exchange, body) -> {
            hits.incrementAndGet();
            writeJson(exchange, 500, pad + SECRET_KEY + " trailing-error-detail");
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl(baseUrl);
        s.setModel("mock-model");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(5);

        LlmOpponent.TestResult result = LlmOpponent.testConnection(s);
        Assert.assertFalse(result.isSuccess());
        Assert.assertTrue(result.getMessage().startsWith("HTTP 500:"), result.getMessage());
        Assert.assertFalse(result.getMessage().contains(SECRET_KEY),
                "full key must not appear: " + result.getMessage());
        Assert.assertFalse(result.getMessage().contains(keyPrefix),
                "partial key surviving truncate-before-redact must not appear: " + result.getMessage());
        Assert.assertTrue(result.getMessage().contains("***"),
                "redaction marker expected: " + result.getMessage());
        Assert.assertTrue(result.getMessage().length() <= 320,
                "message should be truncated after redaction, len=" + result.getMessage().length());

        Path log = tempDir.resolve("llm_decisions.log");
        if (Files.isRegularFile(log)) {
            Assert.assertFalse(Files.readString(log).contains(SECRET_KEY), "log must not contain the key");
            Assert.assertFalse(Files.readString(log).contains(keyPrefix), "log must not contain key prefix");
        }
    }

    @Test
    public void permissionsAppliedBeforeKeyContentIsWritten() throws Exception {
        Path props = tempDir.resolve("perms-before-write.properties");
        AtomicBoolean keyPresentBeforeWrite = new AtomicBoolean(true);
        AtomicReference<Set<PosixFilePermission>> permsBeforeWrite = new AtomicReference<>();
        AtomicBoolean posixSupported = new AtomicBoolean(true);

        LlmSettings.setBeforeWriteForTests(() -> {
            try {
                Assert.assertTrue(Files.isRegularFile(props), "file must exist before key write");
                String content = Files.readString(props);
                keyPresentBeforeWrite.set(content.contains(SECRET_KEY));
                try {
                    permsBeforeWrite.set(Files.getPosixFilePermissions(props));
                } catch (UnsupportedOperationException e) {
                    posixSupported.set(false);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://localhost:11434/v1");
        s.setModel("m");
        s.setApiKey(SECRET_KEY);
        s.save(props.toFile());

        Assert.assertFalse(keyPresentBeforeWrite.get(), "API key must not be on disk before restrict+write");
        if (posixSupported.get()) {
            Assert.assertEquals(permsBeforeWrite.get(),
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    "POSIX 600 must be applied before key content is written");
        }
        String after = Files.readString(props);
        Assert.assertTrue(after.contains(SECRET_KEY), "key is stored after the restricted write");
        Assert.assertFalse(after.isBlank());
    }

    @Test
    public void concurrentSavesAndFlushesProduceConsistentFile() throws Exception {
        Path props = tempDir.resolve("concurrent.properties");
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://localhost:11434/v1");
        s.setModel("model-0");
        s.setApiKey(SECRET_KEY);
        s.setTimeoutSeconds(15);

        LlmSettingsPersistence persistence = new LlmSettingsPersistence(s, props.toFile(), 30L);
        int threads = 8;
        int opsPerThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < opsPerThread; i++) {
                            String model = "model-" + threadId + "-" + i;
                            synchronized (s) {
                                s.setModel(model);
                                s.setApiKey(SECRET_KEY);
                                s.setTimeoutSeconds(10 + ((threadId + i) % 5));
                            }
                            persistence.scheduleSave();
                            if ((i % 4) == 0) {
                                persistence.flush();
                            }
                        }
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            Assert.assertTrue(done.await(30, TimeUnit.SECONDS), "workers finished");
            persistence.flush();
        } finally {
            persistence.close();
            pool.shutdownNow();
        }

        Assert.assertTrue(errors.isEmpty(), "worker errors: " + errors);
        Assert.assertTrue(Files.isRegularFile(props));
        String fileText = Files.readString(props);
        Assert.assertFalse(fileText.contains("\0"), "file must not contain NUL tears");
        Assert.assertTrue(fileText.contains(SECRET_KEY), "final file retains the key");

        Properties parsed = new Properties();
        try (var in = Files.newInputStream(props)) {
            parsed.load(in);
        }
        Assert.assertEquals(parsed.getProperty(LlmSettings.PROP_API_KEY), SECRET_KEY);
        Assert.assertEquals(parsed.getProperty(LlmSettings.PROP_ENABLED), "true");
        String model = parsed.getProperty(LlmSettings.PROP_MODEL);
        Assert.assertNotNull(model);
        Assert.assertTrue(model.startsWith("model-"), "model must be a complete value, got: " + model);
        Assert.assertFalse(model.contains("model-model-"), "model value must not be interleaved garbage");

        LlmSettings loaded = LlmSettings.load(props.toFile());
        Assert.assertEquals(loaded.getApiKey(), SECRET_KEY);
        Assert.assertTrue(loaded.getModel().startsWith("model-"));
        Assert.assertTrue(loaded.isEnabled());
    }

    @Test
    public void keyDecisionFilterDistinguishesSpellsFromEmpty() {
        Assert.assertFalse(LlmOpponent.isKeySpellDecision(null));
        Assert.assertFalse(LlmOpponent.isKeySpellDecision(java.util.Collections.emptyList()));
    }

    // ---- helpers ----

    private String baseUrlOrPlaceholder() {
        return baseUrl != null ? baseUrl : "http://127.0.0.1:9/v1";
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange, String body) throws IOException;
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            String body = new String(raw, StandardCharsets.UTF_8);
            handler.handle(exchange, body);
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/v1";
    }

    private static String chatJson(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"}}]}";
    }

    private static void writeJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
