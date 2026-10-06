package forge.ai.llm;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets a language model (any OpenAI-compatible chat API) make the AI opponent's big decisions:
 * which of the plays Forge's AI is willing to make to actually make (or pass), attackers, and
 * blockers. Forge's own AI still prepares the options (with targets) and validates everything,
 * and is used as the fallback whenever the model is unavailable or answers badly.
 *
 * Settings are read from llm_opponent.properties in the directory given by the system property
 * "forge.llm.dir" (Adventure sets it to the Forge user folder): enabled, baseUrl, apiKey, model,
 * timeoutSeconds. Each duel's prompts and answers are written to llm_decisions.log there.
 */
public final class LlmOpponent {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static volatile boolean active;
    private static volatile Properties settings;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private LlmOpponent() {
    }

    /** Turn LLM decisions on for the duel about to start (if configured), or off after it. */
    public static void setActive(boolean on) {
        if (on) {
            settings = loadSettings();
            active = settings != null && "true".equalsIgnoreCase(settings.getProperty("enabled", "false").trim())
                    && !settings.getProperty("apiKey", "").isBlank() && !settings.getProperty("model", "").isBlank();
            if (active) {
                try (PrintWriter out = new PrintWriter(new FileWriter(new File(dir(), "llm_decisions.log"), false))) {
                    out.println("=== Duel started " + LocalTime.now().format(TIME) + ", model " + settings.getProperty("model") + " ===");
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

    // ---------------------------------------------------------------- decisions

    /**
     * Picks one of the plays Forge's AI has prepared, or null to pass.
     * Falls back to the AI's own first choice if the model fails.
     */
    public static SpellAbility chooseSpell(Player ai, List<SpellAbility> options) {
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nYou have priority. Possible actions (already legal and affordable, targets chosen):\n");
        prompt.append("0. Pass (do nothing now)\n");
        for (int i = 0; i < options.size(); i++)
            prompt.append(i + 1).append(". ").append(describeAction(options.get(i))).append('\n');
        prompt.append("\nPick the single best action right now. Consider holding instants and removal for better targets, "
                + "sequencing (e.g. creatures before combat only if they matter), and what the opponent could do. "
                + "Reply only with JSON: {\"choice\": <number>, \"reason\": \"<one sentence>\"}");

        String answer = ask(prompt.toString());
        Integer choice = findInt(answer, "choice");
        if (choice == null || choice < 0 || choice > options.size()) {
            log("  -> unusable answer, using Forge AI's choice");
            return options.get(0);
        }
        SpellAbility pick = choice == 0 ? null : options.get(choice - 1);
        note(ai, pick == null ? "passes" : "plays " + pick.getHostCard().getName());
        return pick;
    }

    /**
     * Chooses which of the legal attackers attack. Returns the chosen subset, or null to keep
     * Forge AI's own declaration.
     */
    public static List<Card> chooseAttackers(Player ai, List<Card> legal, List<Card> forgeSuggestion) {
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nIt is your declare-attackers step. Creatures that can attack:\n");
        for (int i = 0; i < legal.size(); i++)
            prompt.append(i + 1).append(". ").append(describeCard(legal.get(i), true)).append('\n');
        prompt.append("A simple heuristic suggests attacking with: ").append(names(forgeSuggestion)).append('\n');
        prompt.append("\nDecide which creatures attack. Think about the opponent's possible blocks, combat tricks, "
                + "racing, and keeping blockers back. Reply only with JSON: {\"attackers\": [<numbers>], \"reason\": \"<one sentence>\"}");

        String answer = ask(prompt.toString());
        List<Integer> picks = findIntList(answer, "attackers");
        if (picks == null) {
            log("  -> unusable answer, using Forge AI's attack");
            return null;
        }
        List<Card> chosen = new ArrayList<>();
        for (int n : picks)
            if (n >= 1 && n <= legal.size() && !chosen.contains(legal.get(n - 1)))
                chosen.add(legal.get(n - 1));
        note(ai, chosen.isEmpty() ? "doesn't attack" : "attacks with " + names(chosen));
        return chosen;
    }

    /**
     * Chooses blocks. Returns blocker -> attacker pairs (possibly empty = no blocks), or null to
     * keep Forge AI's own blocks.
     */
    public static Map<Card, Card> chooseBlocks(Player ai, List<Card> attackers, List<Card> blockers, Map<Card, Card> forgeSuggestion) {
        StringBuilder prompt = new StringBuilder(describeState(ai));
        prompt.append("\nYou are being attacked. Attacking creatures:\n");
        for (int i = 0; i < attackers.size(); i++)
            prompt.append("A").append(i + 1).append(". ").append(describeCard(attackers.get(i), true)).append('\n');
        prompt.append("Your creatures that can block:\n");
        for (int i = 0; i < blockers.size(); i++)
            prompt.append("B").append(i + 1).append(". ").append(describeCard(blockers.get(i), true)).append('\n');
        prompt.append("A simple heuristic suggests: ");
        if (forgeSuggestion.isEmpty())
            prompt.append("no blocks");
        else
            for (Map.Entry<Card, Card> e : forgeSuggestion.entrySet())
                prompt.append(e.getKey().getName()).append(" blocks ").append(e.getValue().getName()).append("; ");
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
            if (b >= 1 && b <= blockers.size() && a >= 1 && a <= attackers.size())
                chosen.put(blockers.get(b - 1), attackers.get(a - 1));
        }
        StringBuilder desc = new StringBuilder();
        for (Map.Entry<Card, Card> e : chosen.entrySet())
            desc.append(desc.length() == 0 ? "" : ", ").append(e.getKey().getName()).append(" blocks ").append(e.getValue().getName());
        note(ai, chosen.isEmpty() ? "doesn't block" : desc.toString());
        return chosen;
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
            if (p.getPoisonCounters() > 0)
                sb.append(", poison ").append(p.getPoisonCounters());
            sb.append(", library ").append(p.getCardsIn(ZoneType.Library).size())
                    .append(", graveyard ").append(p.getCardsIn(ZoneType.Graveyard).size()).append('\n');
            if (me) {
                sb.append("  Hand:\n");
                for (Card c : p.getCardsIn(ZoneType.Hand))
                    sb.append("   - ").append(describeCard(c, false)).append('\n');
            } else {
                sb.append("  Hand: ").append(p.getCardsIn(ZoneType.Hand).size()).append(" cards (hidden)\n");
            }
            sb.append("  Battlefield:\n");
            for (Card c : p.getCardsIn(ZoneType.Battlefield))
                sb.append("   - ").append(describeCard(c, true)).append('\n');
        }
        return sb.toString();
    }

    private static String describeCard(Card c, boolean onBattlefield) {
        if (c.isFaceDown())
            return "(face-down card)";
        StringBuilder sb = new StringBuilder(c.getName());
        if (!c.isLand() && c.getManaCost() != null && !c.getManaCost().isNoCost())
            sb.append(" {").append(c.getManaCost().getShortString()).append('}');
        sb.append(" [").append(c.getType()).append(']');
        if (c.isCreature())
            sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
        if (onBattlefield) {
            if (c.isTapped())
                sb.append(" TAPPED");
            if (c.getDamage() > 0)
                sb.append(", damage ").append(c.getDamage());
            if (c.isCreature() && c.isSick())
                sb.append(", summoning sick");
            for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet())
                sb.append(", ").append(e.getCount()).append(' ').append(e.getElement().getName()).append(" counter(s)");
        }
        if (!(c.isLand() && c.getType().isBasicLand())) {
            String text = c.getOracleText();
            if (text == null || text.isBlank())
                text = c.getAbilityText();
            if (text != null && !text.isBlank())
                sb.append(" — ").append(text.replace('\n', ' ').replace("\r", "").trim());
        }
        return sb.toString();
    }

    private static String describeAction(SpellAbility sa) {
        String what = sa.isSpell() ? "Cast " : "Activate ";
        String desc = sa.getStackDescription();
        if (desc == null || desc.isBlank())
            desc = sa.toString();
        return what + sa.getHostCard().getName() + ": " + desc.replace('\n', ' ').trim();
    }

    private static String names(List<Card> cards) {
        if (cards == null || cards.isEmpty())
            return "nothing";
        StringBuilder sb = new StringBuilder();
        for (Card c : cards)
            sb.append(sb.length() == 0 ? "" : ", ").append(c.getName());
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

    private static String ask(String prompt) {
        Properties s = settings;
        if (s == null)
            return null;
        long start = System.currentTimeMillis();
        log("\n----- PROMPT " + LocalTime.now().format(TIME) + " -----\n" + prompt);
        try {
            String body = "{\"model\":" + quote(s.getProperty("model").trim())
                    + ",\"temperature\":0.3,\"max_tokens\":1500,\"messages\":[{\"role\":\"user\",\"content\":" + quote(prompt) + "}]}";
            int timeout = Integer.parseInt(s.getProperty("timeoutSeconds", "30").trim());
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.getProperty("baseUrl").trim() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + s.getProperty("apiKey").trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log("----- HTTP " + resp.statusCode() + ": " + resp.body());
                return null;
            }
            String content = extractContent(resp.body());
            log("----- ANSWER (" + (System.currentTimeMillis() - start) + " ms) -----\n" + content);
            return content;
        } catch (Exception e) {
            log("----- ERROR: " + e);
            return null;
        }
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
                    if (ch < 0x20)
                        sb.append(String.format("\\u%04x", (int) ch));
                    else
                        sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Pulls choices[0].message.content out of an OpenAI-style response without a JSON library. */
    private static String extractContent(String json) {
        int i = json.indexOf("\"content\"");
        if (i < 0)
            return null;
        i = json.indexOf(':', i) + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i)))
            i++;
        if (i >= json.length() || json.charAt(i) != '"')
            return null;
        StringBuilder sb = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"')
                break;
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

