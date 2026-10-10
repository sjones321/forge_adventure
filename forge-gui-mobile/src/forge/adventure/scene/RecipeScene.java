package forge.adventure.scene;

import forge.screens.FScreen;

/**
 * Adventure scene wrapper for {@link RecipeScreen}.
 * One shared crafting UI for Forge, Workshop, Apothecary, and Jeweler stations (Ascendant only).
 */
public class RecipeScene extends ForgeScene {
    private static RecipeScene object;
    private RecipeScreen screen;
    private String pendingStation = "forge";

    public static RecipeScene instance() {
        if (object == null)
            object = new RecipeScene();
        return object;
    }

    private RecipeScene() {
    }

    /** Open the shared recipe UI filtered to the given station key. */
    public RecipeScene open(String station) {
        pendingStation = station != null ? station : "forge";
        if (screen != null)
            screen.setStation(pendingStation);
        return this;
    }

    @Override
    public void enter() {
        // Keep one RecipeScreen instance so gold/material listeners are not re-registered
        // (and leaked) on every station visit.
        getScreen();
        if (screen != null)
            screen.setStation(pendingStation);
        super.enter();
    }

    @Override
    public FScreen getScreen() {
        return screen == null ? screen = new RecipeScreen(pendingStation) : screen;
    }

    @Override
    public boolean leave() {
        try {
            forge.adventure.coop.CoopHooks.notifyPartnerProgressChanged(false);
        } catch (final Exception ignored) {
        }
        return super.leave();
    }
}
