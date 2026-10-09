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

/**
 * Pure touch/pad gesture policy for DS1 (headless-testable).
 */
public final class ModernDuelGestures {
    private ModernDuelGestures() {
    }

    /** Peek starts on long-press/hold only — never on a plain press. */
    public static boolean shouldPeekOnPress() {
        return false;
    }

    public static boolean shouldPeekOnLongPress(final boolean fromHand, final boolean selecting) {
        return fromHand && !selecting;
    }

    /**
     * Whether a hand pan should be consumed by modern drag/peek instead of scrolling.
     * Horizontal pans in the hand scroll; upward lifts onto the board may start a drag.
     */
    public static boolean shouldConsumeHandPan(final boolean peekActive, final boolean dragActive,
                                               final float dx, final float dy, final boolean overBoard) {
        if (peekActive || dragActive) {
            return true;
        }
        if (overBoard) {
            return true; // candidate cast drag (caller still checks canTouchDrag)
        }
        // Horizontal (or mostly horizontal) pan in the hand → scroll.
        return Math.abs(dy) > Math.abs(dx);
    }

    /**
     * Pad amber targeting arrow: only after real pad input, and never while touch is driving.
     * Prevents a stray amber arrow at match start before any input.
     */
    public static boolean shouldDrawPadFocusArrow(final boolean touchInputActive,
                                                  final boolean padInputSeen) {
        return !touchInputActive && padInputSeen;
    }

    /** Double-tap zooms a hand card (long-press peeks instead of stock zoom). */
    public static boolean shouldZoomOnDoubleTap(final boolean modernEnabled, final int tapCount) {
        return modernEnabled && tapCount > 1;
    }
}
