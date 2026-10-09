package forge.adventure.scene;

import com.badlogic.gdx.Input;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.coop.AdventurePlayerTradeBag;
import forge.adventure.coop.CoopTradeRuntime;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.KeyBinding;
import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.coop.CoopTradeState;
import forge.gamemodes.net.coop.CoopTradeWireLimits;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ascendant TR1 face-to-face trade window. Controller-first (INV1-style bindings):
 * A confirms, B cancels (only before Execute), Y adds gold, DPAD moves focus,
 * LB/RB cycle add targets. Stock play never opens this scene ({@link Config#ascendant()}).
 */
public class TradeScene extends UIScene {
    private static TradeScene object;
    private static boolean open;

    private TypingLabel status;
    private TypingLabel youOffer;
    private TypingLabel theyOffer;
    private int draftGold;
    private final List<CoopTradeOffer.Line> draftMaterials = new ArrayList<>();
    private final List<CoopTradeOffer.Line> draftItems = new ArrayList<>();
    private final List<CoopTradeOffer.CardLine> draftCards = new ArrayList<>();
    private int materialCursor;
    private int itemCursor;
    private final List<String> materialIds = new ArrayList<>();
    private final List<String> itemNames = new ArrayList<>();

    public static TradeScene instance() {
        if (object == null) {
            object = new TradeScene();
        }
        return object;
    }

    public static boolean isOpen() {
        return open;
    }

    private TradeScene() {
        super(Forge.isLandscapeMode() ? "ui/trade.json" : "ui/trade_portrait.json");
        ui.onButtonPress("confirm", this::toggleConfirm);
        ui.onButtonPress("cancel", this::cancel);
        ui.onButtonPress("addGold", this::addGoldStep);
        ui.onButtonPress("addMaterial", this::addMaterialStep);
        ui.onButtonPress("addItem", this::addItemStep);
        ui.onButtonPress("clearOffer", this::clearOffer);
        status = ui.findActor("status");
        youOffer = ui.findActor("youOffer");
        theyOffer = ui.findActor("theyOffer");
    }

    @Override
    public void enter() {
        open = true;
        draftGold = 0;
        draftMaterials.clear();
        draftItems.clear();
        draftCards.clear();
        rebuildCursors();
        refreshFromState();
        pushOffer();
        super.enter();
    }

    @Override
    public boolean leave() {
        open = false;
        return super.leave();
    }

    public void refreshFromState() {
        final CoopTradeState st = CoopTradeRuntime.get().getState();
        if (status != null) {
            String conf = (st.isLocalConfirmed() ? "You ✓" : "You …")
                    + "  |  "
                    + (st.isPeerConfirmed() ? "Partner ✓" : "Partner …");
            if (!st.isCancelAllowed()) {
                conf += "  |  Commit…";
            }
            status.setText(CoopTradeWireLimits.clampText(conf));
            status.skipToTheEnd();
        }
        if (youOffer != null) {
            youOffer.setText("[%85]You offer:\n" + formatOffer(buildDraftOffer()));
            youOffer.skipToTheEnd();
        }
        if (theyOffer != null) {
            theyOffer.setText("[%85]They offer:\n" + formatOffer(st.getPeerOffer()));
            theyOffer.skipToTheEnd();
        }
    }

    private void rebuildCursors() {
        materialIds.clear();
        itemNames.clear();
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        for (final Map.Entry<String, Integer> e : ap.getMaterials().entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                materialIds.add(e.getKey());
            }
        }
        for (final ItemData item : ap.getItems()) {
            if (item == null || item.name == null || item.questItem || item.isEquipped) {
                continue;
            }
            if (!itemNames.contains(item.name)) {
                itemNames.add(item.name);
            }
        }
        materialCursor = 0;
        itemCursor = 0;
    }

    private CoopTradeOffer buildDraftOffer() {
        return new CoopTradeOffer(draftGold, draftMaterials, draftItems, draftCards);
    }

    private void pushOffer() {
        CoopTradeRuntime.get().updateLocalOffer(buildDraftOffer());
        refreshFromState();
    }

    private void toggleConfirm() {
        final CoopTradeState st = CoopTradeRuntime.get().getState();
        CoopTradeRuntime.get().setLocalConfirmed(!st.isLocalConfirmed());
        refreshFromState();
    }

    private void cancel() {
        if (!CoopTradeRuntime.get().getState().isCancelAllowed()) {
            // Cancel disabled after Execute (protocol + UI).
            return;
        }
        CoopTradeRuntime.get().cancelTrade("cancelled");
        open = false;
        Forge.switchScene(GameScene.instance());
    }

    private void clearOffer() {
        draftGold = 0;
        draftMaterials.clear();
        draftItems.clear();
        draftCards.clear();
        pushOffer();
    }

    private void addGoldStep() {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        final int step = 10;
        final int next = Math.min(ap.getGold(), draftGold + step);
        if (next == draftGold && draftGold < ap.getGold()) {
            draftGold = Math.min(ap.getGold(), draftGold + 1);
        } else {
            draftGold = next;
        }
        pushOffer();
    }

    private void addMaterialStep() {
        rebuildCursors();
        if (materialIds.isEmpty()) {
            return;
        }
        materialCursor = materialCursor % materialIds.size();
        final String id = materialIds.get(materialCursor);
        final AdventurePlayer ap = Current.player();
        final int have = ap.getMaterial(id);
        int offered = 0;
        for (final CoopTradeOffer.Line line : draftMaterials) {
            if (line.getId().equals(id)) {
                offered = line.getCount();
                break;
            }
        }
        if (offered >= have) {
            materialCursor = (materialCursor + 1) % materialIds.size();
            return;
        }
        draftMaterials.removeIf(l -> l.getId().equals(id));
        draftMaterials.add(new CoopTradeOffer.Line(id, offered + 1, have));
        materialCursor = (materialCursor + 1) % materialIds.size();
        pushOffer();
    }

    private void addItemStep() {
        rebuildCursors();
        if (itemNames.isEmpty()) {
            return;
        }
        itemCursor = itemCursor % itemNames.size();
        final String name = itemNames.get(itemCursor);
        final AdventurePlayerTradeBag bag = new AdventurePlayerTradeBag(Current.player());
        if (bag.isQuestItem(name)) {
            itemCursor = (itemCursor + 1) % itemNames.size();
            return;
        }
        final int have = bag.getItemCount(name);
        int offered = 0;
        for (final CoopTradeOffer.Line line : draftItems) {
            if (line.getId().equalsIgnoreCase(name)) {
                offered = line.getCount();
                break;
            }
        }
        if (offered >= have) {
            itemCursor = (itemCursor + 1) % itemNames.size();
            return;
        }
        draftItems.removeIf(l -> l.getId().equalsIgnoreCase(name));
        draftItems.add(new CoopTradeOffer.Line(name, offered + 1, have));
        itemCursor = (itemCursor + 1) % itemNames.size();
        pushOffer();
    }

    /** Offer one tradeable copy of the selected collection card (best-effort first free card). */
    private void addCardStep() {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        for (final Map.Entry<PaperCard, Integer> e : ap.getCards()) {
            final PaperCard card = e.getKey();
            if (card == null) {
                continue;
            }
            final String key = AdventurePlayerTradeBag.cardKey(card);
            final int tradeable = Math.max(0, e.getValue()
                    - ap.vaultedCount(card)
                    - ap.getCopiesUsedInDecks(card));
            if (tradeable <= 0) {
                continue;
            }
            int offered = 0;
            for (final CoopTradeOffer.CardLine line : draftCards) {
                if (line.key().equals(key)) {
                    offered = line.getCount();
                    break;
                }
            }
            if (offered >= tradeable) {
                continue;
            }
            draftCards.removeIf(l -> l.key().equals(key));
            draftCards.add(new CoopTradeOffer.CardLine(
                    card.getName(), card.getEdition(), card.getArtIndex(), offered + 1, tradeable));
            pushOffer();
            return;
        }
    }

    private static String formatOffer(final CoopTradeOffer offer) {
        if (offer == null || offer.isEmpty()) {
            return "(nothing)";
        }
        final StringBuilder sb = new StringBuilder();
        if (offer.getGold() > 0) {
            sb.append(offer.getGold()).append(" gold\n");
        }
        for (final CoopTradeOffer.Line line : offer.getMaterials()) {
            sb.append(line.getCount()).append('x').append(' ').append(line.getId()).append('\n');
        }
        for (final CoopTradeOffer.Line line : offer.getItems()) {
            sb.append(line.getCount()).append('x').append(' ').append(line.getId()).append('\n');
        }
        for (final CoopTradeOffer.CardLine line : offer.getCards()) {
            sb.append(line.getCount()).append('x').append(' ').append(line.getName()).append('\n');
        }
        return sb.toString().trim();
    }

    @Override
    public boolean keyPressed(final int keycode) {
        if (!Config.ascendant()) {
            return super.keyPressed(keycode);
        }
        // INV1-style: never steal keys while a HUD invite dialog is up.
        try {
            if (forge.adventure.stage.GameHUD.getInstance().isDialogOnlyInput()) {
                return super.keyPressed(keycode);
            }
        } catch (final Exception ignored) {
        }
        if (KeyBinding.Back.isPressed(keycode)) {
            cancel();
            return true;
        }
        if (KeyBinding.Use.isPressed(keycode) || keycode == Input.Keys.BUTTON_A) {
            toggleConfirm();
            return true;
        }
        if (KeyBinding.Status.isPressed(keycode) || keycode == Input.Keys.BUTTON_Y) {
            addGoldStep();
            return true;
        }
        if (KeyBinding.ScrollUp.isPressed(keycode) || keycode == Input.Keys.BUTTON_L1) {
            addMaterialStep();
            return true;
        }
        if (KeyBinding.ScrollDown.isPressed(keycode) || keycode == Input.Keys.BUTTON_R1) {
            addItemStep();
            return true;
        }
        if (keycode == Input.Keys.C) {
            addCardStep();
            return true;
        }
        return super.keyPressed(keycode);
    }
}
