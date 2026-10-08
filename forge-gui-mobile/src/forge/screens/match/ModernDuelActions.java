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

import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * Pure drop classification for DS1 drag-to-cast / attack / block, adapted from
 * Neo Forge {@code NeoMatchUI.onCardDropped} behaviour (not a line copy).
 * All game effects still go through {@code IGameController.selectCard/selectPlayer}.
 */
public final class ModernDuelActions {
    public enum Kind {
        /** Drop from hand onto the board: cast / play. */
        CAST,
        /** Combat: declare attacker against player or PW/battle. */
        ATTACK,
        /** Combat: declare blocker on an attacker. */
        BLOCK,
        /** Drop within the hand: reorder. */
        REORDER,
        /** Cancel / no-op (including permanent dragged outside combat). */
        NONE
    }

    public static final class Decision {
        public final Kind kind;
        public final Object target;

        public Decision(final Kind kind, final Object target) {
            this.kind = kind;
            this.target = target;
        }
    }

    private ModernDuelActions() {
    }

    public static Decision decide(final CardView source, final boolean fromHand,
                                  final Object target, final boolean overBoard, final boolean overHand,
                                  final PhaseType phase, final boolean selecting) {
        if (source == null) {
            return new Decision(Kind.NONE, null);
        }
        if (fromHand) {
            if (overHand && !overBoard) {
                return new Decision(Kind.REORDER, target);
            }
            if (overBoard) {
                return new Decision(Kind.CAST, target);
            }
            return new Decision(Kind.NONE, null);
        }

        final boolean onBattlefield = source.getZone() == ZoneType.Battlefield;
        if (!onBattlefield) {
            // Command-zone / elsewhere: treat as a click (cast commander, etc.)
            return new Decision(Kind.CAST, target);
        }

        final boolean ownCreature = isOwnCreature(source);
        final boolean attacking = ownCreature && phase == PhaseType.COMBAT_DECLARE_ATTACKERS && !selecting;
        final boolean blocking = ownCreature && phase == PhaseType.COMBAT_DECLARE_BLOCKERS && !selecting;

        if (!attacking && !blocking) {
            // Permanent drag outside combat does nothing (Neo Forge principle).
            return new Decision(Kind.NONE, null);
        }
        if (target == null) {
            return new Decision(Kind.NONE, null);
        }
        if (attacking) {
            if (isValidAttackTarget(source, target)) {
                return new Decision(Kind.ATTACK, target);
            }
            return new Decision(Kind.NONE, null);
        }
        if (target instanceof CardView attacker && isAttacker(attacker)) {
            return new Decision(Kind.BLOCK, attacker);
        }
        return new Decision(Kind.NONE, null);
    }

    private static boolean isOwnCreature(final CardView source) {
        return source.getZone() == ZoneType.Battlefield
                && source.getCurrentState() != null
                && source.getCurrentState().isCreature();
    }

    private static boolean isValidAttackTarget(final CardView source, final Object target) {
        if (target instanceof PlayerView p) {
            return !p.getHasLost() && p.isOpponentOf(source.getController());
        }
        if (target instanceof CardView c) {
            if (c.getZone() != ZoneType.Battlefield || c.getCurrentState() == null) {
                return false;
            }
            if (c.getCurrentState().isBattle()) {
                return true;
            }
            return c.getCurrentState().isPlaneswalker()
                    && c.getController() != null
                    && c.getController().isOpponentOf(source.getController());
        }
        return false;
    }

    private static boolean isAttacker(final CardView c) {
        // Callers that have combat can refine; here accept any BF creature as a possible attacker.
        return c != null && c.getZone() == ZoneType.Battlefield
                && c.getCurrentState() != null && c.getCurrentState().isCreature();
    }
}
