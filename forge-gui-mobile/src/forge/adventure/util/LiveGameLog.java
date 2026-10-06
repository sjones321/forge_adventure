package forge.adventure.util;

import forge.game.Game;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Observer;

/**
 * Debug aid: mirrors the current Adventure duel to a plain-text file (live_game.log in the
 * Forge user data folder) so the game can be reviewed outside the UI. Every game log line is
 * appended as it happens, and a full board snapshot is written at the start of each turn.
 * The file is overwritten when a new duel starts.
 */
public final class LiveGameLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Object LOCK = new Object();
    private static Game currentGame;
    private static int written;

    private LiveGameLog() {
    }

    public static File getFile() {
        return new File(ForgeConstants.USER_DIR, "live_game.log");
    }

    /** Starts mirroring the given game; replaces any previous duel's file. */
    public static void attach(Game game, String enemyName) {
        if (game == null)
            return;
        synchronized (LOCK) {
            currentGame = game;
            written = 0;
            try (PrintWriter out = new PrintWriter(new FileWriter(getFile(), false))) {
                out.println("=== Duel vs " + enemyName + " started " + LocalTime.now().format(TIME) + " ===");
            } catch (IOException ignored) {
                return;
            }
        }
        GameLog log = game.getGameLog();
        Observer observer = (o, arg) -> onLogChanged(game);
        log.addObserver(observer);
    }

    private static void onLogChanged(Game game) {
        synchronized (LOCK) {
            if (game != currentGame)
                return;
            List<GameLogEntry> entries = game.getGameLog().getAllEntries();
            try (PrintWriter out = new PrintWriter(new FileWriter(getFile(), true))) {
                for (int i = written; i < entries.size(); i++) {
                    GameLogEntry entry = entries.get(i);
                    if (entry.type() == GameLogEntryType.MANA || entry.type() == GameLogEntryType.PHASE)
                        continue;
                    if (entry.type() == GameLogEntryType.TURN)
                        writeSnapshot(out, game);
                    out.println(LocalTime.now().format(TIME) + " [" + entry.type() + "] " + entry.message());
                }
                written = entries.size();
            } catch (Exception ignored) {
                // never let logging break a game
            }
        }
    }

    private static void writeSnapshot(PrintWriter out, Game game) {
        out.println();
        out.println("----- Board at start of turn " + game.getPhaseHandler().getTurn() + " -----");
        for (Player p : game.getPlayers()) {
            out.println(p.getName() + (p.isAI() ? " (AI)" : "") + ": life " + p.getLife()
                    + (p.getPoisonCounters() > 0 ? ", poison " + p.getPoisonCounters() : "")
                    + ", library " + p.getCardsIn(ZoneType.Library).size()
                    + ", graveyard " + p.getCardsIn(ZoneType.Graveyard).size());
            out.println("  Hand: " + describe(p.getCardsIn(ZoneType.Hand)));
            out.println("  Battlefield: " + describe(p.getCardsIn(ZoneType.Battlefield)));
        }
        out.println("-----");
    }

    private static String describe(Iterable<Card> cards) {
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(c.isFaceDown() ? "(face-down)" : c.getName());
            if (c.isCreature())
                sb.append(" ").append(c.getNetPower()).append("/").append(c.getNetToughness());
            if (c.getDamage() > 0)
                sb.append(" dmg").append(c.getDamage());
            if (c.isTapped())
                sb.append(" (T)");
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
