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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

import forge.Graphics;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.views.VCardDisplayArea.CardAreaPanel;
import forge.screens.match.views.VFloatingMana;
import forge.screens.match.views.VPhaseIndicator;
import forge.screens.match.views.VPlayerPanel;
import forge.screens.match.views.VPrompt;
import forge.toolbox.FCardPanel;
import forge.toolbox.FDisplayObject;
import forge.util.ThreadUtil;
import forge.util.Utils;
import forge.util.collect.FCollectionView;

/**
 * Runtime state for DS1 gestures: press-to-peek hand, drag-to-cast/attack/block,
 * hand reorder, drag arrow, and full controller paths. Behaviour studied from
 * Neo Forge {@code TableScreen.installDragGestures} / {@code NeoMatchUI.onCardDropped}.
 */
public final class ModernDuelController {
    private static final ModernDuelController INSTANCE = new ModernDuelController();

    /** Cached Neo-style drag / targeting colours (avoid Color.valueOf every frame). */
    private static final Color ARROW_VALID = Color.valueOf("4FB477");
    private static final Color ARROW_AIM = Color.valueOf("4A9BE0");
    private static final Color ARROW_TARGET = Color.valueOf("E0A63C");

    private CardView dragSource;
    private boolean dragFromHand;
    private boolean dragActive;
    private float pressScreenX;
    private float pressScreenY;
    private float dragScreenX;
    private float dragScreenY;
    private boolean dragValid;

    private CardView peekCard;
    private Rectangle peekBounds = new Rectangle();

    /** Controller pick-up: held card waiting for a second A to drop. */
    private CardView heldCard;
    private boolean heldFromHand;

    private ModernDuelPad.Focus padFocus = ModernDuelPad.Focus.NONE;
    private int manaFocusIndex;
    private int phaseFocusIndex;

    private boolean swallowNextTap;
    /** Once touch is used, ignore leftover gamepad focus for targeting arrows. */
    private boolean touchInputActive;

    public static ModernDuelController get() {
        return INSTANCE;
    }

    private ModernDuelController() {
    }

    public void reset() {
        clearDrag();
        hidePeek();
        clearPadFocus();
        heldCard = null;
        heldFromHand = false;
        swallowNextTap = false;
        touchInputActive = false;
    }

    public boolean isBusy() {
        return dragActive || peekCard != null || heldCard != null
                || padFocus != ModernDuelPad.Focus.NONE;
    }

    /** True only while a drag-to-cast/attack gesture is active (not peek alone). */
    public boolean isDragActive() {
        return dragActive;
    }

    public boolean isPeeking() {
        return peekCard != null;
    }

    /** True after touch input; pad focus arrow must not be drawn. */
    public boolean isTouchInputActive() {
        return touchInputActive;
    }

    public boolean shouldDrawPadFocusArrow() {
        return ModernDuelGestures.shouldDrawPadFocusArrow(touchInputActive);
    }

    public ModernDuelPad.Focus getPadFocus() {
        return padFocus;
    }

    /** Mark that touch is driving input (clears stale pad focus for arrows). */
    public void markTouchInput() {
        touchInputActive = true;
        if (padFocus != ModernDuelPad.Focus.NONE) {
            clearPadFocus();
        }
    }

    private void noteTouchInput() {
        markTouchInput();
    }

