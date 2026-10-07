package forge.adventure.scene;

import forge.screens.FScreen;

/**
 * Adventure scene wrapper for {@link PrismaticScreen}. Opened from Spell Smith (Ascendant only).
 */
public class PrismaticScene extends ForgeScene {
    private static PrismaticScene object;
    private PrismaticScreen screen;

    public static PrismaticScene instance() {
        if (object == null)
            object = new PrismaticScene();
        return object;
    }

    private PrismaticScene() {
    }

    @Override
    public void enter() {
        screen = null;
        getScreen();
        super.enter();
    }

    @Override
    public FScreen getScreen() {
        return screen == null ? screen = new PrismaticScreen() : screen;
    }
}
