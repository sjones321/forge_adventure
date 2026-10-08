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

import forge.adventure.util.Config;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

/**
 * DS1 gate for the modern libGDX duel screen (drag cast/attack, peek hand,
 * floating mana, Neo-style arrows). Stock Forge stays unchanged unless the
 * preference is {@link #MODE_ALWAYS}; Ascendant defaults on via {@link #MODE_AUTO}.
 */
public final class ModernDuelScreen {
    public static final String MODE_AUTO = "Auto";
    public static final String MODE_ALWAYS = "Always";
    public static final String MODE_NEVER = "Never";

    /** ~9px as in Neo Forge {@code TableScreen.DRAG_SLOP}. */
    public static final float DRAG_SLOP_PX = 9f;

    private ModernDuelScreen() {
    }

    public static boolean enabled() {
        final String mode = FModel.getPreferences() == null
                ? MODE_AUTO
                : FModel.getPreferences().getPref(FPref.UI_MODERN_DUEL_SCREEN);
        return resolve(mode, Config.ascendant());
    }

    /**
     * Pure preference resolution for tests and callers that already know
     * whether Ascendant rules are active.
     */
    public static boolean resolve(final String mode, final boolean ascendant) {
        if (mode != null) {
            if (MODE_ALWAYS.equalsIgnoreCase(mode.trim())) {
                return true;
            }
            if (MODE_NEVER.equalsIgnoreCase(mode.trim())) {
                return false;
            }
        }
        return ascendant;
    }
}
