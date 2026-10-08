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

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * Pure drop classification for DS1 drag-to-cast / attack / block, adapted from
 * Neo Forge {@code NeoMatchUI.onCardDropped} behaviour (not a line copy).
 * All game effects still go through {@code IGameController.selectCard/selectPlayer}.
 * <p>Combat prompt helpers avoid forcing {@link PhaseType} class-init in headless
 * tests — pass {@link CombatPrompt} directly from tests.
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

    /** Which declare-combat prompt (if any) is active for drag attack/block. */
    public enum CombatPrompt {
        NONE,
        DECLARE_ATTACKERS,
        DECLARE_BLOCKERS
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

    public static CombatPrompt combatPrompt(final PhaseType phase, final boolean selecting) {
        if (selecting || phase == null) {
            return CombatPrompt.NONE;
        }
        if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS) {
            return CombatPrompt.DECLARE_ATTACKERS;
        }
        if (phase == PhaseType.COMBAT_DECLARE_BLOCKERS) {
            return CombatPrompt.DECLARE_BLOCKERS;
        }
        return CombatPrompt.NONE;
    }

    /**
     * Whether controller A / touch should pick the card up for a drag action.
     * Hand: cast or reorder when not in a selection prompt. Battlefield: only
     * local creatures during declare-attackers / declare-blockers.
     */
    public static boolean canPickup(final boolean fromHand, final boolean localCreature,
                                    final CombatPrompt combat, final boolean selecting) {
        if (selecting) {
            return false;
        }
        if (fromHand) {
            return true;
        }
        return localCreature && combat != CombatPrompt.NONE;
    }

    /**
     * True when dropping {@code source} on {@code target} would toggle an
     * existing attacker off (same defender already declared).
     */
    public static boolean wouldToggleOffAttacker(final CardView source, final Object target,
                                                 final CombatView combat) {
        if (combat == null || source == null || target == null) {
            return false;
        }
        if (!combat.isAttacking(source)) {
            return false;
        }
        final GameEntityView defender = combat.getDefender(source);
        return defender != null && defender.equals(target);
    }

    /**
     * @param localCreature true when {@code source} is a creature the local player controls
     *                      (callers compute this; keeps headless tests free of CardType setup)
     */
    public static Decision decide(final CardView source, final boolean fromHand,
                                  final Object target, final boolean overBoard, final boolean overHand,
                                  final CombatPrompt combat, final boolean selecting,
                                  final boolean localCreature) {
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

        final boolean attacking = localCreature && combat == CombatPrompt.DECLARE_ATTACKERS && !selecting;
        final boolean blocking = localCreature && combat == CombatPrompt.DECLARE_BLOCKERS && !selecting;

        if (!attacking && !blocking) {
            // Permanent drag outside combat / opponent creature does nothing.
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
        // Block: any battlefield card is accepted here; applyDrop verifies combat attackers.
        if (target instanceof CardView) {
            return new Decision(Kind.BLOCK, target);
        }
        return new Decision(Kind.NONE, null);
    }

    private static boolean isValidAttackTarget(final CardView source, final Object target) {
        if (target instanceof PlayerView p) {
            if (p.getHasLost()) {
                return false;
            }
            final PlayerView ctrl = source.getController();
            if (ctrl == null || ctrl.getId() == p.getId()) {
                return false;
            }
            // Prefer tracked opponents; if the view has none yet, any other player is fine.
            if (ctrl.getOpponents() != null && !ctrl.getOpponents().isEmpty()) {
                return ctrl.isOpponentOf(p);
            }
            return true;
        }
        if (target instanceof CardView c) {
            if (c.getZone() != ZoneType.Battlefield || c.getCurrentState() == null) {
                return false;
            }
            if (c.getCurrentState().isBattle()) {
                return true;
            }
            if (!c.getCurrentState().isPlaneswalker() || c.getController() == null) {
                return false;
            }
            final PlayerView ctrl = source.getController();
            if (ctrl == null || ctrl.getId() == c.getController().getId()) {
                return false;
            }
            if (ctrl.getOpponents() != null && !ctrl.getOpponents().isEmpty()) {
                return ctrl.isOpponentOf(c.getController());
            }
            return true;
        }
        return false;
    }
}