    private static Integer findInt(String text, String key) {
        if (text == null)
            return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"?(-?\\d+)").matcher(text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static List<Integer> findIntList(String text, String key) {
        if (text == null)
            return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)]").matcher(text);
        if (!m.find())
            return null;
        List<Integer> out = new ArrayList<>();
        Matcher n = Pattern.compile("\\d+").matcher(m.group(1));
        while (n.find())
            out.add(Integer.valueOf(n.group()));
        return out;
    }

    // ---------------------------------------------------------------- settings + log

    private static File dir() {
        String d = System.getProperty("forge.llm.dir");
        return d == null ? new File(System.getProperty("user.home")) : new File(d);
    }

    private static Properties loadSettings() {
        File f = new File(dir(), "llm_opponent.properties");
        if (!f.isFile())
            return null;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    private static synchronized void log(String text) {
        try (PrintWriter out = new PrintWriter(new FileWriter(new File(dir(), "llm_decisions.log"), true))) {
            out.println(text);
        } catch (IOException ignored) {
        }
    }

    /** Defender helper for attack declarations: the first opposing player that can be attacked. */
    public static GameEntity primaryDefender(Combat combat) {
        for (GameEntity d : combat.getDefenders())
            if (d instanceof Player)
                return d;
        return combat.getDefenders().isEmpty() ? null : combat.getDefenders().iterator().next();
    }
}
