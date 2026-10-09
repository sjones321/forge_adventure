package forge.adventure.coop;

import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * CO5: build a new partner character for the host world — sealed-style starter gift
 * from the world's current sets (tunable), plus optional solo deck copy.
 */
public final class CoopPartnerStarter {
    private CoopPartnerStarter() {
    }

    /**
     * Create a fresh partner AdventurePlayer for the host world.
     * Uses the host player's difficulty and Standard window as the world context.
     * When the sealed/card path is unavailable (headless tests), falls back to an
     * empty Sealed character with the given name/look.
     */
    public static AdventurePlayer createNew(final String name, final boolean male,
                                            final int race, final int avatarIndex,
                                            final String soloDecklistText) {
        final String safeName = CoopPartnerValidator.capName(name);
        final AdventurePlayer host = WorldSave.getCurrentSave().getPlayer();
        final DifficultyData diff = host != null && host.getDifficulty() != null
                ? copyDifficulty(host.getDifficulty())
                : defaultDifficulty();

        final AdventurePlayer partner = new AdventurePlayer();
        final ConfigData cfg = Config.instance().getConfigData();
        final int packs = Math.max(1, cfg.coopPartnerStarterPacks > 0
                ? cfg.coopPartnerStarterPacks : Math.max(1, cfg.sealedStartPacks));
        final int opened = Math.max(1, cfg.coopPartnerStarterOpenedPacks > 0
                ? cfg.coopPartnerStarterOpenedPacks : Math.max(1, cfg.sealedStartOpenedPacks));
        final int bonusGold = cfg.coopPartnerStarterBonusGold >= 0
                ? cfg.coopPartnerStarterBonusGold : Math.max(0, cfg.sealedStartBonusGold);

        final Deck starter = new Deck(safeName + " Starter");
        partner.create(safeName, starter, male, race, avatarIndex, false, false, diff, AdventureModes.Sealed);
        if (host != null && host.getStandardWindow() != null && host.getStandardWindow().isActive()) {
            final List<String> sets = new ArrayList<>(host.getStandardWindow().getSets());
            partner.getStandardWindow().init(sets);
        }
        if (bonusGold > 0) {
            partner.giveGold(bonusGold);
        }
        partner.setCharacterFlag("coopPartner", 1);
        partner.setCharacterFlag("coopPartnerStarterPacks", packs);
        partner.setCharacterFlag("coopPartnerStarterOpened", opened);

        if (soloDecklistText != null && !soloDecklistText.isEmpty()) {
            tryApplySoloDeck(partner, soloDecklistText);
        }
        return partner;
    }

    /**
     * Import a legacy co-op character SaveFileData as this world's partner.
     */
    public static AdventurePlayer fromLegacy(final SaveFileData legacy,
                                             final String name, final boolean male,
                                             final int race, final int avatarIndex) {
        final AdventurePlayer partner = new AdventurePlayer();
        if (legacy != null) {
            partner.load(legacy);
        }
        final String safeName = CoopPartnerValidator.capName(
                name != null && !name.isEmpty() ? name : partner.getName());
        if (safeName != null && !safeName.isEmpty()) {
            try {
                final Field f = AdventurePlayer.class.getDeclaredField("name");
                f.setAccessible(true);
                f.set(partner, safeName);
            } catch (final Exception ignored) {
            }
        }
        try {
            final Field raceF = AdventurePlayer.class.getDeclaredField("heroRace");
            raceF.setAccessible(true);
            raceF.setInt(partner, race);
            final Field avatarF = AdventurePlayer.class.getDeclaredField("avatarIndex");
            avatarF.setAccessible(true);
            avatarF.setInt(partner, avatarIndex);
            final Field femaleF = AdventurePlayer.class.getDeclaredField("isFemale");
            femaleF.setAccessible(true);
            femaleF.setBoolean(partner, !male);
        } catch (final Exception ignored) {
        }
        partner.setCharacterFlag("coopPartner", 1);
        partner.setCharacterFlag("coopLegacyImport", 1);
        return partner;
    }

    private static void tryApplySoloDeck(final AdventurePlayer partner, final String decklistText) {
        final String text = decklistText.length() > 32_000
                ? decklistText.substring(0, 32_000) : decklistText;
        try {
            final Deck deck = DeckSerializer.fromDecklistText(text);
            if (deck != null && deck.getMain() != null && deck.getMain().countAll() > 0) {
                // Replace slot 0 with the gifted deck when empty/starter.
                try {
                    final Field decksF = AdventurePlayer.class.getDeclaredField("decks");
                    decksF.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    final com.badlogic.gdx.utils.Array<Deck> decks =
                            (com.badlogic.gdx.utils.Array<Deck>) decksF.get(partner);
                    if (decks != null && decks.size > 0) {
                        decks.set(0, deck);
                        final Field deckF = AdventurePlayer.class.getDeclaredField("deck");
                        deckF.setAccessible(true);
                        deckF.set(partner, deck);
                    }
                } catch (final Exception ignored) {
                }
                partner.setCharacterFlag("coopSoloDeckGift", 1);
            }
        } catch (final Exception ignored) {
            // Optional gift — ignore parse failures.
        }
    }

    private static DifficultyData copyDifficulty(final DifficultyData src) {
        final DifficultyData d = new DifficultyData();
        d.name = src.name;
        d.startingLife = src.startingLife;
        d.startingMoney = src.startingMoney;
        d.startingShards = src.startingShards;
        d.startingDifficulty = src.startingDifficulty;
        d.spawnRank = src.spawnRank;
        d.enemyLifeFactor = src.enemyLifeFactor;
        d.sellFactor = src.sellFactor;
        d.shardSellRatio = src.shardSellRatio;
        d.goldLoss = src.goldLoss;
        d.lifeLoss = src.lifeLoss;
        d.startItems = src.startItems != null ? src.startItems.clone() : new String[0];
        return d;
    }

    private static DifficultyData defaultDifficulty() {
        final DifficultyData d = new DifficultyData();
        d.name = "Easy";
        d.startingLife = 20;
        d.startingMoney = 100;
        d.startingShards = 0;
        d.startingDifficulty = true;
        d.spawnRank = 0;
        d.enemyLifeFactor = 1f;
        d.sellFactor = 0.5f;
        d.shardSellRatio = 0.5f;
        d.goldLoss = 0.1f;
        d.lifeLoss = 0.1f;
        d.startItems = new String[0];
        return d;
    }
}
