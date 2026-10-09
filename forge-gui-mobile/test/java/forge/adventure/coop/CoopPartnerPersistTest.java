package forge.adventure.coop;

import forge.adventure.AdventureTestUserDir;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.gamemodes.net.event.coop.CoopPartnerCreateEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotEvent;
import forge.localinstance.properties.ForgeConstants;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * CO5 partner behaviour against {@code forge.test.userDir}.
 * Uses public/package APIs only (no reflection).
 */
public class CoopPartnerPersistTest {

    private static final String PROFILE_A = "11111111-1111-1111-1111-111111111111";
    private static final String PROFILE_B = "22222222-2222-2222-2222-222222222222";
    private static final int SOLO_GOLD = 100;
    private static final int LOOT_GOLD = 250;
    private static final String LOOT_MATERIAL = "ore_iron";
    private static final int LOOT_MATERIAL_AMOUNT = 7;

    private static Path testUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;

    private AdventurePlayer player;
    private SaveFileData soloPlayerSnapshot;
    private Path soloSavPath;
    private byte[] soloSavBytesBefore;

    @BeforeClass
    public static void initIsolatedUserDirAndGui() throws Exception {
        testUserDir = AdventureTestUserDir.configuredTestUserDir();
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        AdventureTestUserDir.requireIsolatedUserDir();
        Assert.assertTrue(CoopProfileId.profileFile().getAbsolutePath().startsWith(testUserDir.toString()));
    }

