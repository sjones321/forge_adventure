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

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollection;

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

    /**
     * Combat drag is gated on the active {@code InputAttack} / {@code InputBlock},
     * not merely the phase. Phase alone yields {@link CombatPrompt#NONE}.
     *
     * @param selecting            engine card-selection prompt (not combat declare)
     * @param inputAttackActive    {@link forge.gui.interfaces.IGuiGame#isCombatDeclareAttackersInput()}
     * @param inputBlockActive     {@link forge.gui.interfaces.IGuiGame#isCombatDeclareBlockersInput()}
     */
    public static CombatPrompt combatPrompt(final boolean selecting,
                                            final boolean inputAttackActive,
                                            final boolean inputBlockActive) {
        if (selecting) {
            return CombatPrompt.NONE;
        }
        if (inputAttackActive) {
            return CombatPrompt.DECLARE_ATTACKERS;
        }
        if (inputBlockActive) {
            return CombatPrompt.DECLARE_BLOCKERS;
        }
        return CombatPrompt.NONE;
    }

    /**
     * Whether controller A should pick the card up for a drag action.
     * Hand: only when a cast (or other hand drag) is actually possible.
     * Battlefield: only local creatures during declare-attackers / declare-blockers.
     */
    public static boolean canPickup(final boolean fromHand, final boolean localCreature,
                                    final CombatPrompt combat, final boolean selecting,
                                    final boolean handCastable) {
        if (selecting) {
            return false;
        }
        if (fromHand) {
            return handCastable;
        }
        return localCreature && combat != CombatPrompt.NONE;
    }

    /** Touch may start a hand drag for cast/reorder even when A would fall through. */
    public static boolean canTouchDrag(final boolean fromHand, final boolean localCreature,
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
     * View-only castability for controller A / pickup gating. Never calls
     * {@code IGameController} (guest {@code getActivateDescription} would block the UI thread).
     * Heuristic: lands when the player can still play a land; non-lands when total mana
     * in the pool is at least the card CMC. Engine still validates on cast.
     */
    public static boolean isHandCastableFromViews(final CardView card, final PlayerView controller) {
        if (card == null || card.getZone() != ZoneType.Hand) {
            return false;
        }
        final CardView.CardStateView state = card.getCurrentState();
        if (state == null) {
            return false;
        }
        if (state.isLand()) {
            return canPlayLandFromViews(controller);
        }
        final ManaCost cost = state.getManaCost();
        if (cost == null || cost.isNoCost()) {
            return false;
        }
        return estimateSpellCastable(cost.getCMC(), controller == null ? 0 : totalMana(controller));
    }

    /** Land playability from player views only (no network). */
    public static boolean canPlayLandFromViews(final PlayerView controller) {
        if (controller == null) {
            return false;
        }
        return controller.hasUnlimitedLandPlay()
                || controller.getNumLandThisTurn() < controller.getMaxLandPlay();
    }

    /** Spell affordability heuristic from CMC vs total mana pool. */
    public static boolean estimateSpellCastable(final int cmc, final int totalMana) {
        return cmc <= totalMana;
    }

    /** True when A on a castable hand card should cast immediately (no pick-up). */
    public static boolean isOnePressHandCast(final boolean fromHand, final boolean handCastable,
                                             final boolean alreadyHolding) {
        return fromHand && handCastable && !alreadyHolding;
    }

    private static int totalMana(final PlayerView player) {
        int total = 0;
        for (final byte c : ManaAtom.MANATYPES) {
            total += player.getMana(c);
        }
        return total;
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
     * True when dropping {@code source} on {@code target} would toggle an
     * existing block off (blocker already assigned to that attacker).
     */
    public static boolean wouldToggleOffBlocker(final CardView source, final Object target,
                                                final CombatView combat) {
        if (combat == null || source == null || !(target instanceof CardView attacker)) {
            return false;
        }
        if (!combat.isAttacking(attacker)) {
            return false;
        }
        final FCollection<CardView> planned = combat.getPlannedBlockers(attacker);
        if (planned != null && planned.contains(source)) {
            return true;
        }
        final FCollection<CardView> blockers = combat.getBlockers(attacker);
        return blockers != null && blockers.contains(source);
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
            return new Decision(Kind.CAST, target);
        }

        final boolean attacking = localCreature && combat == CombatPrompt.DECLARE_ATTACKERS && !selecting;
        final boolean blocking = localCreature && combat == CombatPrompt.DECLARE_BLOCKERS && !selecting;

        if (!attacking && !blocking) {
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
