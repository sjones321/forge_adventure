package forge.ai.llm;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets a language model (any OpenAI-compatible chat API) make the AI opponent's <em>key</em>
 * decisions: mulligan, attacks, blocks, and main spells. Forge's own AI prepares options
 * (with targets), handles routine priority, and is the silent fallback on disable, timeout,
 * network failure, or a malformed reply.
 *
 * <p>Settings live in {@link LlmSettings} ({@code llm_opponent.properties} under
 * {@code forge.llm.dir}). The API key is local-only and is never logged. HTTP runs on a
 * dedicated thread with the configured timeout so the game/GL threads never hang on the
 * network.
 */
public final class LlmOpponent {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ExecutorService HTTP_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llm-opponent-http");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean active;
    private static volatile LlmSettings settings = new LlmSettings();

    private LlmOpponent() {
    }

    /** Turn LLM decisions on for the duel about to start (if configured), or off after it. */
    public static void setActive(boolean on) {
        if (on) {
            settings = LlmSettings.load();
            active = settings.canActivate();
            if (active) {
                try (PrintWriter out = new PrintWriter(new FileWriter(new File(LlmSettings.settingsDir(), "llm_decisions.log"), false))) {
                    out.println("=== Duel started " + LocalTime.now().format(TIME)
                            + ", model " + settings.getModel() + " ===");
                } catch (IOException ignored) {
                }
            }
        } else {
            active = false;
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** Snapshot used by the active duel (or the last load). Never log {@link LlmSettings#getApiKey()}. */
    public static LlmSettings getSettings() {
        return settings;
    }

    /** Test hook: load settings from an explicit file and optionally activate. */
    public static void activateForTests(LlmSettings s) {
        settings = s == null ? new LlmSettings() : s;
        active = settings.canActivate();
    }

    public static void deactivateForTests() {
        active = false;
        settings = new LlmSettings();
    }

    // ---------------------------------------------------------------- decisions

    /**
     * True when the option list is a "key" spell decision (cast a spell or counter), so the
     * LLM should pick. Routine activated abilities alone stay with Forge AI.
     */
    public static boolean isKeySpellDecision(List<SpellAbility> options) {
        if (options == null || options.isEmpty()) {
            return false;
        }
        for (SpellAbility sa : options) {
            if (sa == null) {
                continue;
            }
            if (sa.isSpell()) {
                return true;
            }
            if (sa.getApi() == ApiType.Counter) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mulligan keep/mull. Returns {@link Boolean#TRUE} to keep, {@link Boolean#FALSE} to mull,
     * or {@code null} to fall back to Forge AI.
     */
    public static Boolean chooseKeepHand(Player ai, int cardsToReturn) {
        if (!active || settings == null || !settings.canActivate()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nMulligan decision. Cards you would put back if you mulligan: ")
                .append(Math.max(0, cardsToReturn))
                .append(".\nDecide whether to keep this hand. Reply only with JSON: "
                        + "{\"keep\": true|false, \"reason\": \"<one sentence>\"}");
        String answer = ask(prompt.toString());
        Boolean keep = findBoolean(answer, "keep");
        if (keep == null) {
            log("  -> unusable mulligan answer, using Forge AI");
            return null;
        }
        note(ai, keep ? "keeps hand" : "mulligans");
        return keep;
    }

    /**
     * Picks one of the plays Forge's AI has prepared, or null to pass.
     * Falls back to the AI's own first choice if the model fails.
     */
    public static SpellAbility chooseSpell(Player ai, List<SpellAbility> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (!active || settings == null || !settings.canActivate()) {
            return options.get(0);
        }
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nYou have priority. Possible actions (already legal and affordable, targets chosen):\n");
        prompt.append("0. Pass (do nothing now)\n");
        for (int i = 0; i < options.size(); i++) {
            prompt.append(i + 1).append(". ").append(describeAction(options.get(i))).append('\n');
        }
        prompt.append("\nPick the single best action right now. Consider holding instants and removal for better targets, "
                + "sequencing (e.g. creatures before combat only if they matter), and what the opponent could do. "
                + "Reply only with JSON: {\"choice\": <number>, \"reason\": \"<one sentence>\"}");

        String answer = ask(prompt.toString());
        int choice = parseSpellChoice(answer, options.size());
        if (choice < 0) {
            log("  -> unusable answer, using Forge AI's choice");
            return options.get(0);
        }
        SpellAbility pick = choice == 0 ? null : options.get(choice - 1);
        note(ai, pick == null ? "passes" : "plays " + pick.getHostCard().getName());
        return pick;
    }

    /**
     * Parses {@code {"choice": N}}. Returns 0..optionCount, or -1 if unusable.
     */
    public static int parseSpellChoice(String answer, int optionCount) {
        Integer choice = findInt(answer, "choice");
        if (choice == null || choice < 0 || choice > optionCount) {
            return -1;
        }
        return choice;
    }

    /**
     * Chooses which of the legal attackers attack. Returns the chosen subset, or null to keep
     * Forge AI's own declaration.
     */
    public static List<Card> chooseAttackers(Player ai, List<Card> legal, List<Card> forgeSuggestion) {
        if (!active || settings == null || !settings.canActivate()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nIt is your declare-attackers step. Creatures that can attack:\n");
        for (int i = 0; i < legal.size(); i++) {
            prompt.append(i + 1).append(". ").append(describeCard(legal.get(i), true)).append('\n');
            prompt.append(combatOutlook(ai, legal.get(i)));
        }
        prompt.append("Forge's cautious default would attack with: ").append(names(forgeSuggestion)).append('\n');
        prompt.append("\nRules reminders: summoning-sick creatures CAN still block. A creature whose death creates tokens "
                + "or other value loses little by being blocked, so attacking with it is usually free damage or a good trade.\n");
        prompt.append("\nDecide which creatures attack. Think about the opponent's possible blocks, combat tricks, "
                + "racing, and keeping blockers back. Reply only with JSON: {\"attackers\": [<numbers>], \"reason\": \"<one sentence>\"}");

        String answer = ask(prompt.toString());
        List<Integer> picks = findIntList(answer, "attackers");
        if (picks == null) {
            log("  -> unusable answer, using Forge AI's attack");
            return null;
        }
        List<Card> chosen = new ArrayList<>();
        for (int n : picks) {
            if (n >= 1 && n <= legal.size() && !chosen.contains(legal.get(n - 1))) {
                chosen.add(legal.get(n - 1));
            }
        }
        note(ai, chosen.isEmpty() ? "doesn't attack" : "attacks with " + names(chosen));
        return chosen;
    }

    /**
     * Chooses blocks. Returns blocker -> attacker pairs (possibly empty = no blocks), or null to
     * keep Forge AI's own blocks.
     */
    public static Map<Card, Card> chooseBlocks(Player ai, List<Card> attackers, List<Card> blockers, Map<Card, Card> forgeSuggestion) {
        if (!active || settings == null || !settings.canActivate()) {
            return null;
        }
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nYou are being attacked. Attacking creatures:\n");
        for (int i = 0; i < attackers.size(); i++) {
            prompt.append("A").append(i + 1).append(". ").append(describeCard(attackers.get(i), true)).append('\n');
        }
        prompt.append("Your creatures that can block:\n");
        for (int i = 0; i < blockers.size(); i++) {
            prompt.append("B").append(i + 1).append(". ").append(describeCard(blockers.get(i), true)).append('\n');
        }
        prompt.append("A simple heuristic suggests: ");
        if (forgeSuggestion.isEmpty()) {
            prompt.append("no blocks");
        } else {
            for (Map.Entry<Card, Card> e : forgeSuggestion.entrySet()) {
                prompt.append(e.getKey().getName()).append(" blocks ").append(e.getValue().getName()).append("; ");
            }
        }
        prompt.append("\n\nDecide blocks. Each blocker blocks at most one attacker; several blockers may block the same attacker. "
                + "Consider your life total, trades, deathtouch/first strike/trample, and not throwing away creatures. "
                + "Reply only with JSON: {\"blocks\": [{\"blocker\": <B number>, \"attacker\": <A number>}], \"reason\": \"<one sentence>\"}");

        String answer = ask(prompt.toString());
        if (answer == null || !answer.contains("\"blocks\"")) {
            log("  -> unusable answer, using Forge AI's blocks");
            return null;
        }
        Map<Card, Card> chosen = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"blocker\"\\s*:\\s*\"?B?(\\d+)\"?\\s*,\\s*\"attacker\"\\s*:\\s*\"?A?(\\d+)").matcher(answer);
        while (m.find()) {
            int b = Integer.parseInt(m.group(1)), a = Integer.parseInt(m.group(2));
            if (b >= 1 && b <= blockers.size() && a >= 1 && a <= attackers.size()) {
                chosen.put(blockers.get(b - 1), attackers.get(a - 1));
            }
        }
        StringBuilder desc = new StringBuilder();
        for (Map.Entry<Card, Card> e : chosen.entrySet()) {
            desc.append(desc.length() == 0 ? "" : ", ").append(e.getKey().getName()).append(" blocks ").append(e.getValue().getName());
        }
        note(ai, chosen.isEmpty() ? "doesn't block" : desc.toString());
        return chosen;
    }

    // ---------------------------------------------------------------- connection test

    /**
     * Small chat-completions request used by the settings Test button. Works even when
     * {@link LlmSettings#isEnabled()} is false. Uses only the supplied snapshot — never
     * mutates the live duel settings. Never includes the API key in the returned message.
     */
    public static TestResult testConnection(LlmSettings s) {
        if (s == null) {
            return TestResult.fail("No settings.");
        }
        if (s.getBaseUrl() == null || s.getBaseUrl().isBlank()) {
            return TestResult.fail("Endpoint URL is empty.");
        }
        if (s.getModel() == null || s.getModel().isBlank()) {
            return TestResult.fail("Model name is empty.");
        }
        final LlmSettings snap = s; // caller should pass a copy; we still never write to global settings
        int timeout = snap.getTimeoutSeconds();
        Callable<String> call = () -> sendChat(snap, "Reply with exactly the word ok.", true);
        try {
            String content;
            if (Thread.currentThread().getName().startsWith("llm-opponent-http")) {
                content = call.call();
            } else {
                Future<String> future = HTTP_EXEC.submit(call);
                try {
                    content = future.get(timeout + 2L, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    return TestResult.fail("TimeoutException: timed out after " + timeout + "s");
                }
            }
            if (content == null) {
                return TestResult.fail("Empty response body (no message content).");
            }
            return TestResult.ok("Connected. Model replied (" + Math.min(content.length(), 80)
                    + " chars). Ready for key decisions.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return TestResult.fail(formatTestError(snap, cause));
        } catch (Exception e) {
            return TestResult.fail(formatTestError(snap, e));
        }
    }

    private static String formatTestError(LlmSettings s, Throwable e) {
        if (e instanceof HttpStatusException http) {
            String body = http.getResponseBody();
            String detail = body == null || body.isBlank() ? "(no body)" : body.trim();
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "…";
            }
            return s.redact("HTTP " + http.getStatusCode() + ": " + detail);
        }
        return s.redact(safeMessage(e));
    }

    /** Non-200 chat-completions response; message for Test UI only (always redacted before show). */
    static final class HttpStatusException extends IOException {
        private final int statusCode;
        private final String responseBody;

        HttpStatusException(int statusCode, String responseBody) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody == null ? "" : responseBody;
        }

        int getStatusCode() {
            return statusCode;
        }

        String getResponseBody() {
            return responseBody;
        }
    }

    public static final class TestResult {
        private final boolean success;
        private final String message;

        private TestResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        public static TestResult ok(String message) {
            return new TestResult(true, message);
        }

        public static TestResult fail(String message) {
            return new TestResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return (success ? "OK: " : "Error: ") + message;
        }
    }

    // ---------------------------------------------------------------- prompt text

    private static String describeState(Player ai) {
        Game game = ai.getGame();
        StringBuilder sb = new StringBuilder();
        sb.append("You are playing Magic: The Gathering as ").append(ai.getName())
                .append(". Play to win. Turn ").append(game.getPhaseHandler().getTurn())
                .append(", it is ").append(game.getPhaseHandler().getPlayerTurn() == ai ? "YOUR" : "the OPPONENT'S")
                .append(" turn, phase: ").append(game.getPhaseHandler().getPhase()).append(".\n");
        if (!game.getStack().isEmpty()) {
            sb.append("On the stack (top first): ");
            game.getStack().forEach(si -> sb.append(si.getStackDescription()).append(" | "));
            sb.append('\n');
        }
        for (Player p : game.getPlayers()) {
            boolean me = p == ai;
            sb.append('\n').append(me ? "YOU" : "OPPONENT").append(" (").append(p.getName()).append("): life ").append(p.getLife());
            if (p.getPoisonCounters() > 0) {
                sb.append(", poison ").append(p.getPoisonCounters());
            }
            sb.append(", library ").append(p.getCardsIn(ZoneType.Library).size())
                    .append(", graveyard ").append(p.getCardsIn(ZoneType.Graveyard).size()).append('\n');
            if (me) {
                sb.append("  Hand:\n");
                for (Card c : p.getCardsIn(ZoneType.Hand)) {
                    sb.append("   - ").append(describeCard(c, false)).append('\n');
                }
            } else {
                sb.append("  Hand: ").append(p.getCardsIn(ZoneType.Hand).size()).append(" cards (hidden)\n");
            }
            sb.append("  Battlefield:\n");
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                sb.append("   - ").append(describeCard(c, true)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Rough combat preview for one attacker: unblocked damage, and the result against each creature that
     * could block it (power vs toughness only; first strike, deathtouch and tricks are not modelled).
     */
    private static String combatOutlook(Player ai, Card attacker) {
        StringBuilder sb = new StringBuilder("   If it attacks: unblocked = ")
                .append(Math.max(0, attacker.getNetCombatDamage())).append(" damage.");
        String deathNote = diesTriggerNote(attacker);
        for (Player opp : ai.getOpponents()) {
            for (Card blocker : opp.getCreaturesInPlay()) {
                if (blocker.isTapped() || !forge.game.combat.CombatUtil.canBlock(attacker, blocker)) {
                    continue;
                }
                boolean attackerDies = blocker.getNetCombatDamage() >= attacker.getNetToughness() - attacker.getDamage();
                boolean blockerDies = attacker.getNetCombatDamage() >= blocker.getNetToughness() - blocker.getDamage();
                sb.append(" Blocked by ").append(blocker.getName()).append(' ')
                        .append(blocker.getNetPower()).append('/').append(blocker.getNetToughness()).append(": ");
                if (attackerDies && blockerDies) {
                    sb.append("both die");
                } else if (attackerDies) {
                    sb.append("yours dies");
                } else if (blockerDies) {
                    sb.append("theirs dies");
                } else {
                    sb.append("neither dies");
                }
                if (attackerDies && deathNote != null) {
                    sb.append(" (then ").append(deathNote).append(')');
                }
                sb.append('.');
            }
        }
        return sb.append('\n').toString();
    }

    /** The card's own "when this dies" text, if it has one. */
    private static String diesTriggerNote(Card c) {
        String text = c.getOracleText();
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith("When " + c.getName() + " dies") || l.startsWith("When this creature dies")) {
                String rest = l.substring(l.indexOf("dies") + 4).trim();
                return "its death trigger: " + (rest.startsWith(",") ? rest.substring(1).trim() : rest);
            }
        }
        return null;
    }

    private static String describeCard(Card c, boolean onBattlefield) {
        if (c.isFaceDown()) {
            return "(face-down card)";
        }
        StringBuilder sb = new StringBuilder(c.getName());
        if (!c.isLand() && c.getManaCost() != null && !c.getManaCost().isNoCost()) {
            sb.append(" {").append(c.getManaCost().getShortString()).append('}');
        }
        sb.append(" [").append(c.getType()).append(']');
        if (c.isCreature()) {
            sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
        }
        if (onBattlefield) {
            if (c.isTapped()) {
                sb.append(" TAPPED");
            }
            if (c.getDamage() > 0) {
                sb.append(", damage ").append(c.getDamage());
            }
            if (c.isCreature() && c.isSick()) {
                sb.append(", summoning sick (can't attack this turn, can still block)");
            }
            for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                sb.append(", ").append(e.getCount()).append(' ').append(e.getElement().getName()).append(" counter(s)");
            }
        }
        if (!(c.isLand() && c.getType().isBasicLand())) {
            String text = c.getOracleText();
            if (text == null || text.isBlank()) {
                text = c.getAbilityText();
            }
            if (text != null && !text.isBlank()) {
                sb.append(" — ").append(text.replace('\n', ' ').replace("\r", "").trim());
            }
        }
        return sb.toString();
    }

    private static String describeAction(SpellAbility sa) {
        String what = sa.isSpell() ? "Cast " : "Activate ";
        String desc = sa.getStackDescription();
        if (desc == null || desc.isBlank()) {
            desc = sa.toString();
        }
        return what + sa.getHostCard().getName() + ": " + desc.replace('\n', ' ').trim();
    }

    private static String names(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            sb.append(sb.length() == 0 ? "" : ", ").append(c.getName());
        }
        return sb.toString();
    }

    /** Short public note in the game log so the decision shows up in the battle log. */
    private static void note(Player ai, String what) {
        try {
            ai.getGame().getGameLog().add(GameLogEntryType.INFORMATION, ai.getName() + " (LLM) " + what);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- HTTP + JSON

    /**
     * Sends a chat completion on the HTTP executor and enforces the timeout. Returns null on
     * any failure. Never logs the API key.
     */
    static String ask(String prompt) {
        LlmSettings s = settings;
        if (s == null || !s.canActivate()) {
            return null;
        }
        long start = System.currentTimeMillis();
        log("\n----- PROMPT " + LocalTime.now().format(TIME) + " -----\n" + prompt);
        int timeout = s.getTimeoutSeconds();
        Callable<String> call = () -> sendChat(s, prompt, false);
        try {
            String content;
            if (Thread.currentThread().getName().startsWith("llm-opponent-http")) {
                content = call.call();
            } else {
                Future<String> future = HTTP_EXEC.submit(call);
                try {
                    content = future.get(timeout + 2L, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    log("----- ERROR: timeout after " + timeout + "s");
                    return null;
                }
            }
            if (content == null) {
                return null;
            }
            log("----- ANSWER (" + (System.currentTimeMillis() - start) + " ms) -----\n" + content);
            return content;
        } catch (ExecutionException e) {
            log("----- ERROR: " + s.redact(safeMessage(e.getCause() != null ? e.getCause() : e)));
            return null;
        } catch (Exception e) {
            log("----- ERROR: " + s.redact(safeMessage(e)));
            return null;
        }
    }

    /**
     * @param throwHttpErrors when true (Test button), non-200 responses raise
     *                        {@link HttpStatusException}; when false (duel path), return null.
     */
    private static String sendChat(LlmSettings s, String prompt, boolean throwHttpErrors)
            throws IOException, InterruptedException {
        String base = s.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String body = "{\"model\":" + quote(s.getModel().trim())
                + ",\"temperature\":0.3,\"max_tokens\":1500,\"messages\":[{\"role\":\"user\",\"content\":"
                + quote(prompt) + "}]}";
        int timeout = s.getTimeoutSeconds();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (s.hasApiKey()) {
            builder.header("Authorization", "Bearer " + s.getApiKey().trim());
        }
        HttpRequest req = builder.build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log("----- HTTP " + resp.statusCode() + ": " + s.redact(resp.body()));
            if (throwHttpErrors) {
                throw new HttpStatusException(resp.statusCode(), resp.body());
            }
            return null;
        }
        return extractContent(resp.body());
    }

    private static String safeMessage(Throwable e) {
        if (e == null) {
            return "unknown error";
        }
        String m = e.getClass().getSimpleName();
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            m = m + ": " + e.getMessage();
        }
        return m;
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Pulls choices[0].message.content out of an OpenAI-style response without a JSON library. */
    static String extractContent(String json) {
        int i = json.indexOf("\"content\"");
        if (i < 0) {
            return null;
        }
        i = json.indexOf(':', i) + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') {
                break;
            }
            if (ch == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    static Integer findInt(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+)").matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static Boolean findBoolean(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    static List<Integer> findIntList(String text, String key) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(text);
        if (!m.find()) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        Matcher n = Pattern.compile("\\d+").matcher(m.group(1));
        while (n.find()) {
            out.add(Integer.valueOf(n.group()));
        }
        return out;
    }

    private static synchronized void log(String text) {
        LlmSettings s = settings;
        String safe = s == null ? text : s.redact(text);
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(LlmSettings.settingsDir(), "llm_decisions.log"), true))) {
            out.println(safe);
        } catch (IOException ignored) {
        }
    }

    /** Defender helper for attack declarations: the first opposing player that can be attacked. */
    public static GameEntity primaryDefender(Combat combat) {
        for (GameEntity d : combat.getDefenders()) {
            if (d instanceof Player) {
                return d;
            }
        }
        return combat.getDefenders().isEmpty() ? null : combat.getDefenders().iterator().next();
    }

    /** Whether the current phase is one where main-spell LLM calls are expected. */
    public static boolean isMainSpellPhase(Game game) {
        if (game == null || game.getPhaseHandler() == null) {
            return false;
        }
        PhaseType phase = game.getPhaseHandler().getPhase();
        return phase == PhaseType.MAIN1 || phase == PhaseType.MAIN2
                || phase == PhaseType.COMBAT_BEGIN || phase == PhaseType.COMBAT_DECLARE_ATTACKERS
                || phase == PhaseType.COMBAT_DECLARE_BLOCKERS || !game.getStack().isEmpty();
    }
}

