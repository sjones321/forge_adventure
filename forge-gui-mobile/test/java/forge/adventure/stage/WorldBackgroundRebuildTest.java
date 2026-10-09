package forge.adventure.stage;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import forge.adventure.data.WorldData;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;

/**
 * H3: in-place co-op world swap must rebuild chunk caches without clearing the
 * foreground group (player / enemies / mirrors stay on stage).
 */
public class WorldBackgroundRebuildTest {

    @Test
    public void rebuildChunkCachesKeepsPlayerSpriteOnStage() {
        final World live = WorldSave.getCurrentSave().getWorld();
        final WorldData grid = new WorldData();
        grid.width = 48;
        grid.height = 48;
        grid.tileSize = 16;
        grid.playerStartPosX = 0.5f;
        grid.playerStartPosY = 0.5f;
        live.installTestWorldGrid(grid, 41L);
        if (live.terrainMap != null) {
            for (int x = 0; x < live.terrainMap.length; x++) {
                Arrays.fill(live.terrainMap[x], 0x41414141);
            }
        }

        final SpriteGroup foreground = new SpriteGroup();
        final Actor player = new Actor();
        player.setName("player-sentinel");
        foreground.addActor(player);
        Assert.assertSame(player.getParent(), foreground);

        final WorldBackground bg = WorldBackground.createForTest(foreground, new Group());
        bg.setPlayerPos(128f, 128f);
        // Seed chunk arrays then rebuild — must not clear the foreground group.
        bg.rebuildChunkCaches();
        bg.rebuildChunkCaches();

        Assert.assertSame(player.getParent(), foreground,
                "rebuildChunkCaches must leave the player sprite on the foreground group");
        Assert.assertTrue(foreground.getChildren().contains(player, true));
    }
}