    public boolean shouldSwallowTap() {
        if (swallowNextTap) {
            swallowNextTap = false;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ press / pan / release from CardAreaPanel
    public boolean onCardPress(final CardAreaPanel panel, final float screenX, final float screenY) {
        if (!ModernDuelScreen.enabled() || panel == null || panel.getCard() == null) {
            return false;
        }
        noteTouchInput();
        final CardView card = panel.getCard();
        pressScreenX = screenX;
        pressScreenY = screenY;
        dragScreenX = screenX;
        dragScreenY = screenY;
        dragActive = false;
        dragValid = false;
        swallowNextTap = false;

        final boolean fromHand = card.getZone() == ZoneType.Hand;
        // Peek is long-press only — plain press must not swallow hand scrolling.
        if (fromHand && ModernDuelGestures.shouldPeekOnPress()) {
            showPeek(panel);
            dragSource = null;
            dragFromHand = false;
            Gdx.graphics.requestRendering();
            return true;
        }

        if (!canTouchDragCard(card)) {
            clearDrag();
            return false;
        }
        dragSource = card;
        dragFromHand = fromHand;
        Gdx.graphics.requestRendering();
        return false;
    }

    public boolean onCardLongPress(final CardAreaPanel panel) {
        if (!ModernDuelScreen.enabled() || panel == null || panel.getCard() == null) {
            return false;
        }
        noteTouchInput();
        final CardView card = panel.getCard();
        final boolean fromHand = card.getZone() == ZoneType.Hand;
        if (ModernDuelGestures.shouldPeekOnLongPress(fromHand, MatchController.instance.isSelecting())) {
            showPeek(panel);
            Gdx.graphics.requestRendering();
            return true;
        }
        return false;
    }

    public boolean onCardPan(final CardAreaPanel panel, final float screenX, final float screenY) {
        if (!ModernDuelScreen.enabled()) {
            return false;
        }
        noteTouchInput();
        if (peekCard != null) {
            if (!isOverBoard(screenX, screenY)) {
                final CardAreaPanel under = handCardAt(screenX);
                if (under != null && under.getCard() != null && under.getCard() != peekCard) {
                    showPeek(under);
                }
                Gdx.graphics.requestRendering();
                return true;
            }
            // Lifted onto the board: convert peek into a drag of the shown card.
            if (!canTouchDragCard(peekCard)) {
                hidePeek();
                clearDrag();
                return false;
            }
            dragSource = peekCard;
            dragFromHand = true;
            hidePeek();
            dragActive = true;
        }

        final float dx = screenX - pressScreenX;
        final float dy = screenY - pressScreenY;
        final boolean overBoard = isOverBoard(screenX, screenY);
        if (dragFromHand || (panel != null && panel.getCard() != null
                && panel.getCard().getZone() == ZoneType.Hand)) {
            if (!ModernDuelGestures.shouldConsumeHandPan(peekCard != null, dragActive, dx, dy, overBoard)) {
                // Horizontal pan in hand → scroll.
                return false;
            }
        }

        if (dragSource == null && panel != null && panel.getCard() != null) {
            if (!canTouchDragCard(panel.getCard())) {
                return false;
            }
            dragSource = panel.getCard();
            dragFromHand = dragSource.getZone() == ZoneType.Hand;
        }
        if (dragSource == null) {
            return false;
        }
        if (!dragActive) {
            if (Math.hypot(dx, dy) < ModernDuelScreen.DRAG_SLOP_PX) {
                return false;
            }
            dragActive = true;
        }
        dragScreenX = screenX;
        dragScreenY = screenY;
        final Object target = entityAt(screenX, screenY);
        final boolean cancelling = dragFromHand && !overBoard;
        dragValid = !cancelling && target != null;
        Gdx.graphics.requestRendering();
        return true;
    }

    public boolean onCardPanStop(final float screenX, final float screenY) {
        if (!ModernDuelScreen.enabled()) {
            return false;
        }
        if (peekCard != null) {
            // Release in hand: peek only (tap may still select via normal path).
            hidePeek();
            clearDrag();
            Gdx.graphics.requestRendering();
            return true;
        }
        if (!dragActive || dragSource == null) {
            clearDrag();
            return false;
        }
        final CardView source = dragSource;
        final boolean fromHand = dragFromHand;
        final Object target = entityAt(screenX, screenY);
        final boolean overBoard = isOverBoard(screenX, screenY);
        final boolean overHand = isOverHand(screenX, screenY);
        clearDrag();
        swallowNextTap = true;
        applyDrop(source, fromHand, target, overBoard, overHand, screenX);
        Gdx.graphics.requestRendering();
        return true;
    }

    public boolean onCardRelease() {
        if (peekCard != null && !dragActive) {
            hidePeek();
            Gdx.graphics.requestRendering();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ controller pad routing
    /**
     * Handle a gamepad key for modern duel modes. Returns true if consumed.
     * Call before stock match bindings for X / directional keys while in a mode.
     */
    public boolean handlePadKey(final int keyCode, final MatchScreen screen,
                                final CardView focusedCard, final PlayerView focusedPlayer) {
        if (!ModernDuelScreen.enabled() || screen == null) {
            return false;
        }
        // Gamepad resumes pad-focus arrows after touch.
        touchInputActive = false;
        switch (keyCode) {
            case Keys.BUTTON_X:
                return onPadX(focusedCard);
            case Keys.BUTTON_B:
                return controllerCancel();
            case Keys.DPAD_LEFT:
                return onPadDpad(-1, 0, screen, focusedCard);
            case Keys.DPAD_RIGHT:
                return onPadDpad(1, 0, screen, focusedCard);
            case Keys.DPAD_UP:
                return onPadDpad(0, -1, screen, focusedCard);
            case Keys.DPAD_DOWN:
                return onPadDpad(0, 1, screen, focusedCard);
            case Keys.BUTTON_A:
                return onPadA(focusedCard, focusedPlayer);
            default:
                return false;
        }
    }

    private boolean onPadX(final CardView focusedCard) {
        if (padFocus == ModernDuelPad.Focus.PEEK || peekCard != null) {
            hidePeek();
            padFocus = ModernDuelPad.Focus.NONE;
            Gdx.graphics.requestRendering();
            return true;
        }
        if (padFocus == ModernDuelPad.Focus.MANA || padFocus == ModernDuelPad.Focus.PHASE) {
            clearPadFocus();
            Gdx.graphics.requestRendering();
            return true;
        }
        final boolean handFocused = focusedCard != null && focusedCard.getZone() == ZoneType.Hand;
        final VFloatingMana mana = localFloatingMana();
        final boolean manaAvailable = mana != null && mana.hasManaAvailable();
        final ModernDuelPad.Focus next = ModernDuelPad.chooseXTarget(handFocused, manaAvailable);
        switch (next) {
            case PEEK -> {
                if (focusedCard == null) {
                    return false;
                }
                ensureHandTab();
                showPeek(CardAreaPanel.get(focusedCard));
                padFocus = ModernDuelPad.Focus.PEEK;
            }
            case MANA -> {
                manaFocusIndex = 0;
                padFocus = ModernDuelPad.Focus.MANA;
                if (mana != null) {
                    mana.setFocusedIndex(manaFocusIndex);
                }
            }
            case PHASE -> {
                phaseFocusIndex = 0;
                padFocus = ModernDuelPad.Focus.PHASE;
                final VPhaseIndicator pi = localPhaseIndicator();
                if (pi != null) {
                    pi.setPadFocusIndex(phaseFocusIndex);
                }
            }
            default -> {
                return false;
            }
        }
        Gdx.graphics.requestRendering();
        return true;
    }

    private boolean onPadDpad(final int dx, final int dy, final MatchScreen screen,
                             final CardView focusedCard) {
        if (padFocus == ModernDuelPad.Focus.PEEK || peekCard != null) {
            if (dy < 0) {
                // Lift peeked card onto the table (held) — same as touch push-up.
                final CardView card = peekCard;
                hidePeek();
                padFocus = ModernDuelPad.Focus.NONE;
                if (card != null) {
                    heldCard = card;
                    heldFromHand = true;
                }
                Gdx.graphics.requestRendering();
                return true;
            }
            if (dx != 0) {
                return peekMove(dx);
            }
            return true; // swallow down while peeking
        }
        if (padFocus == ModernDuelPad.Focus.MANA && dx != 0) {
            final VFloatingMana mana = localFloatingMana();
            if (mana == null) {
                return false;
            }
            manaFocusIndex = ModernDuelPad.cycle(manaFocusIndex, mana.getPipCount(), dx);
            mana.setFocusedIndex(manaFocusIndex);
            Gdx.graphics.requestRendering();
            return true;
        }
        if (padFocus == ModernDuelPad.Focus.PHASE && dx != 0) {
            final VPhaseIndicator pi = localPhaseIndicator();
            if (pi == null) {
                return false;
            }
            phaseFocusIndex = ModernDuelPad.cycle(phaseFocusIndex, VPhaseIndicator.PHASE_ORDER.length, dx);
            pi.setPadFocusIndex(phaseFocusIndex);
            Gdx.graphics.requestRendering();
            return true;
        }
        // While holding a hand card, L/R still moves the normal focus cursor (fall through).
        return false;
    }

    /**
     * @return true if A was fully handled (caller should not fall through to tapChild);
     *         false if stock confirm/select should run (e.g. targeting).
     */
    private boolean onPadA(final CardView focusedCard, final PlayerView focusedPlayer) {
        if (padFocus == ModernDuelPad.Focus.MANA) {
            final VFloatingMana mana = localFloatingMana();
            if (mana != null && mana.activateFocused()) {
                Gdx.graphics.requestRendering();
                return true;
            }
            return true;
        }
        if (padFocus == ModernDuelPad.Focus.PHASE) {
            final VPhaseIndicator pi = localPhaseIndicator();
            if (pi != null) {
                pi.togglePadFocusedStop();
                MatchController.writeMatchPreferences();
            }
            Gdx.graphics.requestRendering();
            return true;
        }
        if (padFocus == ModernDuelPad.Focus.PEEK || peekCard != null) {
            final CardView card = peekCard != null ? peekCard : focusedCard;
            hidePeek();
            padFocus = ModernDuelPad.Focus.NONE;
            if (MatchController.instance.isSelecting()) {
                // Engine is asking for a choice from hand — select the peeked card.
                if (card != null) {
                    final IGameController c = MatchController.instance.getGameController();
                    if (c != null) {
                        ThreadUtil.invokeInGameThread(() -> c.selectCard(card, null, null));
                    }
                }
                return true;
            }
            if (card != null) {
                heldCard = card;
                heldFromHand = true;
                Gdx.graphics.requestRendering();
                return true;
            }
            return true;
        }
        // Targeting: let stock tapChild select; we only draw the arrow.
        if (MatchController.instance.isSelecting() && heldCard == null) {
            return false;
        }
        if (heldCard != null && focusedPlayer != null && controllerDropOnPlayer(focusedPlayer)) {
            return true;
        }
        if (controllerPickOrDrop(focusedCard)) {
            return true;
        }
        return false;
    }

    private boolean peekMove(final int dx) {
        final VPlayerPanel local = localPanel();
        if (local == null || local.getZoneDisplay(ZoneType.Hand) == null || peekCard == null) {
            return false;
        }
        ensureHandTab();
        final java.util.List<CardAreaPanel> panels = new java.util.ArrayList<>();
        for (final CardAreaPanel p : local.getZoneDisplay(ZoneType.Hand).getCardPanels()) {
            panels.add(p);
        }
        if (panels.isEmpty()) {
            return false;
        }
        int idx = 0;
        for (int i = 0; i < panels.size(); i++) {
            if (panels.get(i).getCard() != null && panels.get(i).getCard().getId() == peekCard.getId()) {
                idx = i;
                break;
            }
        }
        idx = ModernDuelPad.cycle(idx, panels.size(), dx);
        final CardAreaPanel next = panels.get(idx);
        showPeek(next);
        // Keep the hand tab selection in sync for A / further DPAD.
        local.getZoneDisplay(ZoneType.Hand).selectChildAt(idx);
        padFocus = ModernDuelPad.Focus.PEEK;
        Gdx.graphics.requestRendering();
        return true;
    }

    // ------------------------------------------------------------------ controller pick-up / drop
    public boolean controllerPickOrDrop(final CardView focused) {
        if (!ModernDuelScreen.enabled()) {
            return false;
        }
        if (heldCard != null) {
            final CardView source = heldCard;
            final boolean fromHand = heldFromHand;
            heldCard = null;
            heldFromHand = false;
            if (focused == null) {
                Gdx.graphics.requestRendering();
                return true;
            }
            // Hand → hand: reorder at the focused card's index (multi-step OK).
            if (fromHand && focused.getZone() == ZoneType.Hand) {
                final int heldIdx = handIndexOf(source);
                final int targetIdx = handIndexOf(focused);
                final int handSize = handSize();
                final int index = ModernDuelPad.reorderIndex(heldIdx, targetIdx, handSize);
                if (index >= 0 && !FModel.getPreferences().getPrefBoolean(FPref.UI_ORDER_HAND)) {
                    final IGameController controller = MatchController.instance.getGameController();
                    if (controller != null) {
                        ThreadUtil.invokeInGameThread(() -> controller.reorderHand(source, index));
                    }
                }
                Gdx.graphics.requestRendering();
                return true;
            }
            final boolean overBoard = focused.getZone() == ZoneType.Battlefield
                    || focused.getZone() == ZoneType.Command;
            final boolean overHand = focused.getZone() == ZoneType.Hand && fromHand;
            applyDrop(source, fromHand, focused, overBoard && !overHand, overHand, -1);
            Gdx.graphics.requestRendering();
            return true;
        }
        if (focused == null) {
            return false;
        }
        // Stock tap/activate (mana, abilities, loyalty, uncastable hand) when no drag.
        if (!canControllerPickupCard(focused)) {
            return false;
        }
        heldCard = focused;
        heldFromHand = focused.getZone() == ZoneType.Hand;
        Gdx.graphics.requestRendering();
        return true;
    }

    public boolean controllerDropOnPlayer(final PlayerView player) {
        if (!ModernDuelScreen.enabled() || heldCard == null || player == null) {
            return false;
        }
        final CardView source = heldCard;
        final boolean fromHand = heldFromHand;
        heldCard = null;
        heldFromHand = false;
        applyDrop(source, fromHand, player, true, false, -1);
        Gdx.graphics.requestRendering();
        return true;
    }

    public boolean controllerCancel() {
        if (!ModernDuelScreen.enabled()) {
            return false;
        }
        if (heldCard != null || dragActive || peekCard != null
                || padFocus != ModernDuelPad.Focus.NONE) {
            heldCard = null;
            heldFromHand = false;
            hidePeek();
            clearDrag();
            clearPadFocus();
            Gdx.graphics.requestRendering();
            return true;
        }
        return false;
    }

    public CardView getHeldCard() {
        return heldCard;
    }

    // ------------------------------------------------------------------ draw
    public void drawOverlay(final Graphics g) {
        if (!ModernDuelScreen.enabled()) {
            return;
        }
        if (peekCard != null && peekBounds.width > 0) {
            CardRenderer.drawCard(g, peekCard, peekBounds.x, peekBounds.y,
                    peekBounds.width, peekBounds.height, CardStackPosition.Top, false);
            g.drawRect(Utils.scale(2), Color.WHITE, peekBounds.x, peekBounds.y,
                    peekBounds.width, peekBounds.height);
        }
        if (dragActive && dragSource != null) {
            final Vector2 origin = arrowOriginFor(dragSource);
            if (origin != null) {
                final Color c = dragValid ? ARROW_VALID : ARROW_AIM;
                g.drawCurvedArrow(Utils.scale(3), c, Color.WHITE,
                        origin.x, origin.y, dragScreenX, dragScreenY, true);
            }
        }
        // Held card or targeting: amber arrow from the actual source to pad focus.
        // Touch users must not see leftover gamepad focus arrows.
        if (!shouldDrawPadFocusArrow()) {
            return;
        }
        final Vector2 targetEnd = focusArrowEnd();
        Vector2 origin = null;
        if (heldCard != null) {
            origin = arrowOriginFor(heldCard);
        } else if (MatchController.instance.isSelecting()) {
            origin = selectionArrowOrigin();
        }
        if (origin != null && targetEnd != null) {
            g.drawCurvedArrow(Utils.scale(3), ARROW_TARGET, Color.WHITE,
                    origin.x, origin.y, targetEnd.x, targetEnd.y, true);
        }
    }

    // ------------------------------------------------------------------ drop apply
    private void applyDrop(final CardView source, final boolean fromHand, final Object target,
                           final boolean overBoard, final boolean overHand, final float screenX) {
        final GameView gv = MatchController.instance.getGameView();
        final boolean selecting = MatchController.instance.isSelecting();
        final ModernDuelActions.CombatPrompt combat = currentCombatPrompt(selecting);
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        final boolean localCreature = isLocalCreature(source, local);
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                source, fromHand, target, overBoard, overHand, combat, selecting, localCreature);

        final IGameController controller = MatchController.instance.getGameController();
        if (controller == null) {
            return;
        }

        switch (d.kind) {
            case CAST -> ThreadUtil.invokeInGameThread(() ->
                    controller.selectCard(source, null, null));
            case REORDER -> {
                if (FModel.getPreferences().getPrefBoolean(FPref.UI_ORDER_HAND)) {
                    return;
                }
                final int index = handDropIndex(source, screenX);
                if (index >= 0) {
                    final int idx = index;
                    ThreadUtil.invokeInGameThread(() -> controller.reorderHand(source, idx));
                }
            }
            case ATTACK -> {
                final CombatView cv = gv == null ? null : gv.getCombat();
                // Dropping on the same defender must never toggle an existing attacker off.
                if (ModernDuelActions.wouldToggleOffAttacker(source, d.target, cv)) {
                    return;
                }
                if (d.target instanceof PlayerView player) {
                    ThreadUtil.invokeInGameThread(() -> {
                        controller.selectPlayer(player, null);
                        controller.selectCard(source, null, null);
                    });
                } else if (d.target instanceof CardView card && card.getId() != source.getId()) {
                    ThreadUtil.invokeInGameThread(() -> {
                        controller.selectCard(card, null, null);
                        controller.selectCard(source, null, null);
                    });
                }
            }
            case BLOCK -> {
                if (d.target instanceof CardView attacker) {
                    final CombatView combatView = gv == null ? null : gv.getCombat();
                    if (combatView != null && !containsAttacker(combatView, attacker)) {
                        return;
                    }
                    // Dropping a blocker on an attacker it already blocks must not remove the block.
                    if (ModernDuelActions.wouldToggleOffBlocker(source, attacker, combatView)) {
                        return;
                    }
                    ThreadUtil.invokeInGameThread(() -> {
                        controller.selectCard(attacker, null, null);
                        controller.selectCard(source, null, null);
                    });
                }
            }
            case NONE -> {
                // no-op
            }
        }
    }

    private static boolean containsAttacker(final CombatView combat, final CardView card) {
        if (combat == null || card == null) {
            return false;
        }
        for (final CardView a : combat.getAttackers()) {
            if (a != null && a.getId() == card.getId()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ geometry helpers
    private void showPeek(final CardAreaPanel over) {
        if (over == null || over.getCard() == null) {
            return;
        }
        peekCard = over.getCard();
        final MatchScreen screen = MatchController.getView();
        if (screen == null) {
            return;
        }
        final float contentW = screen.getWidth();
        final float handTop = over.screenPos.y;
        float pw = Math.min(contentW / 3f, Math.max(over.getWidth() * 2.2f, Utils.scale(180)));
        float ph = pw * FCardPanel.ASPECT_RATIO;
        if (ph > handTop - Utils.scale(8)) {
            ph = Math.max(over.getHeight(), handTop - Utils.scale(8));
            pw = ph / FCardPanel.ASPECT_RATIO;
        }
        final float centre = over.screenPos.x + over.getWidth() / 2f;
        final float x = Math.max(Utils.scale(4), Math.min(contentW - pw - Utils.scale(4), centre - pw / 2f));
        final float y = Math.max(Utils.scale(4), handTop - ph - Utils.scale(4));
        peekBounds.set(x, y, pw, ph);
    }

    private void hidePeek() {
        peekCard = null;
        peekBounds.set(0, 0, 0, 0);
    }

    private void clearDrag() {
        dragSource = null;
        dragFromHand = false;
        dragActive = false;
        dragValid = false;
    }

    private static boolean isOverBoard(final float screenX, final float screenY) {
        final MatchScreen screen = MatchController.getView();
        if (screen == null) {
            return false;
        }
        for (final VPlayerPanel panel : screen.getPlayerPanelsList()) {
            if (panel.getField() != null && panel.getField().screenPos.contains(screenX, screenY)) {
                return true;
            }
            if (panel.getAvatar() != null && panel.getAvatar().screenPos.contains(screenX, screenY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOverHand(final float screenX, final float screenY) {
        final VPlayerPanel local = localPanel();
        if (local == null || local.getZoneDisplay(ZoneType.Hand) == null) {
            return false;
        }
        return local.getZoneDisplay(ZoneType.Hand).screenPos.contains(screenX, screenY);
    }

    private static CardAreaPanel handCardAt(final float screenX) {
        final VPlayerPanel local = localPanel();
        if (local == null || local.getZoneDisplay(ZoneType.Hand) == null) {
            return null;
        }
        CardAreaPanel best = null;
        for (final CardAreaPanel p : local.getZoneDisplay(ZoneType.Hand).getCardPanels()) {
            if (p.screenPos.x <= screenX && screenX <= p.screenPos.x + p.getWidth()) {
                best = p; // rightmost overlapping wins (drawn on top)
            }
        }
        return best;
    }

    private int handDropIndex(final CardView source, final float screenX) {
        if (screenX < 0) {
            return -1;
        }
        final VPlayerPanel local = localPanel();
        if (local == null || local.getZoneDisplay(ZoneType.Hand) == null) {
            return -1;
        }
        int current = -1;
        int index = 0;
        int i = 0;
        for (final CardAreaPanel p : local.getZoneDisplay(ZoneType.Hand).getCardPanels()) {
            final CardView c = p.getCard();
            if (c != null && c.getId() == source.getId()) {
                current = i;
                i++;
                continue;
            }
            if (p.screenPos.x + p.getWidth() / 2f < screenX) {
                index++;
            }
            i++;
        }
        return current < 0 || index == current ? -1 : index;
    }

    private static Object entityAt(final float screenX, final float screenY) {
        final MatchScreen screen = MatchController.getView();
        if (screen == null) {
            return null;
        }
        CardAreaPanel bestCard = null;
        float bestArea = Float.MAX_VALUE;
        for (final VPlayerPanel panel : screen.getPlayerPanelsList()) {
            if (panel.getAvatar() != null && panel.getAvatar().screenPos.contains(screenX, screenY)) {
                return panel.getPlayer();
            }
            for (final FCardPanel fp : panel.getField().getCardPanels()) {
                if (!(fp instanceof CardAreaPanel p)) {
                    continue;
                }
                if (!p.screenPos.contains(screenX, screenY)) {
                    continue;
                }
                final float area = p.getWidth() * p.getHeight();
                if (area < bestArea) {
                    bestArea = area;
                    bestCard = p;
                }
            }
        }
        return bestCard == null ? null : bestCard.getCard();
    }

    private static Vector2 arrowOriginFor(final CardView card) {
        if (card == null) {
            return null;
        }
        return CardAreaPanel.get(card).getTargetingArrowOrigin();
    }

    private Vector2 focusArrowEnd() {
        final CardAreaPanel focusPanel = focusedPanel();
        if (focusPanel != null) {
            return focusPanel.getTargetingArrowOrigin();
        }
        try {
            final MatchScreen screen = MatchController.getView();
            if (screen == null) {
                return null;
            }
            final VPlayerPanel panel = screen.selectedPlayerPanel();
            if (panel != null && panel.getAvatar() != null
                    && (panel.getSelectedTab() == null || !panel.getSelectedTab().getDisplayArea().isVisible())) {
                // No card focused on this panel — aim at the player avatar (attack / player targets).
                if (panel.getSelectedRow().getSelectedChild() == null) {
                    return panel.getAvatar().getTargetingArrowOrigin();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Amber targeting arrow origin: only the prompt's actual source card
     * (avoids stray arrows when selecting without a card source).
     */
    private Vector2 selectionArrowOrigin() {
        final CardView promptCard = promptSourceCard();
        if (promptCard != null) {
            final Vector2 o = arrowOriginFor(promptCard);
            if (o != null) {
                return o;
            }
        }
        // Fallback: stack top only when it matches the prompt source (or prompt had no panel).
        final GameView gv = MatchController.instance.getGameView();
        if (gv == null || promptCard == null) {
            return null;
        }
        final FCollectionView<StackItemView> stack = gv.getStack();
        if (stack != null && !stack.isEmpty()) {
            final StackItemView top = stack.getLast();
            if (top != null && top.getSourceCard() != null
                    && top.getSourceCard().getId() == promptCard.getId()) {
                return arrowOriginFor(top.getSourceCard());
            }
        }
        return null;
    }

    private static CardView promptSourceCard() {
        final MatchScreen screen = MatchController.getView();
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        if (screen == null || local == null) {
            return null;
        }
        try {
            final VPrompt prompt = screen.getPrompt(local);
            return prompt == null ? null : prompt.getCardView();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ModernDuelActions.CombatPrompt currentCombatPrompt(final boolean selecting) {
        return ModernDuelActions.combatPrompt(
                selecting,
                MatchController.instance.isCombatDeclareAttackersInput(),
                MatchController.instance.isCombatDeclareBlockersInput());
    }

    private static boolean canControllerPickupCard(final CardView card) {
        if (card == null) {
            return false;
        }
        final boolean selecting = MatchController.instance.isSelecting();
        final ModernDuelActions.CombatPrompt combat = currentCombatPrompt(selecting);
        final boolean fromHand = card.getZone() == ZoneType.Hand;
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        final boolean localCreature = isLocalCreature(card, local);
        final boolean handCastable = fromHand && isHandCastable(card);
        return ModernDuelActions.canPickup(fromHand, localCreature, combat, selecting, handCastable);
    }

    private static boolean canTouchDragCard(final CardView card) {
        if (card == null) {
            return false;
        }
        final boolean selecting = MatchController.instance.isSelecting();
        final ModernDuelActions.CombatPrompt combat = currentCombatPrompt(selecting);
        final boolean fromHand = card.getZone() == ZoneType.Hand;
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        final boolean localCreature = isLocalCreature(card, local);
        return ModernDuelActions.canTouchDrag(fromHand, localCreature, combat, selecting);
    }

    private static boolean isHandCastable(final CardView card) {
        if (card == null || card.getZone() != ZoneType.Hand) {
            return false;
        }
        try {
            final IGameController c = MatchController.instance.getGameController();
            if (c == null) {
                return false;
            }
            final String desc = c.getActivateDescription(card);
            return desc != null && !desc.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLocalCreature(final CardView card, final PlayerView local) {
        if (card == null || local == null || card.getController() == null) {
            return false;
        }
        if (card.getZone() != ZoneType.Battlefield) {
            return false;
        }
        if (card.getController().getId() != local.getId()) {
            return false;
        }
        return card.getCurrentState() != null && card.getCurrentState().isCreature();
    }

    private static CardAreaPanel focusedPanel() {
        final MatchScreen screen = MatchController.getView();
        if (screen == null) {
            return null;
        }
        try {
            final VPlayerPanel panel = screen.selectedPlayerPanel();
            if (panel == null) {
                return null;
            }
            final VPlayerPanel.InfoTab tab = panel.getSelectedTab();
            if (tab != null && tab.getDisplayArea() != null && tab.getDisplayArea().isVisible()) {
                final FDisplayObject child = tab.getDisplayArea().getSelectedChild();
                if (child instanceof CardAreaPanel cap) {
                    return cap;
                }
            }
            final FDisplayObject child = panel.getSelectedRow().getSelectedChild();
            if (child instanceof CardAreaPanel cap) {
                return cap;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void clearPadFocus() {
        padFocus = ModernDuelPad.Focus.NONE;
        final VFloatingMana mana = localFloatingMana();
        if (mana != null) {
            mana.setFocusedIndex(-1);
        }
        final VPhaseIndicator pi = localPhaseIndicator();
        if (pi != null) {
            pi.setPadFocusIndex(-1);
        }
    }

    private static void ensureHandTab() {
        final VPlayerPanel local = localPanel();
        if (local == null) {
            return;
        }
        local.setSelectedZone(ZoneType.Hand);
    }

    private static int handIndexOf(final CardView card) {
        if (card == null) {
            return -1;
        }
        final VPlayerPanel local = localPanel();
        if (local == null || local.getZoneDisplay(ZoneType.Hand) == null) {
            return -1;
        }
        int i = 0;
        for (final CardAreaPanel p : local.getZoneDisplay(ZoneType.Hand).getCardPanels()) {
            if (p.getCard() != null && p.getCard().getId() == card.getId()) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static int handSize() {
        final VPlayerPanel local = localPanel();
        if (local == null || local.getZoneDisplay(ZoneType.Hand) == null) {
            return 0;
        }
        int n = 0;
        for (final CardAreaPanel ignored : local.getZoneDisplay(ZoneType.Hand).getCardPanels()) {
            n++;
        }
        return n;
    }

    private static VFloatingMana localFloatingMana() {
        final VPlayerPanel local = localPanel();
        return local == null ? null : local.getFloatingMana();
    }

    private static VPhaseIndicator localPhaseIndicator() {
        final VPlayerPanel local = localPanel();
        return local == null ? null : local.getPhaseIndicator();
    }

    private static VPlayerPanel localPanel() {
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        return local == null ? null : MatchScreen.getPlayerPanel(local);
    }
}
