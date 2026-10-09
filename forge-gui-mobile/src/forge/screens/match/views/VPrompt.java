package forge.screens.match.views;

import forge.Forge;
import org.apache.commons.lang3.StringUtils;

import com.badlogic.gdx.utils.Align;

import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinColor.Colors;
import forge.assets.FSkinFont;
import forge.assets.TextRenderer;
import forge.card.CardZoom;
import forge.game.card.CardView;
import forge.menu.FMagnifyView;
import forge.screens.match.TakeBackActions;
import forge.toolbox.FButton;
import forge.toolbox.FButton.Corner;
import forge.toolbox.FContainer;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FEvent.FEventHandler;
import forge.util.TextBounds;
import forge.util.Utils;

public class VPrompt extends FContainer {
    public static final float HEIGHT = Utils.AVG_FINGER_HEIGHT;
    public static final float BTN_WIDTH = HEIGHT * 1.5f;
    public static final float PADDING = Utils.scale(2);
    public static final FSkinFont FONT = FSkinFont.get(14);
    public static FSkinColor getBackColor() {
        if (Forge.isMobileAdventureMode)
            return FSkinColor.get(Colors.ADV_CLR_THEME2);
        return FSkinColor.get(Colors.CLR_THEME2);
    }
    public static FSkinColor getForeColor() {
        if (Forge.isMobileAdventureMode)
            return FSkinColor.get(Colors.ADV_CLR_TEXT);
        return FSkinColor.get(Colors.CLR_TEXT);
    }

    private final FButton btnOk, btnCancel, btnTakeBack;
    private final MessageLabel lblMessage;
    private String message;
    private CardView card;

    public void setCardView(final CardView card) {
        this.card = card;
    }

    /** Card associated with the current prompt (targeting / ability source), or null. */
    public CardView getCardView() {
        return card;
    }

    // Double-click guard (match prompts only): after a button press, further presses are ignored until
    // the next prompt has been showing for a moment, so a repeat click can't land on the next prompt
    // (e.g. "End Turn") while the game is still catching up.
    private static final long NEW_PROMPT_LOCKOUT_MS = 500;
    private static final long MAX_PRESS_LOCKOUT_MS = 1500;
    private final boolean guardDoubleClicks;
    private long ignorePressesUntil;

    public VPrompt(String okText, String cancelText, FEventHandler okCommand, FEventHandler cancelCommand) {
        this(okText, cancelText, okCommand, cancelCommand, false);
    }

    public VPrompt(String okText, String cancelText, FEventHandler okCommand, FEventHandler cancelCommand, boolean guardDoubleClicks0) {
        guardDoubleClicks = guardDoubleClicks0;
        lblMessage = add(new MessageLabel());
        lblMessage.setLeft(BTN_WIDTH);
        lblMessage.setHeight(HEIGHT);
        btnOk = add(new FButton(okText, guard(okCommand)));
        btnCancel = add(new FButton(cancelText, guard(cancelCommand)));
        // DS4: Take back — only visible when a snapshot was retained successfully.
        btnTakeBack = add(new FButton(TakeBackActions.buttonLabel(),
                guard(e -> TakeBackActions.takeBack())));
        btnOk.setSize(BTN_WIDTH, HEIGHT);
        btnCancel.setSize(BTN_WIDTH, HEIGHT);
        btnTakeBack.setSize(BTN_WIDTH, HEIGHT);
        btnOk.setCorner(Corner.BottomLeft);
        btnCancel.setCorner(Corner.BottomRight);
        btnOk.setEnabled(false); //disable buttons until first input queued
        btnCancel.setEnabled(false);
        btnTakeBack.setVisible(false);
        btnTakeBack.setEnabled(false);
    }

    public FButton getBtnOk() {
        return btnOk;
    }

    public FButton getBtnCancel() {
        return btnCancel;
    }

    public FButton getBtnTakeBack() {
        return btnTakeBack;
    }

