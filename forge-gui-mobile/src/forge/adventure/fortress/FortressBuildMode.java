package forge.adventure.fortress;

import forge.adventure.data.FortressStructureData;
import forge.adventure.data.FortressStructureListData;
import forge.adventure.util.KeyBinding;

import java.util.Set;

/**
 * Grid-cursor build mode state (FT1). Keyboard, mouse and controller all drive the same
 * cursor / rotate / place / demolish actions.
 *
 * <p>Controls (documented for the PR):
 * <ul>
 *   <li>Move cursor: arrows / WASD / D-pad</li>
 *   <li>Rotate: R or Y / face-top</li>
 *   <li>Place: Enter / A</li>
 *   <li>Demolish: Delete / X</li>
 *   <li>Cycle structure: [ ] / L1 R1</li>
 *   <li>Exit: Esc / B</li>
 *   <li>Mouse: click sets cursor; click again with valid preview places</li>
 * </ul>
 */
public final class FortressBuildMode {
    private boolean active;
    private FortressInstance instance;
    private int cursorX;
    private int cursorY;
    private int rotationDeg;
    private int structureIndex;
    private int constructionLevel = 1;

    public void open(FortressInstance instance, int constructionLevel) {
        this.instance = instance;
        this.constructionLevel = Math.max(1, constructionLevel);
        this.active = instance != null;
        this.rotationDeg = 0;
        this.structureIndex = 0;
        if (instance != null) {
            cursorX = instance.getGridOriginX();
            cursorY = instance.getGridOriginY();
        }
    }

    public void close() {
        active = false;
        instance = null;
    }

    public boolean isActive() {
        return active && instance != null;
    }

    public FortressInstance getInstance() {
        return instance;
    }

    public int getCursorX() {
        return cursorX;
    }

    public int getCursorY() {
        return cursorY;
    }

    public int getRotationDeg() {
        return rotationDeg;
    }

    public void setCursor(int gridX, int gridY) {
        cursorX = gridX;
        cursorY = gridY;
    }

    public void moveCursor(int dx, int dy) {
        cursorX += dx;
        cursorY += dy;
    }

    public void rotate() {
        rotationDeg = FortressBuildGrid.nextRotation(rotationDeg);
    }

    public FortressStructureData selectedStructure() {
        var all = FortressStructureListData.getAll();
        if (all.size == 0)
            return null;
        if (structureIndex < 0)
            structureIndex = 0;
        if (structureIndex >= all.size)
            structureIndex = all.size - 1;
        return all.get(structureIndex);
    }

    public void cycleStructure(int delta) {
        var all = FortressStructureListData.getAll();
        if (all.size == 0)
            return;
        structureIndex = (structureIndex + delta) % all.size;
        if (structureIndex < 0)
            structureIndex += all.size;
    }

    public boolean previewValid() {
        return previewValid(Integer.MIN_VALUE, Integer.MIN_VALUE, -1, -1, 0, 0, null);
    }

    public boolean previewValid(int playerGridX, int playerGridY,
                                int entryGridX, int entryGridY, int mapW, int mapH) {
        return previewValid(playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, null);
    }

    public boolean previewValid(int playerGridX, int playerGridY,
                                int entryGridX, int entryGridY, int mapW, int mapH,
                                Set<Long> mapCollision) {
        return FortressBuildGrid.isValidPreview(instance, selectedStructure(),
                cursorX, cursorY, rotationDeg, constructionLevel,
                playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, mapCollision);
    }

    /**
     * Handle a keycode using {@link KeyBinding} conventions. Returns an action token:
     * {@code null} (ignored), {@code "place"}, {@code "demolish"}, {@code "rotate"},
     * {@code "exit"}, {@code "cycle_prev"}, {@code "cycle_next"}, or {@code "moved"}.
     */
    public String handleKey(int keycode) {
        if (!isActive())
            return null;
        if (KeyBinding.Back.isPressed(keycode))
            return "exit";
        if (KeyBinding.Left.isPressed(keycode)) {
            moveCursor(-1, 0);
            return "moved";
        }
        if (KeyBinding.Right.isPressed(keycode)) {
            moveCursor(1, 0);
            return "moved";
        }
        if (KeyBinding.Up.isPressed(keycode)) {
            moveCursor(0, 1);
            return "moved";
        }
        if (KeyBinding.Down.isPressed(keycode)) {
            moveCursor(0, -1);
            return "moved";
        }
        if (KeyBinding.Use.isPressed(keycode) || KeyBinding.Enter.isPressed(keycode))
            return "place";
        if (keycode == com.badlogic.gdx.Input.Keys.R
                || keycode == com.badlogic.gdx.Input.Keys.BUTTON_Y) {
            rotate();
            return "rotate";
        }
        if (keycode == com.badlogic.gdx.Input.Keys.FORWARD_DEL
                || keycode == com.badlogic.gdx.Input.Keys.DEL
                || keycode == com.badlogic.gdx.Input.Keys.BUTTON_X) {
            return "demolish";
        }
        if (KeyBinding.ScrollUp.isPressed(keycode)
                || keycode == com.badlogic.gdx.Input.Keys.LEFT_BRACKET) {
            cycleStructure(-1);
            return "cycle_prev";
        }
        if (KeyBinding.ScrollDown.isPressed(keycode)
                || keycode == com.badlogic.gdx.Input.Keys.RIGHT_BRACKET) {
            cycleStructure(1);
            return "cycle_next";
        }
        return null;
    }

    /** Mouse / touch: set cursor to the given grid cell. */
    public void handlePointerGrid(int gridX, int gridY) {
        if (!isActive())
            return;
        setCursor(gridX, gridY);
    }
}
