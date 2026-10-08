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
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;

import forge.Graphics;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.match.views.VCardDisplayArea.CardAreaPanel;
import forge.screens.match.views.VPlayerPanel;
import forge.toolbox.FCardPanel;
import forge.toolbox.FDisplayObject;
import forge.util.ThreadUtil;
import forge.util.Utils;

/**
 * Runtime state for DS1 gestures: press-to-peek hand, drag-to-cast/attack/block,
 * hand reorder, drag arrow, and controller pick-up / drop. Behaviour studied from
 * Neo Forge {@code TableScreen.installDragGestures} / {@code NeoMatchUI.onCardDropped}.
 */
public final class ModernDuelController {
    private static final ModernDuelController INSTANCE = new ModernDuelController();

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

    private boolean swallowNextTap;

    public static ModernDuelController get() {
        return INSTANCE;
    }

    private ModernDuelController() {
    }

    public void reset() {
        clearDrag();
        hidePeek();
        heldCard = null;
        heldFromHand = false;
        swallowNextTap = false;
    }

    public boolean isBusy() {
        return dragActive || peekCard != null || heldCard != null;
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
        final CardView card = panel.getCard();
        pressScreenX = screenX;
        pressScreenY = screenY;
        dragScreenX = screenX;
        dragScreenY = screenY;
        dragSource = card;
        dragFromHand = card.getZone() == ZoneType.Hand;
        dragActive = false;
        dragValid = false;
        swallowNextTap = false;

        if (dragFromHand && !MatchController.instance.isSelecting()) {
            showPeek(panel);
            // Peek owns the gesture until the finger leaves the hand strip upward.
            dragSource = null;
            dragFromHand = false;
        }
        Gdx.graphics.requestRendering();
        return peekCard != null;
    }

    public boolean onCardPan(final CardAreaPanel panel, final float screenX, final float screenY) {
        if (!ModernDuelScreen.enabled()) {
            return false;
        }
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
            dragSource = peekCard;
            dragFromHand = true;
            hidePeek();
            dragActive = true;
        }

        if (dragSource == null && panel != null) {
            dragSource = panel.getCard();
            dragFromHand = dragSource != null && dragSource.getZone() == ZoneType.Hand;
        }
        if (dragSource == null) {
            return false;
        }
        if (!dragActive) {
            if (Math.hypot(screenX - pressScreenX, screenY - pressScreenY) < ModernDuelScreen.DRAG_SLOP_PX) {
                return false;
            }
            dragActive = true;
        }
        dragScreenX = screenX;
        dragScreenY = screenY;
        final Object target = entityAt(screenX, screenY);
        final boolean cancelling = dragFromHand && !isOverBoard(screenX, screenY);
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
                return true; // cancel-ish drop with no target
            }
            final boolean overBoard = focused.getZone() == ZoneType.Battlefield
                    || focused.getZone() == ZoneType.Command;
            final boolean overHand = focused.getZone() == ZoneType.Hand && fromHand;
            Object target = focused;
            if (fromHand && overHand) {
                target = focused;
            } else if (fromHand) {
                // Dropping onto a field card / player avatar focus: cast.
                target = focused;
            }
            // Prefer player focus when attacking: callers pass player via selectPlayer path below.
            final VPlayerPanel pp = MatchScreen.getPlayerPanel(
                    MatchController.instance.getCurrentPlayer());
            final boolean board = overBoard || (pp != null && pp.getField() != null);
            applyDrop(source, fromHand, target, board && !overHand, overHand, -1);
            return true;
        }
        if (focused == null) {
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
        return true;
    }

    public boolean controllerCancel() {
        if (!ModernDuelScreen.enabled()) {
            return false;
        }
        if (heldCard != null || dragActive || peekCard != null) {
            heldCard = null;
            heldFromHand = false;
            hidePeek();
            clearDrag();
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
                final Color c = dragValid
                        ? Color.valueOf("4FB477")
                        : Color.valueOf("4A9BE0");
                g.drawCurvedArrow(Utils.scale(3), c, Color.WHITE,
                        origin.x, origin.y, dragScreenX, dragScreenY, true);
            }
        }
        if (heldCard != null) {
            final Vector2 origin = arrowOriginFor(heldCard);
            final CardAreaPanel focusPanel = focusedPanel();
            if (origin != null && focusPanel != null) {
                final Vector2 end = focusPanel.getTargetingArrowOrigin();
                if (end != null) {
                    g.drawCurvedArrow(Utils.scale(3), Color.valueOf("E0A63C"), Color.WHITE,
                            origin.x, origin.y, end.x, end.y, true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ drop apply
    private void applyDrop(final CardView source, final boolean fromHand, final Object target,
                           final boolean overBoard, final boolean overHand, final float screenX) {
        final GameView gv = MatchController.instance.getGameView();
        final PhaseType phase = gv == null ? null : gv.getPhase();
        final boolean selecting = MatchController.instance.isSelecting();
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                source, fromHand, target, overBoard, overHand, phase, selecting);

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
                    final CombatView combat = gv == null ? null : gv.getCombat();
                    if (combat != null && !containsAttacker(combat, attacker)) {
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

    private static VPlayerPanel localPanel() {
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        return local == null ? null : MatchScreen.getPlayerPanel(local);
    }
}
