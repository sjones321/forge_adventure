package forge.adventure.scene;

import forge.screens.FScreen;

/**
 * Adventure scene wrapper for {@link CraftingScreen}. Used when opening crafting from Spell Smith
 * (a UIScene). From the deck editor, prefer {@link forge.Forge#openScreen} so Back returns to the editor.
 */
public class CraftingScene extends ForgeScene {
    private static CraftingScene object;
    private CraftingScreen screen;

    public static CraftingScene instance() {
        if (object == null)
            object = new CraftingScene();
        return object;
    }

    private CraftingScene() {
    }

    @Override
    public void enter() {
        screen = null;
        getScreen();
        super.enter();
    }

    @Override
    public FScreen getScreen() {
        return screen == null ? screen = new CraftingScreen() : screen;
    }
}
