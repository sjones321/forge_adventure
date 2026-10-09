/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.screens.match.views;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinFont;
import forge.assets.FSkinImageInterface;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.game.player.PlayerView;
import forge.interfaces.IGameController;
import forge.localinstance.skin.FSkinProp;
import forge.screens.match.MatchController;
import forge.screens.match.ModernDuelController;
import forge.screens.match.ModernDuelScreen;
import forge.toolbox.FDisplayObject;
import forge.util.Utils;

/**
 * DS1 clickable floating mana pips near the local player's field (Neo Forge
 * PlayerBar mana behaviour, reimplemented in libGDX). Controller can focus a
 * pip and activate it with A while paying.
 */
public class VFloatingMana extends FDisplayObject {
    private static final FSkinFont FONT = FSkinFont.get(12);
    /** Cached draw colours — do not allocate per frame. */
    private static final Color BACKDROP = new Color(0f, 0f, 0f, 0.45f);
    private static final Color FOCUS_FILL = new Color(1f, 1f, 1f, 0.25f);
    public static final byte[] COLORS = {
            ManaAtom.COLORLESS, MagicColor.WHITE, MagicColor.BLUE,
            MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN
    };
    private static final FSkinProp[] ICONS = {
            FSkinProp.IMG_MANA_COLORLESS, FSkinProp.IMG_MANA_W, FSkinProp.IMG_MANA_U,
            FSkinProp.IMG_MANA_B, FSkinProp.IMG_MANA_R, FSkinProp.IMG_MANA_G
    };

    private final PlayerView player;
    private boolean forceVisible;
    private int totalMana;
    private int focusedIndex = -1;
    /** Cached pip count strings — rebuilt only when a pip amount changes. */
    private final int[] cachedCounts = new int[COLORS.length];
    private final String[] cachedCountText = new String[COLORS.length];

    public VFloatingMana(final PlayerView player0) {
        player = player0;
        setVisible(false);
        for (int i = 0; i < COLORS.length; i++) {
            cachedCounts[i] = Integer.MIN_VALUE;
            cachedCountText[i] = "0";
        }
    }

    public void setForceVisible(final boolean v) {
        forceVisible = v;
        refreshVisibility();
    }

    public boolean isForceVisible() {
        return forceVisible;
    }

    public boolean hasManaAvailable() {
        return isVisible() && (forceVisible || totalMana > 0);
    }

    public int getPipCount() {
        return COLORS.length;
    }

    public void setFocusedIndex(final int index) {
        focusedIndex = index < 0 || index >= COLORS.length ? -1 : index;
    }

    public int getFocusedIndex() {
        return focusedIndex;
    }

    public void update() {
        totalMana = 0;
        for (final byte c : COLORS) {
            totalMana += player.getMana(c);
        }
        refreshVisibility();
    }

    private void refreshVisibility() {
        setVisible(ModernDuelScreen.enabled() && (forceVisible || totalMana > 0));
        if (!isVisible()) {
            focusedIndex = -1;
        }
    }

    @Override
    public boolean tap(final float x, final float y, final int count) {
        final int idx = indexAt(x);
        if (idx < 0) {
            return false;
        }
        // Taps are touch — keep pad/touch arrow mode consistent.
        if (ModernDuelScreen.enabled()) {
            ModernDuelController.get().markTouchInput();
        }
        activate(COLORS[idx]);
        return true;
    }

    /** Controller: spend the focused pip (or first with mana if none focused). */
    public boolean activateFocused() {
        int idx = focusedIndex;
        if (idx < 0) {
            idx = firstSpendableIndex();
        }
        if (idx < 0) {
            return false;
        }
        activate(COLORS[idx]);
        return true;
    }

    private int firstSpendableIndex() {
        for (int i = 0; i < COLORS.length; i++) {
            if (player.getMana(COLORS[i]) > 0) {
                return i;
            }
        }
        return forceVisible ? 0 : -1;
    }

    private void activate(final byte colorCode) {
        final IGameController controller = MatchController.instance.getGameController(player);
        if (controller == null) {
            return;
        }
        controller.useMana(colorCode);
    }

    private int indexAt(final float x) {
        final float pipW = getWidth() / COLORS.length;
        final int idx = (int) (x / pipW);
        if (idx < 0 || idx >= COLORS.length) {
            return -1;
        }
        return player.getMana(COLORS[idx]) > 0 || forceVisible ? idx : -1;
    }

    @Override
    public void draw(final Graphics g) {
        if (!isVisible()) {
            return;
        }
        final float pipW = getWidth() / COLORS.length;
        final float h = getHeight();
        g.fillRect(BACKDROP, 0, 0, getWidth(), h);
        for (int i = 0; i < COLORS.length; i++) {
            final int count = player.getMana(COLORS[i]);
            if (count <= 0 && !forceVisible) {
                continue;
            }
            final FSkinImageInterface image = Forge.getAssets().images().get(ICONS[i]);
            final float pad = Utils.scale(2);
            final float size = Math.min(pipW, h) - 2 * pad;
            final float ix = i * pipW + (pipW - size) / 2f;
            final float iy = pad;
            if (i == focusedIndex) {
                g.fillRect(FOCUS_FILL, i * pipW, 0, pipW, h);
                g.drawRect(Utils.scale(2), Color.WHITE, i * pipW + 1, 1, pipW - 2, h - 2);
            }
            g.drawImage(image, ix, iy, size, size);
            if (cachedCounts[i] != count) {
                cachedCounts[i] = count;
                cachedCountText[i] = Integer.toString(count);
            }
            g.drawText(cachedCountText[i], FONT, Color.WHITE,
                    i * pipW, iy + size - FONT.getCapHeight(), pipW, FONT.getLineHeight(),
                    false, Align.center, false);
        }
    }
}