    @AfterClass
    public static void assertRealUserDirUntouched() throws Exception {
        AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot, "CoopPartnerPersistTest");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        CoopProfileId.clearCacheForTests();
        player = WorldSave.getCurrentSave().getPlayer();
        preparePlayer(player, "SoloHero", SOLO_GOLD);
        soloPlayerSnapshot = player.save();
        WorldSave.getCurrentSave().getPartners().clear();
        CoopSession.get().testClearGuestPlaneFollow();
        soloSavPath = Path.of(ForgeConstants.USER_ADVENTURE_DIR,
                "Shandalar Ascendant", "save_co5solo.sav");
        Files.createDirectories(soloSavPath.getParent());
        soloSavBytesBefore = writeFakeSoloSav(soloSavPath, soloPlayerSnapshot);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        if (soloPlayerSnapshot != null && player != null) {
            try {
                player.load(soloPlayerSnapshot);
            } catch (final Exception ignored) {
                preparePlayer(player, "SoloHero", SOLO_GOLD);
            }
        }
        WorldSave.getCurrentSave().getPartners().clear();
        CoopSession.get().testClearGuestPlaneFollow();
        CoopProfileId.clearCacheForTests();
    }

    @Test
    public void firstJoinCreatesPartnerInHostSave() {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 1, new byte[0], "")));
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(PROFILE_A));
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE_A).readString("name"), "Farmhand");
    }

    @Test
    public void leaveAndRejoinRestoresPartnerWithProgress() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));

        final AdventurePlayer partner = new AdventurePlayer();
        partner.load(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        partner.giveGold(LOOT_GOLD);
        Assert.assertTrue(partner.addMaterial(LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
        final byte[] snap = CoopPartnerCodec.encode(partner.save());
        Assert.assertTrue(session.partnerSync().applySnapshotOnGl(
                new CoopPartnerSnapshotEvent(PROFILE_A, 1L, false, snap)));

        final SaveFileData partnersBlob = WorldSave.getCurrentSave().getPartners().save();
        final WorldPartners reloaded = new WorldPartners();
        reloaded.load(partnersBlob);
        Assert.assertTrue(reloaded.has(PROFILE_A));
        Assert.assertTrue(rawHasMaterial(reloaded.get(PROFILE_A), LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
    }

    @Test
    public void guestCrashLosesOnlyLastSnapshotWindow() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));

        final AdventurePlayer partner = new AdventurePlayer();
        partner.load(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        final int goldAfterFirst = partner.getGold() + 50;
        partner.giveGold(50);
        Assert.assertTrue(session.partnerSync().applySnapshotOnGl(
                new CoopPartnerSnapshotEvent(PROFILE_A, 1L, false, CoopPartnerCodec.encode(partner.save()))));

        partner.giveGold(999);
        final SaveFileData onHost = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(onHost.readInt("gold"), goldAfterFirst);
    }

    @Test
    public void guestSoloSavByteIdenticalAndSaveUnavailableAfterLeave() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));

        session.testBeginGuestForPartner(PROFILE_A);
        session.applyGuestPartnerBlob(blob, new String[0]);
        Assert.assertTrue(session.isPartnerLoaded());
        WorldSave.getCurrentSave().getPlayer().giveGold(LOOT_GOLD);

        WorldSave.getCurrentSave().unloadAfterGuestSession();
        Assert.assertNull(WorldSave.getCurrentSave().getWorld().getData(), "world data null after leave");
        Assert.assertFalse(WorldSave.getCurrentSave().save("should-fail", 3), "Save unavailable");
        Assert.assertEquals(Files.readAllBytes(soloSavPath), soloSavBytesBefore, "solo .sav unchanged");
    }

    @Test
    public void legacyChrImportCreatesPartner() throws Exception {
        final File chrDir = new File(testUserDir.toFile(), "adventure/Shandalar Ascendant/characters");
        Assert.assertTrue(chrDir.mkdirs() || chrDir.isDirectory());
        final File chr = new File(chrDir, "LegacyHero.chr");
        final AdventurePlayer legacy = new AdventurePlayer();
        preparePlayer(legacy, "LegacyHero", 333);
        writeChr(chr, legacy.save());

        final byte[] legacyBlob = CoopLegacyChrImport.encodeChrFile(chr);
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Ignored", true, 0, 0, legacyBlob, "")));
        final SaveFileData stored = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(stored.readString("name"), "LegacyHero");
        Assert.assertEquals(stored.readInt("gold"), 333);
    }

    @Test
    public void twoGuestsGetTwoPartners() {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "PartnerA", true, 0, 0, new byte[0], "")));
        session.testBeginHostForPartner(PROFILE_B);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_B, "PartnerB", false, 1, 2, new byte[0], "")));
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().size(), 2);
    }

    @Test
    public void hostWithTwoWorldsKeepsSeparatePartners() {
        final WorldPartners world1 = new WorldPartners();
        final WorldPartners world2 = new WorldPartners();
        world1.putPlayer(PROFILE_A, CoopPartnerStarter.createNew("W1", true, 0, 0, ""));
        world2.putPlayer(PROFILE_A, CoopPartnerStarter.createNew("W2", true, 0, 0, ""));
        Assert.assertEquals(world1.get(PROFILE_A).readString("name"), "W1");
        Assert.assertEquals(world2.get(PROFILE_A).readString("name"), "W2");
    }

    @Test
    public void profileIdStablePerInstall() {
        final String a = CoopProfileId.getOrCreate();
        Assert.assertEquals(CoopProfileId.getOrCreate(), a);
        CoopProfileId.clearCacheForTests();
        Assert.assertEquals(CoopProfileId.getOrCreate(), a);
    }

    @Test
    public void partnersRoundTripThroughWorldSaveBlob() {
        final WorldPartners partners = WorldSave.getCurrentSave().getPartners();
        final AdventurePlayer p = CoopPartnerStarter.createNew("RoundTrip", true, 0, 0, "");
        p.giveGold(42);
        partners.putPlayer(PROFILE_A, p);
        final WorldPartners loaded = new WorldPartners();
        loaded.load(partners.save());
        Assert.assertEquals(loaded.get(PROFILE_A).readString("name"), "RoundTrip");
        Assert.assertTrue(loaded.get(PROFILE_A).readInt("gold") >= 42);
    }

    @Test
    public void realWorldSaveDiskRoundTripPartnersAndNoHeaderRetitle() throws Exception {
        final int slot = 7;
        final String headerName = "Host World Alpha";
        ensureMinimalWorldForDiskSave();
        final WorldSave save = WorldSave.getCurrentSave();
        save.header.name = headerName;
        save.setLoadedSlot(slot);
        final AdventurePlayer partner = CoopPartnerStarter.createNew("DiskPartner", true, 0, 0, "");
        partner.giveGold(88);
        save.getPartners().putPlayer(PROFILE_A, partner);

        Assert.assertTrue(save.savePreservingHeader(slot), "disk save");
        Assert.assertEquals(save.header.name, headerName, "save must not retitle header");

        // Real disk round-trip: read the .sav with the same Inflater/ObjectInputStream path.
        final Path savPath = Path.of(WorldSave.getSaveFile(slot));
        Assert.assertTrue(Files.exists(savPath));
        forge.adventure.world.WorldSaveHeader diskHeader;
        SaveFileData mainData;
        try (FileInputStream fos = new FileInputStream(savPath.toFile());
             InflaterInputStream inf = new InflaterInputStream(fos);
             ObjectInputStream oos = new ObjectInputStream(inf)) {
            diskHeader = (forge.adventure.world.WorldSaveHeader) oos.readObject();
            mainData = (SaveFileData) oos.readObject();
        }
        Assert.assertEquals(diskHeader.name, headerName);
        Assert.assertTrue(mainData.containsKey("partners"), "partners written to disk");
        final WorldPartners fromDisk = new WorldPartners();
        fromDisk.load(mainData.readSubData("partners"));
        Assert.assertTrue(fromDisk.has(PROFILE_A));
        Assert.assertEquals(fromDisk.get(PROFILE_A).readString("name"), "DiskPartner");
        Assert.assertTrue(fromDisk.get(PROFILE_A).readInt("gold") >= 88);

        // Host partner flush must keep the same slot and header title.
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        session.partnerSync().markHostPartnerDirty();
        final Path slot0 = Path.of(WorldSave.getSaveFile(0));
        final byte[] slot0Before = Files.exists(slot0) ? Files.readAllBytes(slot0) : null;
        Assert.assertTrue(session.partnerSync().saveHostWorldNow());
        Assert.assertEquals(WorldSave.getCurrentSave().header.name, headerName,
                "saveHostWorldNow must never retitle header");
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), slot);
        if (slot0Before == null) {
            Assert.assertFalse(Files.exists(slot0), "must not invent hidden slot 0");
        } else {
            Assert.assertEquals(Files.readAllBytes(slot0), slot0Before, "must not overwrite slot 0");
        }
    }

    @Test
    public void realJoinLeaveKeepsSoloSlotBytesIdentical() throws Exception {
        final Path soloSlot = Path.of(WorldSave.getSaveFile(3));
        Files.createDirectories(soloSlot.getParent());
        final byte[] before = ("solo-slot-v2|" + SOLO_GOLD).getBytes();
        Files.write(soloSlot, before);

        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));

        session.testBeginGuestForPartner(PROFILE_A);
        partnerSyncRememberName(session, "SoloHero");
        session.applyGuestPartnerBlob(blob, new String[0]);
        Assert.assertTrue(session.isPartnerLoaded());
        Assert.assertTrue(session.isGuestSession());
        WorldSave.getCurrentSave().getPlayer().giveGold(LOOT_GOLD);

        // Real leave path (final ack timeout ok headless — no host on wire).
        session.testGuestLeaveToMenu();
        Assert.assertFalse(session.isGuestSession(), "leave guard cleared after unload");
        Assert.assertFalse(session.isPartnerLoaded());
        Assert.assertNull(WorldSave.getCurrentSave().getWorld().getData());
        Assert.assertEquals(Files.readAllBytes(soloSlot), before, "solo slot file untouched by join/leave");
    }

    @Test
    public void autosaveDuringLeaveWindowDoesNotWritePartner() throws Exception {
        ensureMinimalWorldForDiskSave();
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));

        session.testBeginGuestForPartner(PROFILE_A);
        session.applyGuestPartnerBlob(blob, new String[0]);
        WorldSave.getCurrentSave().getPlayer().giveGold(LOOT_GOLD);
        Assert.assertEquals(WorldSave.getCurrentSave().getPlayer().getName(), "Farmhand");

        // Leave window: guard stays up so autosave is refused before unload completes.
        session.testSetGuestLeaveGuard(true);
        Assert.assertTrue(session.isGuestSession());
        Assert.assertFalse(WorldSave.getCurrentSave().autoSave(),
                "autosave blocked while leave guard is set");

        final Path autoPath = Path.of(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT));
        final boolean autoExisted = Files.exists(autoPath);
        final byte[] autoBefore = autoExisted ? Files.readAllBytes(autoPath) : null;

        // Complete leave: unload first, then clear guard.
        session.testGuestLeaveToMenu();
        Assert.assertFalse(session.isGuestSession());
        Assert.assertNull(WorldSave.getCurrentSave().getWorld().getData());
        Assert.assertFalse(WorldSave.getCurrentSave().autoSave(),
                "autosave still refuses with null world data");

        if (autoExisted) {
            Assert.assertEquals(Files.readAllBytes(autoPath), autoBefore,
                    "pre-existing auto_save.sav must not gain partner bytes");
        } else {
            Assert.assertFalse(Files.exists(autoPath),
                    "leave must not create auto_save.sav with partner data");
        }
    }

    @Test
    public void finalSnapshotBypassesRateLimit() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        for (int i = 0; i < 20; i++) {
            session.partnerSync().validator().acceptSnapshot();
        }
        Assert.assertFalse(session.partnerSync().validator().acceptSnapshot());
        Assert.assertTrue(session.partnerSync().applySnapshotOnGl(
                        new CoopPartnerSnapshotEvent(PROFILE_A, 99L, true, blob)),
                "final snapshot must not be rate-limited");
    }

    /**
     * H2: New Game (and guest unload) must reset {@code loadedSlot} so a later
     * partner flush cannot overwrite the slot the host loaded earlier.
     * Goes through {@link WorldSave#generateNewWorld}, {@link WorldSave#save},
     * and {@link WorldSave#load(int)} (same path as SetPlaneGeneratorTest).
     */
    @Test
    public void newGameAndGuestUnloadResetLoadedSlotSoPartnerFlushMissesOldSlot() throws Exception {
        final int hostSlot = 8;
        final forge.adventure.data.DifficultyData diff =
                forge.adventure.util.Config.instance().getConfigData().difficulties[0];
        WorldSave.generateNewWorld("PreJoinWorld", true, 0, 0,
                forge.card.ColorSet.W, diff,
                AdventureModes.Chaos, 0, null, 4242L);
        final WorldSave save = WorldSave.getCurrentSave();
        Assert.assertNotNull(save.getWorld().getData());
        // New Game itself clears loadedSlot.
        Assert.assertEquals(save.getLoadedSlot(), WorldSave.INVALID_SAVE_SLOT);

        Assert.assertTrue(save.save("PreJoinWorld", hostSlot), "seed host slot");
        Assert.assertEquals(save.getLoadedSlot(), hostSlot);
        Assert.assertTrue(WorldSave.load(hostSlot), "WorldSave.load must bind loadedSlot");
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), hostSlot);

        final Path slotPath = Path.of(WorldSave.getSaveFile(hostSlot));
        final byte[] slotBefore = Files.readAllBytes(slotPath);

        // Guest unload clears slot (H2).
        WorldSave.getCurrentSave().unloadAfterGuestSession();
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), WorldSave.INVALID_SAVE_SLOT);

        // Reload, then New Game — also clears loadedSlot.
        Assert.assertTrue(WorldSave.load(hostSlot));
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), hostSlot);
        WorldSave.generateNewWorld("NewGameHero", true, 0, 0,
                forge.card.ColorSet.W, diff,
                AdventureModes.Chaos, 0, null, 99L);
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), WorldSave.INVALID_SAVE_SLOT,
                "New Game must not keep the prior load slot");

        // Partner flush with invalid slot falls back to auto — must not rewrite hostSlot.
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "AfterNewGame", true, 0, 0, new byte[0], "")));
        session.partnerSync().markHostPartnerDirty();
        Assert.assertTrue(session.partnerSync().saveHostWorldNow());
        Assert.assertEquals(Files.readAllBytes(slotPath), slotBefore,
                "partner flush after New Game must not overwrite the old host slot");
        Assert.assertTrue(Files.exists(Path.of(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT))),
                "fallback flush writes auto_save.sav");
    }

    /**
     * H3: a second leave while one is in flight must be a no-op (CoopDisconnectEvent
     * + onDisconnected, or guest c.disconnect(), must not unload twice).
     */
    @Test
    public void doubleDisconnectWhileLeaveInFlightIsNoOp() throws Exception {
        ensureMinimalWorldForDiskSave();
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));

        session.testBeginGuestForPartner(PROFILE_A);
        partnerSyncRememberName(session, "SoloHero");
        session.applyGuestPartnerBlob(blob, new String[0]);
        Assert.assertTrue(session.isPartnerLoaded());
        Assert.assertEquals(WorldSave.getCurrentSave().getPlayer().getName(), "Farmhand");

        session.testArmGuestLeaveInFlight();
        Assert.assertTrue(session.testIsGuestLeaveInFlight());
        session.disconnect(); // second leave while in flight
        Assert.assertTrue(session.testIsGuestLeaveInFlight(), "flag must stay armed");
        Assert.assertTrue(session.isPartnerLoaded(), "second leave must not unload");
        Assert.assertEquals(WorldSave.getCurrentSave().getPlayer().getName(), "Farmhand");

        session.testClearGuestLeaveInFlight();
        session.testGuestLeaveToMenu();
        Assert.assertFalse(session.testIsGuestLeaveInFlight());
        Assert.assertFalse(session.isPartnerLoaded());
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), WorldSave.INVALID_SAVE_SLOT);
    }

    /**
     * Real {@link CoopSession#host} / {@link CoopSession#join} plus {@link WorldSave#load}:
     * host a loaded world, guest joins over loopback, create partner, leave cleans up.
     */
    @Test
    public void hostJoinAndLoadCreatesPartnerThenLeaveClearsSlot() throws Exception {
        final int slot = 9;
        final forge.adventure.data.DifficultyData diff =
                forge.adventure.util.Config.instance().getConfigData().difficulties[0];
        WorldSave.generateNewWorld("HostJoinWorld", true, 0, 0,
                forge.card.ColorSet.W, diff,
                AdventureModes.Chaos, 0, null, 5151L);
        Assert.assertTrue(WorldSave.getCurrentSave().save("HostJoinWorld", slot));
        Assert.assertTrue(WorldSave.load(slot), "WorldSave.load after generateNewWorld");
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), slot);

        final CoopSession session = CoopSession.get();
        session.host(true);
        Assert.assertEquals(session.getRole(), CoopSessionRole.HOST);
        final String code = session.testSessionCode();
        Assert.assertEquals(code.length(), 8);

        // Accept a partner create while the real host listener/server are live.
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "JoinedHand", true, 0, 0, new byte[0], "")));
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(PROFILE_A));
        session.partnerSync().markHostPartnerDirty();
        Assert.assertTrue(session.partnerSync().saveHostWorldNow());
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), slot);

        session.disconnect();
        Assert.assertEquals(session.getRole(), CoopSessionRole.NONE);

        // Exercise join() against a stub overworld server on loopback.
        final forge.gamemodes.net.coop.CoopOverworldServer stub =
                new forge.gamemodes.net.coop.CoopOverworldServer(0,
                        new forge.gamemodes.net.coop.CoopMessageListener() {
                            @Override public void onConnected() { }
                            @Override public void onMessage(final forge.gamemodes.net.event.NetEvent event) { }
                            @Override public void onDisconnected(final String reason) { }
                            @Override public void onError(final String message, final Throwable cause) { }
                        });
        try {
            stub.start();
            Assert.assertTrue(stub.awaitBound(5000));
            Assert.assertTrue(WorldSave.load(slot));
            session.join("127.0.0.1:" + stub.getLocalPort(), code);
            Assert.assertEquals(session.getRole(), CoopSessionRole.GUEST);
            Assert.assertTrue(
                    session.getState() == CoopSession.State.JOINING
                            || session.getState() == CoopSession.State.READY
                            || session.getState() == CoopSession.State.DISCONNECTED
                            || session.getState() == CoopSession.State.REJECTED,
                    "join must enter a guest lifecycle state, was " + session.getState());
            session.disconnect();
        } finally {
            stub.stop();
            session.testClearGuestPlaneFollow();
        }
    }

    private static void preparePlayer(final AdventurePlayer p, final String name, final int gold) {
        final DifficultyData d = new DifficultyData();
        d.name = "Easy";
        d.startingLife = 20;
        d.startingMoney = gold;
        d.startingShards = 0;
        d.startingDifficulty = true;
        d.spawnRank = 0;
        d.enemyLifeFactor = 1f;
        d.sellFactor = 0.5f;
        d.shardSellRatio = 0.5f;
        d.goldLoss = 0.1f;
        d.lifeLoss = 0.1f;
        d.startItems = new String[0];
        p.create(name, new Deck(name), true, 0, 0, false, false, d, AdventureModes.Standard);
    }

    private static void partnerSyncRememberName(final CoopSession session, final String name) {
        session.partnerSync().rememberGuiPlayerName(name);
    }

    private static void ensureMinimalWorldForDiskSave() {
        final forge.adventure.data.WorldData data = new forge.adventure.data.WorldData();
        data.width = 16;
        data.height = 16;
        data.tileSize = 16;
        WorldSave.getCurrentSave().getWorld().installTestWorldGrid(data, 42L);
        Assert.assertNotNull(WorldSave.getCurrentSave().getWorld().getData());
    }

    private static boolean rawHasMaterial(final SaveFileData data, final String id, final int amount) {
        final Object ids = data.readObject("materialIds");
        final Object counts = data.readObject("materialCounts");
        if (!(ids instanceof String[]) || !(counts instanceof int[])) {
            return false;
        }
        final String[] materialIds = (String[]) ids;
        final int[] materialCounts = (int[]) counts;
        for (int i = 0; i < materialIds.length; i++) {
            if (id.equals(materialIds[i]) && materialCounts[i] == amount) {
                return true;
            }
        }
        return false;
    }

    private static byte[] writeFakeSoloSav(final Path path, final SaveFileData player) throws Exception {
        final byte[] payload = ("SOLO|" + player.readString("name") + "|" + player.readInt("gold")).getBytes();
        Files.write(path, payload);
        return payload;
    }

    private static void writeChr(final File file, final SaveFileData data) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
    }
}
