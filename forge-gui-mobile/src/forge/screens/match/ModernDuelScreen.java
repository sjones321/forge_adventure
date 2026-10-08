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
package forge.screens.match;

import forge.Forge;
import forge.adventure.util.Config;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * DS1 gate for the modern libGDX duel screen (drag cast/attack, peek hand,
 * floating mana, Neo-style arrows). Stock Forge stays unchanged unless the
 * preference is {@link #MODE_ALWAYS}. {@link #MODE_AUTO} is per-match: on only
 * for Adventure duels while Ascendant is active.
 */
public final class ModernDuelScreen {
    public static final String MODE_AUTO = "Auto";
    public static final String MODE_ALWAYS = "Always";
    public static final String MODE_NEVER = "Never";

    /** ~9px as in Neo Forge {@code TableScreen.DRAG_SLOP}. */
    public static final float DRAG_SLOP_PX = 9f;

    private static Boolean cachedEnabled;
    private static String cachedMode;
    private static boolean cachedAdventureAscendant;

    private ModernDuelScreen() {
    }

    /**
     * Whether the modern duel screen is active for the current match.
     * Result is cached (mode + adventure/ascendant); call {@link #invalidate()}
     * when the preference changes or a match starts.
     */
    public static boolean enabled() {
        final String mode = FModel.getPreferences() == null
                ? MODE_AUTO
                : FModel.getPreferences().getPref(FPref.UI_MODERN_DUEL_SCREEN);
        final boolean adventureAscendant = Forge.isMobileAdventureMode && Config.ascendant();
        if (cachedEnabled != null && modeEquals(cachedMode, mode)
                && cachedAdventureAscendant == adventureAscendant) {
            return cachedEnabled.booleanValue();
        }
        final boolean on = resolve(mode, adventureAscendant);
        cachedMode = mode;
        cachedAdventureAscendant = adventureAscendant;
        cachedEnabled = Boolean.valueOf(on);
        return on;
    }

    /**
     * Pure preference resolution for tests and callers that already know whether
     * this match is an Adventure duel under Ascendant.
     *
     * @param adventureAscendantDuel {@code Forge.isMobileAdventureMode && Config.ascendant()}
     *                               for the match being evaluated — not merely that the
     *                               saved plane is Ascendant while in constructed/quest/draft
     */
    public static boolean resolve(final String mode, final boolean adventureAscendantDuel) {
        if (mode != null) {
            if (MODE_ALWAYS.equalsIgnoreCase(mode.trim())) {
                return true;
            }
            if (MODE_NEVER.equalsIgnoreCase(mode.trim())) {
                return false;
            }
        }
        // Auto (default / null / unknown): Adventure + Ascendant only.
        return adventureAscendantDuel;
    }

    /** Drop the enabled cache (preference change or match start). */
    public static void invalidate() {
        cachedEnabled = null;
        cachedMode = null;
    }

    private static boolean modeEquals(final String a, final String b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
