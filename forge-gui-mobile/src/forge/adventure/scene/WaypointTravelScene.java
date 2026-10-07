package forge.adventure.scene;

import forge.screens.FScreen;

/**
 * Adventure scene wrapper for {@link WaypointTravelScreen}. Opened from the Inn (Ascendant only).
 */
public class WaypointTravelScene extends ForgeScene {
    private static WaypointTravelScene object;
    private WaypointTravelScreen screen;
    private String currentTownId;

    public static WaypointTravelScene instance() {
        if (object == null)
            object = new WaypointTravelScene();
        return object;
    }

    private WaypointTravelScene() {
    }

    public WaypointTravelScene prepare(String townId) {
        this.currentTownId = townId;
        return this;
    }

    @Override
    public void enter() {
        screen = null;
        getScreen();
        if (screen != null)
            screen.prepare(currentTownId);
        super.enter();
    }

    @Override
    public FScreen getScreen() {
        return screen == null ? screen = new WaypointTravelScreen() : screen;
    }
}
