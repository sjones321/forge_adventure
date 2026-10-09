package forge.adventure.coop;

import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.util.Paths;
import forge.adventure.world.PlanarPortalPlacer;
import forge.adventure.world.SetPlaneGenerator;
import forge.adventure.world.World;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopWorldHash;
import forge.gamemodes.net.event.coop.CoopGateUpdateEvent;
import forge.gamemodes.net.event.coop.CoopPlanarGateEntry;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;

/**
 * MV2 mid-session gate-delta: host gate changes push a capped
 * {@link CoopGateUpdateEvent}; guest applies and re-hashes. Non-GL — uses
 * {@link World#installTestWorldGrid} + {@link PlanarPortalPlacer#placeGateAt}
 * (real placement / terrain clear / hash paths, no {@code generateNew}).
 */
public class CoopGateUpdateTest {

    @Test
    public void protocolVersionIsTenForGateDelta() {
        // TR1 (#27) reserved 9; this event takes 10.
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 10);
    }

    @Test
    public void wireClassFilterAllowsGateUpdateEvent() {
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopGateUpdateEvent"));
    }

    @Test
    public void midSessionGateReachesGuestAndHashesMatch() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final long seed = 42L;
        final World host = baseWorld(seed, 0x22222222);
        final String hashBefore = CoopWorldSync.hashWorld(host);

        Assert.assertTrue(PlanarPortalPlacer.placeGateAt(host, "DMU", 200f, 240f),
                "host must place a mid-session gate");
        final CoopPlanarGateEntry[] gates = CoopWorldSync.collectPlanarGates(host);
        Assert.assertEquals(gates.length, 1);
        Assert.assertEquals(gates[0].getSetCode(), "DMU");
        final String hostHash = CoopWorldSync.hashWorld(host);
        Assert.assertNotEquals(hostHash, hashBefore, "gate terrain clear must change live hash");

        // Host wire payload (same fields pushGateUpdateToGuest sends).
        final CoopGateUpdateEvent update = new CoopGateUpdateEvent(
                "home", Paths.WORLD, seed, "", hostHash, gates);
        Assert.assertEquals(update.getWorldPlaneId(), "home");
        Assert.assertEquals(update.getWorldHash(), hostHash);
        Assert.assertEquals(update.getGates().length, 1);
        Assert.assertTrue(update.getGates().length <= CoopWorldSync.MAX_PLANAR_GATES_ON_WIRE);

        // Guest that receives the update: same base world + apply host gates.
        final World guest = baseWorld(seed, 0x22222222);
        Assert.assertEquals(CoopWorldSync.hashWorld(guest), hashBefore);
        CoopWorldSync.applyHostGates(guest, update.getGates());
        Assert.assertTrue(CoopWorldHash.matches(CoopWorldSync.hashWorld(guest), update.getWorldHash()),
                "guest after applyHostGates must match host live hash");
    }

    @Test
    public void guestMissingGateUpdateIsDetectedAsMismatch() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final long seed = 99L;
        final World host = baseWorld(seed, 0x33333333);
        Assert.assertTrue(PlanarPortalPlacer.placeGateAt(host, "BRO", 180f, 220f));
        final CoopPlanarGateEntry[] gates = CoopWorldSync.collectPlanarGates(host);
        final String hostHash = CoopWorldSync.hashWorld(host);

        final CoopGateUpdateEvent update = new CoopGateUpdateEvent(
                "home", Paths.WORLD, seed, "", hostHash, gates);

        // Guest never applies the delta — still on the pre-gate world.
        final World guestMissed = baseWorld(seed, 0x33333333);
        final String guestHash = CoopWorldSync.hashWorld(guestMissed);
        Assert.assertFalse(CoopWorldHash.matches(guestHash, update.getWorldHash()),
                "guest that misses the gate update must not match the host live hash");

        // Applying a wrong / empty gate list must also fail the match (not silent).
        final World guestEmpty = baseWorld(seed, 0x33333333);
        CoopWorldSync.applyHostGates(guestEmpty, new CoopPlanarGateEntry[0]);
        Assert.assertFalse(CoopWorldHash.matches(CoopWorldSync.hashWorld(guestEmpty), hostHash),
                "empty gate replay must not falsely match after host placed a gate");
    }

    private static World baseWorld(final long seed, final int terrainFill) {
        final World w = new World();
        w.installTestWorldGrid(grid48(), seed);
        fillTerrain(w, terrainFill);
        return w;
    }

    private static WorldData grid48() {
        final WorldData grid = new WorldData();
        grid.width = 48;
        grid.height = 48;
        grid.tileSize = 16;
        grid.playerStartPosX = 0.5f;
        grid.playerStartPosY = 0.5f;
        return grid;
    }

    private static void fillTerrain(final World world, final int value) {
        if (world == null || world.terrainMap == null) {
            return;
        }
        for (int x = 0; x < world.terrainMap.length; x++) {
            Arrays.fill(world.terrainMap[x], value);
        }
    }
}