    /** DS4: show Take back only when a snapshot succeeded and remains eligible. */
    public void refreshTakeBackButton() {
        final boolean show = TakeBackActions.featureEnabled() && TakeBackActions.canTakeBack();
        btnTakeBack.setText(TakeBackActions.buttonLabel());
        btnTakeBack.setVisible(show);
        btnTakeBack.setEnabled(show);
        revalidate();
    }

    public String getMessage() {
        return message;
    }
    public void setMessage(String message0) {
        onPromptChanged(message0);
        message = message0;
        card = null;
    }
    public void setMessage(String message0, CardView card0) {
        onPromptChanged(message0);
        message = message0;
        card = card0;
    }

    private FEventHandler guard(FEventHandler command) {
        if (!guardDoubleClicks || command == null)
            return command;
        return e -> {
            long now = System.currentTimeMillis();
            if (now < ignorePressesUntil)
                return;
            ignorePressesUntil = now + MAX_PRESS_LOCKOUT_MS;
            command.handleEvent(e);
        };
    }

    private void onPromptChanged(String newMessage) {
        if (guardDoubleClicks && !StringUtils.equals(message, newMessage))
            ignorePressesUntil = System.currentTimeMillis() + NEW_PROMPT_LOCKOUT_MS;
    }

    /** Flashes animation on input panel if play is currently waiting on input. */
    public void remind() {
        //SDisplayUtil.remind(view);
    }

    @Override
    protected void doLayout(float width, float height) {
        final boolean takeBack = btnTakeBack.isVisible();
        final float takeW = takeBack ? BTN_WIDTH : 0f;
        lblMessage.setWidth(width - 2 * BTN_WIDTH - takeW);
        if (Forge.reversedPrompt) {
            btnOk.setCorner(Corner.BottomRight);
            btnCancel.setCorner(Corner.BottomLeft);
            btnCancel.setLeft(0);
            if (takeBack) {
                btnTakeBack.setLeft(btnCancel.getRight());
                lblMessage.setLeft(btnTakeBack.getRight());
            } else {
                lblMessage.setLeft(btnCancel.getRight());
            }
            btnOk.setLeft(lblMessage.getRight());
        } else {
            btnOk.setCorner(Corner.BottomLeft);
            btnCancel.setCorner(Corner.BottomRight);
            btnOk.setLeft(0);
            if (takeBack) {
                btnTakeBack.setLeft(btnOk.getRight());
                lblMessage.setLeft(btnTakeBack.getRight());
            } else {
                lblMessage.setLeft(btnOk.getRight());
            }
            btnCancel.setLeft(lblMessage.getRight());
        }
    }

    @Override
    protected void drawBackground(Graphics g) {
        g.fillRect(getBackColor(), 0, 0, getWidth(), getHeight());
    }
    
    private class MessageLabel extends FDisplayObject {
        private final TextRenderer renderer = new TextRenderer();

        @Override
        public boolean tap(float x, float y, int count) {
            //if not enough room for prompt at given size, show magnify view
            float maxWidth = getWidth() - 2 * PADDING;
            float maxHeight = getHeight() - 2 * PADDING;
            TextBounds textBounds = renderer.getWrappedBounds(message, FONT, maxWidth);
            if (textBounds.height > maxHeight) {
                FMagnifyView.show(this, message, getForeColor(), getBackColor(), FONT, false);
            }
            return true;
        }

        @Override
        public boolean fling(float x, float y) {
            if (card != null) {
                CardZoom.show(card);
            }
            return true;
        }

        @Override
        public boolean longPress(float x, float y) {
            if (card != null) {
                CardZoom.show(card);
            }
            return true;
        }

        @Override
        public void draw(Graphics g) {
            if (!StringUtils.isEmpty(message)) {
                float x = PADDING;
                float y = PADDING;
                float w = getWidth() - 2 * PADDING;
                float h = getHeight() - 2 * PADDING;
                renderer.drawText(g, message, FONT, getForeColor(), x, y, w, h, y, h, true, Align.center, true);
            }
        }
    }
}
