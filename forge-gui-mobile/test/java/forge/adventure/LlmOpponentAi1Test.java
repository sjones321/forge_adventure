package forge.adventure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
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
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI1 behaviour tests: local key storage, redaction, disable/timeout/malformed fallback,
 * and the settings Test button against a mock OpenAI-compatible HTTP server.
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

        // Simulate a world-save style blob: only non-secret prefs would be copied there.
        Properties saveBlob = new Properties();
        saveBlob.setProperty("gold", "100");
        saveBlob.setProperty("plane", "Shandalar Ascendant");
        // AI1 contract: never copy apiKey into save data.
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

        // Log file must not contain the key even after a successful call.
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
        Assert.assertFalse(result.getMessage().contains(SECRET_KEY),
                "error message leaked key: " + result.getMessage());

        Path log = tempDir.resolve("llm_decisions.log");
        Assert.assertTrue(Files.isRegularFile(log));
        String logText = Files.readString(log);
        Assert.assertFalse(logText.contains(SECRET_KEY), "HTTP error log must redact the key");
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
