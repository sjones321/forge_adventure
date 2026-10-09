package forge.adventure.coop;

import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;

/**
 * CO5: build a new partner character for the host world — sealed starter gift
 * from the world's current sets (same path as New Game sealed), plus optional
 * solo deck copy whose cards also enter the collection.
 */
public final class CoopPartnerStarter {
    private CoopPartnerStarter() {
    }

    /**
     * Create a fresh partner with a sealed pool from the host world's current
     * Standard sets. The starter deck must pass {@code minDeckSize} when card
     * data is available.
     */
    public static AdventurePlayer createNew(final String name, final boolean male,
                                            final int race, final int avatarIndex,
                                            final String soloDecklistText) {
        final String safeName = CoopPartnerValidator.capName(name);
        final AdventurePlayer host = WorldSave.getCurrentSave().getPlayer();
        final DifficultyData diff = host != null && host.getDifficulty() != null
                ? copyDifficulty(host.getDifficulty())
                : defaultDifficulty();

        final ConfigData cfg = Config.instance().getConfigData();
        final int totalPacks = Math.max(1, cfg.coopPartnerStarterPacks > 0
                ? cfg.coopPartnerStarterPacks : Math.max(1, cfg.sealedStartPacks));
        final int openedPacks = Math.max(1, Math.min(
                cfg.coopPartnerStarterOpenedPacks > 0
                        ? cfg.coopPartnerStarterOpenedPacks
                        : Math.max(1, cfg.sealedStartOpenedPacks),
                totalPacks));
        final int bonusGold = cfg.coopPartnerStarterBonusGold >= 0
                ? cfg.coopPartnerStarterBonusGold : Math.max(0, cfg.sealedStartBonusGold);

        final List<String> setCodes = hostStandardSets(host);
        final AdventurePlayer partner = new AdventurePlayer();

        try {
            final List<PaperCard> pool = new ArrayList<>();
            final List<Deck> unopened = new ArrayList<>();
            final String primarySet = setCodes.isEmpty() ? "JMP" : setCodes.get(setCodes.size() - 1);
            for (final String code : setCodes.isEmpty() ? List.of(primarySet) : setCodes) {
                for (int i = 0; i < totalPacks; i++) {
                    final Deck booster = StandardWindow.CORE_COLLECTION.equals(code)
                            ? StandardWindow.generateCoreCollectionBooster()
                            : AdventureEventController.instance().generateBooster(code);
                    if (booster == null) {
                        continue;
                    }
                    if (i < openedPacks) {
                        pool.addAll(booster.getMain().toFlatList());
                    } else {
                        unopened.add(booster);
                    }
                }
            }

            Deck starterDeck;
            if (!pool.isEmpty()) {
                starterDeck = new SealedDeckBuilder(pool).buildDeck(primarySet);
                try {
                    final String setName = FModel.getMagicDb().getEditions().get(primarySet) != null
                            ? FModel.getMagicDb().getEditions().get(primarySet).getName()
                            : primarySet;
                    starterDeck.setName(setName + " Sealed");
                } catch (final Exception e) {
                    starterDeck.setName(safeName + " Starter");
                }
            } else {
                starterDeck = new Deck(safeName + " Starter");
            }

            partner.create(safeName, starterDeck, male, race, avatarIndex, false, false, diff, AdventureModes.Sealed);
            // Do not freeze a Standard window copy at creation — guest applies host live sets on offer.
            RewardData.invalidateCardPool();

            if (!pool.isEmpty()) {
                final List<PaperCard> leftovers = new ArrayList<>(pool);
                for (final PaperCard card : starterDeck.getAllCardsInASinglePool(true, true).toFlatList()) {
                    leftovers.remove(card);
                }
                for (final PaperCard card : leftovers) {
                    partner.addCard(card);
                }
            }
            for (final Deck booster : unopened) {
                partner.addBooster(booster);
            }
            if (bonusGold > 0) {
                partner.giveGold(bonusGold);
            }
            try {
                partner.getSkills().clear();
            } catch (final Exception ignored) {
            }
            partner.setCharacterFlag("coopPartner", 1);
        } catch (final Exception e) {
            // Headless / missing card DB — empty Sealed character still joins.
            partner.create(safeName, new Deck(safeName + " Starter"), male, race, avatarIndex,
                    false, false, diff, AdventureModes.Sealed);
            if (bonusGold > 0) {
                partner.giveGold(bonusGold);
            }
            partner.setCharacterFlag("coopPartner", 1);
        }

        if (soloDecklistText != null && !soloDecklistText.isEmpty()) {
            tryApplySoloDeck(partner, soloDecklistText);
        }
        return partner;
    }

    /**
     * Import a legacy co-op character. Keeps the imported name and look unless
     * the create event supplies empty look fields (then preserve legacy values).
     */
    public static AdventurePlayer fromLegacy(final SaveFileData legacy) {
        final AdventurePlayer partner = new AdventurePlayer();
        if (legacy != null) {
            partner.load(legacy);
        }
        partner.setCharacterFlag("coopPartner", 1);
        partner.setCharacterFlag("coopLegacyImport", 1);
        return partner;
    }

    static List<String> hostStandardSets(final AdventurePlayer host) {
        final List<String> sets = new ArrayList<>();
        if (host != null && host.getStandardWindow() != null && host.getStandardWindow().isActive()) {
            sets.addAll(host.getStandardWindow().getSets());
        }
        if (sets.isEmpty()) {
            final String[] cores = Config.instance().getConfigData().coreSets;
            if (cores != null) {
                for (final String c : cores) {
                    if (c != null && !c.isEmpty() && sets.size() < 2) {
                        sets.add(c);
                    }
                }
            }
        }
        return sets;
    }

    /** Apply the host's live Standard window onto a guest partner (rotation follow). */
    public static void applyHostStandardWindow(final AdventurePlayer partner, final String[] hostSets) {
        if (partner == null) {
            return;
        }
        final List<String> sets = new ArrayList<>();
        if (hostSets != null) {
            for (final String s : hostSets) {
                if (s != null && !s.isEmpty()) {
                    sets.add(s);
                }
            }
        }
        if (sets.isEmpty()) {
            sets.addAll(hostStandardSets(WorldSave.getCurrentSave().getPlayer()));
        }
        if (!sets.isEmpty()) {
            partner.getStandardWindow().init(sets);
        }
    }

    private static void tryApplySoloDeck(final AdventurePlayer partner, final String decklistText) {
        final String text = decklistText.length() > 32_000
                ? decklistText.substring(0, 32_000) : decklistText;
        try {
            final Deck deck = DeckSerializer.fromDecklistText(text);
            if (deck == null || deck.getMain() == null || deck.getMain().countAll() == 0) {
                return;
            }
            // Add every card to the collection, then place the deck in slot 0.
            for (final PaperCard pc : deck.getAllCardsInASinglePool(true, true).toFlatList()) {
                if (pc != null) {
                    partner.addCard(pc);
                }
            }
            partner.setDeckInSlot(0, deck);
        } catch (final Exception ignored) {
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
