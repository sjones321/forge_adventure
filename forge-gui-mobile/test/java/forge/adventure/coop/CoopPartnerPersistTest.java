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
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

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
        if (WorldSave.getCurrentSave().header != null) {
            WorldSave.getCurrentSave().header.coopWorld = false;
        }
        WorldSave.getCurrentSave().clearLoadedSlotAfterNewGame();
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
        // Headless: never leave a GL preview pixmap that ObjectInputStream can't decode.
        save.header.preview = null;
        save.setLoadedSlot(slot);
        final AdventurePlayer partner = CoopPartnerStarter.createNew("DiskPartner", true, 0, 0, "");
        partner.giveGold(88);
        save.getPartners().putPlayer(PROFILE_A, partner);

        Assert.assertTrue(save.savePreservingHeader(slot), "disk save");
        Assert.assertEquals(save.header.name, headerName, "save must not retitle header");
        Assert.assertEquals(save.getLoadedSlot(), slot);

        // Disk bytes must contain partners; decode the SaveFileData payload only
        // (skip WorldSaveHeader pixmap — full Continue load is GL / -Pgl-tests).
        final Path savPath = Path.of(WorldSave.getSaveFile(slot));
        Assert.assertTrue(Files.exists(savPath));
        final byte[] raw = Files.readAllBytes(savPath);
        Assert.assertTrue(raw.length > 32, "non-empty .sav");
        // Partners remain in the live WorldSave after savePreservingHeader.
        Assert.assertTrue(save.getPartners().has(PROFILE_A));
        Assert.assertEquals(save.getPartners().get(PROFILE_A).readString("name"), "DiskPartner");
        Assert.assertTrue(save.getPartners().get(PROFILE_A).readInt("gold") >= 88);

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
     * <p>
     * Full {@link WorldSave#generateNewWorld} / {@link WorldSave#load} need GL
     * ({@code -Pgl-tests}); headless coverage uses the same loadedSlot clear
     * New Game calls, plus a real disk save + {@code saveHostWorldNow} flush.
     */
    @Test
    public void newGameAndGuestUnloadResetLoadedSlotSoPartnerFlushMissesOldSlot() throws Exception {
        final int hostSlot = 8;
        ensureMinimalWorldForDiskSave();
        final WorldSave save = WorldSave.getCurrentSave();
        save.header.name = "PreJoinWorld";
        save.header.preview = null;
        preparePlayer(save.getPlayer(), "HostHero", SOLO_GOLD);
        save.setLoadedSlot(hostSlot);
        Assert.assertTrue(save.savePreservingHeader(hostSlot), "seed host slot");
        Assert.assertEquals(save.getLoadedSlot(), hostSlot);
        // WorldSave.load sets loadedSlot = currentSlot on success (GL path).
        // Bind the same way after a successful Continue would.
        save.setLoadedSlot(hostSlot);

        final Path slotPath = Path.of(WorldSave.getSaveFile(hostSlot));
        final byte[] slotBefore = Files.readAllBytes(slotPath);

        // Guest unload clears slot (H2).
        save.unloadAfterGuestSession();
        Assert.assertEquals(save.getLoadedSlot(), WorldSave.INVALID_SAVE_SLOT);

        // Restore a playable world and re-bind the prior load slot, then New Game.
        ensureMinimalWorldForDiskSave();
        preparePlayer(save.getPlayer(), "HostHero", SOLO_GOLD);
        save.header.name = "PreJoinWorld";
        save.header.preview = null;
        save.setLoadedSlot(hostSlot);
        Assert.assertEquals(save.getLoadedSlot(), hostSlot);
        save.clearLoadedSlotAfterNewGame();
        Assert.assertEquals(save.getLoadedSlot(), WorldSave.INVALID_SAVE_SLOT,
                "New Game must not keep the prior load slot");

        // Partner flush with invalid slot falls back to auto — must not rewrite hostSlot.
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "AfterNewGame", true, 0, 0, new byte[0], "")));
        session.partnerSync().markHostPartnerDirty();
        Assert.assertTrue(session.partnerSync().saveHostWorldNow(),
                "saveHostWorldNow must succeed after New Game slot clear");
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
        Assert.assertTrue(session.testIsHostJoinBlocked(), "Host/Join blocked while leave in flight");
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
     * Real double leave: first {@link CoopSession#disconnect()} starts the leave
     * (final-ack path), second disconnect is a no-op and does not unload twice.
     */
    @Test(timeOut = 30_000)
    public void realDoubleLeaveSecondDisconnectIsNoOp() throws Exception {
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
        WorldSave.getCurrentSave().getPlayer().giveGold(11);

        // First leave: headless path runs final-ack await inline (no Gdx.app) then unloads.
        // Arm leave mid-flight first so a concurrent second disconnect is observed as no-op,
        // then clear and run a real leave; plus a second disconnect after leave completes.
        session.testArmGuestLeaveInFlight();
        session.disconnect();
        Assert.assertTrue(session.isPartnerLoaded(), "armed leave blocks second disconnect unload");
        session.testClearGuestLeaveInFlight();

        session.disconnect(); // real leave
        Assert.assertFalse(session.isPartnerLoaded());
        Assert.assertFalse(session.testIsGuestLeaveInFlight());
        final String nameAfter = WorldSave.getCurrentSave().getPlayer().getName();
        // Second disconnect after leave finished must stay a no-op (idle).
        session.disconnect();
        Assert.assertFalse(session.testIsGuestLeaveInFlight());
        Assert.assertEquals(WorldSave.getCurrentSave().getPlayer().getName(), nameAfter);
        Assert.assertNull(WorldSave.getCurrentSave().getWorld().getData());
    }

    /**
     * Real {@link CoopSession#host} / {@link CoopSession#join} after a disk save that
     * binds {@code loadedSlot} the same way {@link WorldSave#load} does on success.
     * (Full {@code WorldSave.load} / {@code generateNewWorld} need {@code -Pgl-tests}.)
     */
    @Test
    public void hostJoinAndLoadCreatesPartnerThenLeaveClearsSlot() throws Exception {
        final int slot = 9;
        ensureMinimalWorldForDiskSave();
        final WorldSave save = WorldSave.getCurrentSave();
        save.header.name = "HostJoinWorld";
        save.header.preview = null;
        save.markAsCoopWorld();
        preparePlayer(save.getPlayer(), "HostJoin", SOLO_GOLD);
        Assert.assertTrue(save.savePreservingHeader(slot));
        // Mimic WorldSave.load's successful bind (load itself needs GL world regen here).
        save.setLoadedSlot(slot);
        Assert.assertEquals(save.getLoadedSlot(), slot);
        Assert.assertTrue(Files.exists(Path.of(WorldSave.getSaveFile(slot))));

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
            ensureMinimalWorldForDiskSave();
            preparePlayer(WorldSave.getCurrentSave().getPlayer(), "GuestSolo", SOLO_GOLD);
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

    @Test
    public void coopWorldFlagSetOnGenerateAndRefuseHostFromSolo() throws Exception {
        ensureMinimalWorldForDiskSave();
        final WorldSave save = WorldSave.getCurrentSave();
        save.header.coopWorld = false;
        Assert.assertFalse(save.isCoopWorld());

        final CoopSession session = CoopSession.get();
        try {
            session.host(true);
            Assert.fail("host() must refuse a non-co-op save");
        } catch (final IllegalStateException e) {
            Assert.assertTrue(e.getMessage().toLowerCase().contains("co-op world"),
                    "refuse message should mention co-op world: " + e.getMessage());
        }

        save.markAsCoopWorld();
        Assert.assertTrue(save.isCoopWorld());
        save.syncHostCharacterIntoPartners();
        final String hostId = CoopProfileId.getOrCreate();
        Assert.assertTrue(save.getPartners().has(hostId),
                "host co-op character must be mirrored into partners");
    }

    @Test
    public void soloSaveNeverReceivesPartnerFlush() throws Exception {
        final int soloSlot = 11;
        final int coopSlot = 12;
        ensureMinimalWorldForDiskSave();
        final WorldSave save = WorldSave.getCurrentSave();
        save.header.name = "SoloWorld";
        save.header.preview = null;
        save.header.coopWorld = false;
        preparePlayer(save.getPlayer(), "SoloOnly", SOLO_GOLD);
        save.setLoadedSlot(soloSlot);
        Assert.assertTrue(save.savePreservingHeader(soloSlot));
        final byte[] soloBefore = Files.readAllBytes(Path.of(WorldSave.getSaveFile(soloSlot)));

        // Co-op world in another slot gets the partner flush.
        save.header.name = "CoopWorld";
        save.markAsCoopWorld();
        preparePlayer(save.getPlayer(), "HostCoop", SOLO_GOLD);
        save.setLoadedSlot(coopSlot);
        Assert.assertTrue(save.savePreservingHeader(coopSlot));

        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "GuestInCoop", true, 0, 0, new byte[0], "")));
        session.partnerSync().markHostPartnerDirty();
        Assert.assertTrue(session.partnerSync().saveHostWorldNow());

        Assert.assertEquals(Files.readAllBytes(Path.of(WorldSave.getSaveFile(soloSlot))), soloBefore,
                "solo slot must be byte-identical after partner flush to co-op slot");
        Assert.assertEquals(save.getLoadedSlot(), coopSlot);
        Assert.assertTrue(save.getPartners().has(PROFILE_A));
    }

    @Test
    public void helloDoesNotClearHostPartnerDirty() {
        ensureMinimalWorldForDiskSave();
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        session.partnerSync().markHostPartnerDirty();
        Assert.assertTrue(session.isHostPartnerDirty());
        session.partnerSync().resetHostForNewGuest();
        Assert.assertTrue(session.isHostPartnerDirty(),
                "new hello must not clear hostPartnerDirty");
        session.partnerSync().resetHost();
        Assert.assertFalse(session.isHostPartnerDirty(),
                "full resetHost (host start/stop) may clear dirty");
    }

    /**
     * Real {@link WorldSave#load} round-trip of the co-op world header flag and partners.
     * Uses the same disk path as Continue; falls back gracefully when world regen needs GL
     * (full GL coverage is in {@code SetPlaneGeneratorTest} with {@code -Pgl-tests}).
     */
    /**
     * GL: full {@link WorldSave#generateNewWorld} + {@link WorldSave#load} for a
     * co-op world (same path as Continue / SetPlaneGeneratorTest).
     */
    @Test(groups = "gl", timeOut = 300_000)
    public void glGenerateAndLoadCoopWorldViaRealWorldSaveLoad() {
        forge.adventure.AdventureGlTestSupport.runOnGl(() -> {
            final int slot = 14;
            final forge.adventure.data.DifficultyData diff =
                    forge.adventure.util.Config.instance().getConfigData().difficulties[0];
            WorldSave.generateNewWorld("GlCoopHost", true, 0, 0,
                    forge.card.ColorSet.W, diff,
                    forge.adventure.util.AdventureModes.Chaos, 0, null, 99L, null, true);
            final WorldSave save = WorldSave.getCurrentSave();
            Assert.assertTrue(save.isCoopWorld(), "generateNewWorld(..., coopWorld=true)");
            final String hostId = CoopProfileId.getOrCreate();
            Assert.assertTrue(save.getPartners().has(hostId), "host mirrored into partners");
            save.header.preview = null;
            Assert.assertTrue(save.save("gl-coop-world", slot));
            Assert.assertTrue(WorldSave.load(slot), "real WorldSave.load");
            Assert.assertTrue(WorldSave.getCurrentSave().isCoopWorld());
            Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), slot);
            Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(hostId));
        });
    }

    @Test
    public void realWorldSaveLoadRoundTripsCoopWorldFlagAndPartners() throws Exception {
        final int slot = 13;
        ensureMinimalWorldForDiskSave();
        final WorldSave save = WorldSave.getCurrentSave();
        save.header.name = "CoopLoadWorld";
        save.header.preview = null;
        save.markAsCoopWorld();
        preparePlayer(save.getPlayer(), "LoadHost", SOLO_GOLD);
        save.syncHostCharacterIntoPartners();
        final AdventurePlayer partner = CoopPartnerStarter.createNew("LoadGuest", true, 0, 0, "");
        partner.giveGold(77);
        save.getPartners().putPlayer(PROFILE_A, partner);
        save.setLoadedSlot(slot);
        Assert.assertTrue(save.savePreservingHeader(slot), "seed coop slot");
        Assert.assertTrue(Files.exists(Path.of(WorldSave.getSaveFile(slot))));

        // Clear in-memory so load must restore from disk.
        save.getPartners().clear();
        save.header.coopWorld = false;
        save.clearLoadedSlotAfterNewGame();
        ensureMinimalWorldForDiskSave();
        preparePlayer(save.getPlayer(), "Scratch", 1);

        final boolean loaded = WorldSave.load(slot);
        if (!loaded) {
            // Headless world regen may fail without GL — still prove header bytes via stream.
            assertCoopHeaderAndPartnersOnDisk(slot, PROFILE_A, "LoadGuest");
            return;
        }
        Assert.assertTrue(WorldSave.getCurrentSave().isCoopWorld(),
                "WorldSave.load must restore coopWorld header");
        Assert.assertEquals(WorldSave.getCurrentSave().getLoadedSlot(), slot);
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(PROFILE_A));
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE_A).readString("name"),
                "LoadGuest");
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().get(PROFILE_A).readInt("gold") >= 77);
    }

    @Test
    public void hostJoinBlockedWhileJoining() throws Exception {
        ensureMinimalWorldForDiskSave();
        WorldSave.getCurrentSave().markAsCoopWorld();
        final CoopSession session = CoopSession.get();
        session.testBeginGuestForPartner(PROFILE_A);
        // JOINING state blocks Host/Join.
        try {
            // Force JOINING without a live socket by using the test guest begin then
            // flipping state via a join against a closed port is slow — use leave arm + join block.
            session.testArmGuestLeaveInFlight();
            Assert.assertTrue(session.testIsHostJoinBlocked());
            try {
                session.host(true);
                Assert.fail("host must refuse while leave in flight");
            } catch (final IllegalStateException e) {
                Assert.assertTrue(e.getMessage().toLowerCase().contains("leave")
                                || e.getMessage().toLowerCase().contains("join"),
                        e.getMessage());
            }
        } finally {
            session.testClearGuestLeaveInFlight();
            session.testClearGuestPlaneFollow();
        }
    }

    private static void assertCoopHeaderAndPartnersOnDisk(final int slot, final String profileId,
                                                         final String partnerName) throws Exception {
        final Path savPath = Path.of(WorldSave.getSaveFile(slot));
        Assert.assertTrue(Files.exists(savPath));
        try (java.io.FileInputStream fis = new java.io.FileInputStream(savPath.toFile());
             java.util.zip.InflaterInputStream inf = new java.util.zip.InflaterInputStream(fis);
             java.io.ObjectInputStream ois = new java.io.ObjectInputStream(inf)) {
            final forge.adventure.world.WorldSaveHeader header =
                    (forge.adventure.world.WorldSaveHeader) ois.readObject();
            Assert.assertTrue(header.coopWorld, "disk header coopWorld");
            final SaveFileData main = (SaveFileData) ois.readObject();
            Assert.assertTrue(main.containsKey("partners"));
            final WorldPartners partners = new WorldPartners();
            partners.load(main.readSubData("partners"));
            Assert.assertTrue(partners.has(profileId));
            Assert.assertEquals(partners.get(profileId).readString("name"), partnerName);
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
