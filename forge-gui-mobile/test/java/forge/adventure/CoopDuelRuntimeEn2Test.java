package forge.adventure;

import forge.adventure.coop.CoopDuelRuntime;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.EnemyCoopPartners;
import forge.adventure.util.EnemyThemeDecks;
import forge.deck.Deck;
import forge.gamemodes.net.coop.CoopDuelMatchPlan;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * EN2: exercises the real {@link CoopDuelRuntime#buildHostedCoopEnemies} path used by
 * {@code startHostedCoopMatch}, plus the {@link WorldStage.PendingLootRolls} win/loss
 * bookkeeping (same rules {@link WorldStage#setWinner} applies).
 */
public class CoopDuelRuntimeEn2Test {

    @BeforeMethod
    public void setUp() {
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(true);
        EnemyThemeDecks.loadCatalogForTests(sampleCatalog());
        EnemyCoopPartners.setEnabledForTests(true);
    }

    @AfterMethod
    public void tearDown() {
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(null);
        EnemyCoopPartners.setEnabledForTests(null);
    }

    @Test
    public void startHostedCoopMatchPathBuildsPartnerSeatsAndHostLootRollsOnWire() {
        final EnemyData primary = new EnemyData();
        primary.name = "Merfolk";
        primary.themeId = "merfolk_tribal";
        primary.questTags = new String[]{"Merfolk"};
        primary.life = 20;

        final Deck hostDeck = new Deck("Host");
        final CoopDuelRuntime.HostedCoopEnemyBuild build = CoopDuelRuntime.buildHostedCoopEnemies(
                primary, 42L, Collections.emptyList(), hostDeck, false, 0);

        Assert.assertTrue(build.partnerBuilt);
        Assert.assertEquals(build.enemies.size(), 2);
        Assert.assertEquals(build.lifeFactor, 1.0f, 0.001f);
        Assert.assertEquals(build.extraCards, 0);
        Assert.assertEquals(build.lootRollsPerPlayer, 1);

        // Distinct seat names (primary keeps catalog name; partner gets Tidecaller).
        Assert.assertEquals(build.enemies.get(0).name, "Merfolk");
        Assert.assertEquals(build.enemies.get(1).name, "Merfolk Tidecaller");
        final Set<String> names = new HashSet<>();
        for (final CoopDuelMatchPlan.EnemySpec e : build.enemies) {
            Assert.assertTrue(names.add(e.name), "duplicate enemy seat name: " + e.name);
        }

        // Host-authoritative loot rolls travel on the result event (guest must not recompute).
        final CoopDuelResultEvent win = new CoopDuelResultEvent(
                7L, 0, 42L, "Merfolk", build.lootRollsPerPlayer);
        Assert.assertEquals(win.getLootRolls(), 1);
        Assert.assertTrue(win.isTeamWon());

        // WorldStage loot path: win consumes rolls (0 allowed); loss clears pending.
        final WorldStage.PendingLootRolls loot = new WorldStage.PendingLootRolls();
        loot.set(win.getLootRolls());
        Assert.assertEquals(loot.consume(), 1);
        Assert.assertEquals(loot.get(), 1); // reset after consume

        loot.set(0);
        Assert.assertEquals(loot.consume(), 0); // tunable 0 works

        loot.set(3);
        loot.clearOnLoss();
        Assert.assertEquals(loot.get(), 1);
    }

    private static EnemyThemeCatalogData sampleCatalog() {
        final EnemyThemeCatalogData cat = new EnemyThemeCatalogData();
        cat.themes = new EnemyThemeData[]{
                theme("merfolk_tribal", "Merfolk"),
                theme("merfolk_tempo", "Merfolk"),
                theme("goblin_tribal", "Goblin"),
                theme("goblin_burn", "Goblin")
        };
        return cat;
    }

    private static EnemyThemeData theme(final String id, final String tag) {
        final EnemyThemeData t = new EnemyThemeData();
        t.id = id;
        t.tags = new String[]{tag};
        t.colors = new String[]{"blue"};
        t.creatureTypes = new String[]{tag};
        t.standardRecipe = new EnemyThemeRecipeData();
        t.standardRecipe.count = 60;
        t.standardRecipe.tribe = tag;
        return t;
    }
}
