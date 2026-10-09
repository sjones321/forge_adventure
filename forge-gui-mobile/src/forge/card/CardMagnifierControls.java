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
package forge.card;

import com.badlogic.gdx.Input;
import forge.Forge;
import forge.Graphics;
import forge.assets.FSkin;
import forge.assets.FSkinFont;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Utils;

/**
 * Desktop hover-preview / duel magnifier controls (DS3).
 *
 * <p>Right-click opens {@link CardZoom} via the normal tap path and must never
 * toggle the magnifier. {@code M} toggles hover preview on/off; {@code Shift+M}
 * toggles details. Choices persist in preferences (local only — no network).
 */
public final class CardMagnifierControls {
    public static final String HUD_NOTE = "Hover preview on/off";
    private static final float HUD_NOTE_SEC = 1.6f;

    private static String hudNote;
    private static long hudNoteUntilMs;

    private CardMagnifierControls() {
    }

    /** Load persisted magnifier flags into {@link Forge} statics (local prefs only). */
    public static void loadFromPreferences() {
        if (FModel.getPreferences() == null) {
            return;
        }
        Forge.magnifyToggle = FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_TOGGLE);
        Forge.magnifyShowDetails = FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_SHOW_DETAILS);
        Forge.magnify = Forge.magnifyToggle;
        applyCursor();
    }

    /**
     * Handle {@code M} / {@code Shift+M} for magnifier toggles.
     * @return true if consumed
     */
    public static boolean handleKeyDown(final int keyCode) {
        if (keyCode != Input.Keys.M) {
            return false;
        }
        if (GuiBase.getInterface() == null || !GuiBase.getInterface().isRunningOnDesktop()) {
            return false;
        }
        if (FModel.getPreferences() == null
                || !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_MAGNIFIER)) {
            return false;
        }
        if (Forge.KeyInputAdapter.isShiftKeyDown()) {
            toggleShowDetails();
        } else {
            toggleMagnifier();
        }
        return true;
    }

    /** Toggle hover preview on/off, persist, update cursor, show HUD note. */
    public static void toggleMagnifier() {
        Forge.magnifyToggle = !Forge.magnifyToggle;
        Forge.magnify = Forge.magnifyToggle;
        persist(FPref.UI_MAGNIFIER_TOGGLE, Forge.magnifyToggle);
        applyCursor();
        showHudNote(HUD_NOTE);
    }

    /** Toggle magnifier details mode, persist, show HUD note. Never via right-click. */
    public static void toggleShowDetails() {
        Forge.magnifyShowDetails = !Forge.magnifyShowDetails;
        persist(FPref.UI_MAGNIFIER_SHOW_DETAILS, Forge.magnifyShowDetails);
        showHudNote(HUD_NOTE);
    }

    public static void showHudNote(final String note) {
        hudNote = note;
        hudNoteUntilMs = System.currentTimeMillis() + (long) (HUD_NOTE_SEC * 1000);
    }

    /** @return active HUD note, or null if none / expired */
    public static String getHudNote() {
        if (hudNote == null) {
            return null;
        }
        if (System.currentTimeMillis() > hudNoteUntilMs) {
            hudNote = null;
            return null;
        }
        return hudNote;
    }

    /** Clear HUD note (tests). */
    public static void clearHudNote() {
        hudNote = null;
        hudNoteUntilMs = 0;
    }

    public static void drawHudNote(final Graphics g, final float screenW, final float screenH) {
        final String note = getHudNote();
        if (note == null || g == null) {
            return;
        }
        final FSkinFont font = FSkinFont.get(14);
        final float pad = Utils.scale(10);
        final float tw = font.getBounds(note).width + pad * 2;
        final float th = font.getLineHeight() + pad;
        final float x = (screenW - tw) / 2f;
        final float y = screenH * 0.08f;
        g.fillRect(new com.badlogic.gdx.graphics.Color(0.08f, 0.08f, 0.1f, 0.82f), x, y, tw, th);
        g.drawText(note, font, com.badlogic.gdx.graphics.Color.WHITE, x, y, tw, th, false,
                com.badlogic.gdx.utils.Align.center, true);
    }

    private static void persist(final FPref pref, final boolean value) {
        if (FModel.getPreferences() == null) {
            return;
        }
        FModel.getPreferences().setPref(pref, value);
        FModel.getPreferences().save();
    }

    private static void applyCursor() {
        try {
            if (GuiBase.isMobile() || FSkin.getCursor() == null || FSkin.getCursor().isEmpty()) {
                return;
            }
            if (FModel.getPreferences() == null
                    || !FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_MAGNIFIER)) {
                return;
            }
            if (Forge.magnifyToggle) {
                Forge.setCursor(FSkin.getCursor().get(1), "1");
            } else {
                Forge.setCursor(FSkin.getCursor().get(2), "2");
            }
        } catch (Throwable ignored) {
            // Skin / native GL not ready (headless tests — UnsatisfiedLinkError).
        }
    }
}
