package forge.adventure;

import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI1 mulligan-floor settings and stub-client timeout tests. No real network; never touches
 * the real Forge user folder, {@code forge.log}, or production {@code llm_opponent.properties}.
 * Engine-level mulligan-to-0 / floor-with-Player tests live in forge-gui-desktop.
 */
public class LlmOpponentAi1MulliganTest {

    private Path tempDir;

    @BeforeMethod
    public void setUp() throws Exception {
        LlmOpponent.deactivateForTests();
        tempDir = Files.createTempDirectory("llm-ai1-mulligan-");
        // Isolate llm_opponent.properties / llm_decisions.log under a temp dir (not the real user folder).
        System.setProperty("forge.llm.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterMethod
    public void tearDown() {
        LlmOpponent.deactivateForTests();
        System.clearProperty("forge.llm.dir");
    }

    @Test
    public void ai1MulliganAndWatchdogTunablesPersist() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setApiKey("");
        s.setTimeoutSeconds(15);
        s.setMulliganMinHandSize(5);
        s.setPriorityWatchdogSeconds(30);
        s.save();

        LlmSettings loaded = LlmSettings.load();
        Assert.assertEquals(loaded.getMulliganMinHandSize(), 5);
        Assert.assertEquals(loaded.getPriorityWatchdogSeconds(), 30);
        Assert.assertTrue(loaded.toString().contains("mulliganMinHandSize=5"));
        Assert.assertFalse(loaded.toString().contains("sk-"), "toString must not leak keys");
    }

    @Test(timeOut = 5_000)
    public void neverAnsweringStubFallsBackWithoutHanging() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub-model");
        s.setApiKey("");
        s.setTimeoutSeconds(1);
        s.setPriorityWatchdogSeconds(2);
        LlmOpponent.activateForTests(s);

        AtomicInteger started = new AtomicInteger();
        LlmOpponent.setAskClientForTests(prompt -> {
            started.incrementAndGet();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{\"keep\": true}";
        });

        long start = System.currentTimeMillis();
        String answer = LlmOpponent.runWithPriorityWatchdog("priority", () -> LlmOpponent.askForTests("ping"));
        long elapsed = System.currentTimeMillis() - start;

        Assert.assertNull(answer, "slow stub must time out to null (Forge AI fallback)");
        Assert.assertTrue(started.get() >= 1, "stub should have been invoked");
        Assert.assertTrue(elapsed < 4_000, "must not hang; elapsed=" + elapsed);

        Path log = tempDir.resolve("llm_decisions.log");
        // Log may or may not exist depending on whether ask wrote before timeout; if it does,
        // it must not contain secrets (we have no key) and may mention timeout/watchdog.
        if (Files.isRegularFile(log)) {
            try {
                String text = Files.readString(log);
                Assert.assertFalse(text.contains("sk-"), "log must not contain key-like secrets");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void applyAscendantTunablesUpdatesLiveSettings() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("m");
        LlmOpponent.activateForTests(s);
        LlmOpponent.applyAscendantTunables(6, 45);
        Assert.assertEquals(LlmOpponent.getMulliganMinHandSize(), 6);
        Assert.assertEquals(LlmOpponent.getPriorityWatchdogSeconds(), 45);
    }
}
