package forge.adventure;

import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
import forge.adventure.data.ConfigData;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI1 mulligan-floor, decision-budget, and stub-client tests. No real network; never touches
 * the real Forge user folder, {@code forge.log}, or production {@code llm_opponent.properties}.
 */
public class LlmOpponentAi1MulliganTest {

    private Path tempDir;

    @BeforeMethod
    public void setUp() throws Exception {
        LlmOpponent.deactivateForTests();
        tempDir = Files.createTempDirectory("llm-ai1-mulligan-");
        System.setProperty("forge.llm.dir", tempDir.toAbsolutePath().toString());
    }

    @AfterMethod
    public void tearDown() {
        LlmOpponent.deactivateForTests();
        System.clearProperty("forge.llm.dir");
    }

    @Test
    public void ai1MulliganAndDecisionBudgetTunablesPersist() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setApiKey("");
        s.setTimeoutSeconds(15);
        s.setMulliganMinHandSize(5);
        s.setDecisionBudgetSeconds(30);
        s.save();

        LlmSettings loaded = LlmSettings.load();
        Assert.assertEquals(loaded.getMulliganMinHandSize(), 5);
        Assert.assertEquals(loaded.getDecisionBudgetSeconds(), 30);
        Assert.assertTrue(loaded.isMulliganMinHandSizeFromProperties());
        Assert.assertTrue(loaded.isDecisionBudgetFromProperties());
        Assert.assertTrue(loaded.toString().contains("decisionBudgetSeconds=30"));
        Assert.assertFalse(loaded.toString().contains("sk-"), "toString must not leak keys");
    }

    @Test
    public void legacyPriorityWatchdogAliasStillLoads() throws Exception {
        Path props = tempDir.resolve(LlmSettings.FILE_NAME);
        Files.writeString(props, "enabled=true\nbaseUrl=http://127.0.0.1:9/v1\nmodel=m\n"
                + "priorityWatchdogSeconds=42\n");
        LlmSettings loaded = LlmSettings.load(props.toFile());
        Assert.assertEquals(loaded.getDecisionBudgetSeconds(), 42);
        Assert.assertTrue(loaded.isDecisionBudgetFromProperties());
    }

    @Test
    public void propertiesWinOverConfigJsonWhenExplicit() throws Exception {
        Path props = tempDir.resolve(LlmSettings.FILE_NAME);
        Files.writeString(props, "enabled=true\nbaseUrl=http://127.0.0.1:9/v1\nmodel=m\n"
                + "mulliganMinHandSize=7\ndecisionBudgetSeconds=12\n");
        LlmSettings loaded = LlmSettings.load(props.toFile());
        LlmOpponent.activateForTests(loaded);
        // Config wants different values — must not override explicit properties.
        LlmOpponent.applyAscendantTunables(5, 30);
        Assert.assertEquals(LlmOpponent.getMulliganMinHandSize(), 7);
        Assert.assertEquals(LlmOpponent.getDecisionBudgetSeconds(), 12);
    }

    @Test
    public void configJsonFillsWhenPropertiesOmitTunables() throws Exception {
        Path props = tempDir.resolve(LlmSettings.FILE_NAME);
        Files.writeString(props, "enabled=true\nbaseUrl=http://127.0.0.1:9/v1\nmodel=m\n");
        LlmSettings loaded = LlmSettings.load(props.toFile());
        Assert.assertFalse(loaded.isMulliganMinHandSizeFromProperties());
        Assert.assertFalse(loaded.isDecisionBudgetFromProperties());
        LlmOpponent.activateForTests(loaded);
        LlmOpponent.applyAscendantTunables(6, 45);
        Assert.assertEquals(LlmOpponent.getMulliganMinHandSize(), 6);
        Assert.assertEquals(LlmOpponent.getDecisionBudgetSeconds(), 45);
    }

    @Test
    public void resolvedConfigBudgetPrefersNewKey() {
        ConfigData cfg = new ConfigData();
        cfg.llmDecisionBudgetSeconds = 30;
        cfg.llmPriorityWatchdogSeconds = -1;
        Assert.assertEquals(cfg.resolvedLlmDecisionBudgetSeconds(), 30);
        cfg.llmPriorityWatchdogSeconds = 55;
        cfg.llmDecisionBudgetSeconds = 30;
        Assert.assertEquals(cfg.resolvedLlmDecisionBudgetSeconds(), 55,
                "old-only config (alias differs from default) should use alias");
        cfg.llmDecisionBudgetSeconds = 40;
        cfg.llmPriorityWatchdogSeconds = 55;
        Assert.assertEquals(cfg.resolvedLlmDecisionBudgetSeconds(), 40,
                "new key wins when both are meaningfully set");
    }

    @Test(timeOut = 5_000)
    public void neverAnsweringStubFallsBackWithoutHanging() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub-model");
        s.setApiKey("");
        s.setTimeoutSeconds(1);
        s.setDecisionBudgetSeconds(2);
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
        String answer = LlmOpponent.runWithDecisionBudget("priority", () -> LlmOpponent.askForTests("ping"));
        long elapsed = System.currentTimeMillis() - start;

        Assert.assertNull(answer, "slow stub must time out to null (Forge AI fallback)");
        Assert.assertTrue(started.get() >= 1, "stub should have been invoked");
        Assert.assertTrue(elapsed < 4_000, "must not hang; elapsed=" + elapsed);
    }

    @Test(timeOut = 5_000)
    public void decisionBudgetFiresOnTimeWithinTolerance() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setTimeoutSeconds(30);
        s.setDecisionBudgetSeconds(1);
        LlmOpponent.activateForTests(s);
        LlmOpponent.setAskClientForTests(prompt -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        });

        long start = System.nanoTime();
        String answer = LlmOpponent.runWithDecisionBudget("budget-timing",
                () -> LlmOpponent.askForTests("ping"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        Assert.assertNull(answer);
        // On-time: budget is 1s; allow 200ms scheduling slack, and must not use the old +2s path.
        Assert.assertTrue(elapsedMs >= 800, "budget should roughly wait ~1s, elapsedMs=" + elapsedMs);
        Assert.assertTrue(elapsedMs <= 1500, "budget must fire on time (no +2s overshoot), elapsedMs="
                + elapsedMs);
    }

    @Test
    public void nestedDecisionBudgetUsesMinOfOuterRemainingAndNew() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setDecisionBudgetSeconds(5);
        LlmOpponent.activateForTests(s);

        long[] innerRemaining = { -1 };
        LlmOpponent.runWithDecisionBudget("outer", () -> {
            // Burn ~3s of the outer 5s budget, then nest with another 5s request.
            Thread.sleep(300);
            LlmOpponent.runWithDecisionBudget("inner", () -> {
                innerRemaining[0] = LlmOpponent.remainingDecisionBudgetNanos();
                return null;
            });
            return null;
        });
        // Inner should be capped by outer remaining (~4.7s), not a fresh 5s — so < 5s in nanos.
        long fiveSec = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Assert.assertTrue(innerRemaining[0] > 0, "inner budget should still have time left");
        Assert.assertTrue(innerRemaining[0] < fiveSec,
                "nested budget must be min(outer remaining, new); remainingNanos=" + innerRemaining[0]);
    }

    @Test
    public void forgeAiExceptionInsideDecisionBudgetPropagates() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        LlmOpponent.activateForTests(s);

        try {
            LlmOpponent.runWithDecisionBudget("boom", () -> {
                throw new IllegalStateException("forge-ai-bug");
            });
            Assert.fail("expected IllegalStateException to propagate");
        } catch (IllegalStateException e) {
            Assert.assertEquals(e.getMessage(), "forge-ai-bug");
        }
    }
}
