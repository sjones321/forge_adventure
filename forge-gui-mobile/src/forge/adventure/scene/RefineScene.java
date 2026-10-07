package forge.adventure.scene;

import forge.screens.FScreen;

/**
 * Adventure scene wrapper for {@link RefineScreen}. Opened from Spell Smith (Ascendant only).
 */
public class RefineScene extends ForgeScene {
    private static RefineScene object;
    private RefineScreen screen;

    public static RefineScene instance() {
        if (object == null)
            object = new RefineScene();
        return object;
    }

    private RefineScene() {
    }

    @Override
    public void enter() {
        screen = null;
        getScreen();
        super.enter();
    }

    @Override
    public FScreen getScreen() {
        return screen == null ? screen = new RefineScreen() : screen;
    }
}
