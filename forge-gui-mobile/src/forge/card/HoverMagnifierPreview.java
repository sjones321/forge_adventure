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

import forge.util.CardRendererUtils;
import forge.game.card.CardView;

/**
 * DS3 hover-magnifier drawing policy (headless-testable).
 *
 * <p>The duel hover preview must never paint battlefield markers (counters, P/T
 * overlays, damage cracks) on top of the preview art. When details mode is off it
 * uses {@link CardRenderer#drawCard} with {@code magnify=true}; when details is on
 * it uses oracle text via {@link CardImageRenderer#drawDetails}.
 */
public final class HoverMagnifierPreview {
    public enum Style {
        /** Clean card image via {@code CardRenderer.drawCard(..., magnify=true)}. */
        CLEAN_IMAGE,
        /** Oracle / details panel — no battlefield marker overlays on art. */
        DETAILS_TEXT
    }

    private HoverMagnifierPreview() {
    }

    public static Style styleFor(final boolean magnifyShowDetails) {
        return magnifyShowDetails ? Style.DETAILS_TEXT : Style.CLEAN_IMAGE;
    }

    /** Battlefield markers must never be drawn onto the hover preview art. */
    public static boolean drawsBattlefieldMarkersOnPreview() {
        return false;
    }

    /**
     * Damage cracks are suppressed on magnify draws (clean art).
     * Callers pass a card that may have damage; result must still be false.
     */
    public static boolean drawsDamageCracksOnPreview(final CardView card) {
        return CardRendererUtils.drawCracks(card, true);
    }
}
