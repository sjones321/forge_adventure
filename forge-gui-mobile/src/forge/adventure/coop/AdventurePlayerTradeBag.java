package forge.adventure.coop;

import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.OverflowEntry;
import forge.adventure.util.Config;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.gamemodes.net.coop.CoopTradeBag;
import forge.gamemodes.net.coop.CoopTradeValidator;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts {@link AdventurePlayer} to {@link CoopTradeBag} for TR1 host validation
 * and atomic apply. Received items/materials use INV1 grant paths (Overflow, never lost).
 * Guest character files stay isolated via {@link CoopCharacterStore}.
 * {@link #restore} reconstitutes gold, materials, items, cards, <b>and Overflow</b>.
 */
public final class AdventurePlayerTradeBag implements CoopTradeBag {
    private final AdventurePlayer player;

    public AdventurePlayerTradeBag(final AdventurePlayer player) {
        this.player = player;
    }

    public AdventurePlayer getPlayer() {
        return player;
    }

    @Override
    public int getGold() {
        return player.getGold();
    }

    @Override
    public boolean takeGold(final int amount) {
        if (amount <= 0 || player.getGold() < amount) {
            return false;
        }
        player.takeGold(amount);
        return true;
    }

    @Override
    public boolean addGold(final int amount) {
        if (amount <= 0) {
            return false;
        }
        if (CoopTradeValidator.goldAddWouldOverflow(player.getGold(), amount)) {
            return false;
        }
        player.giveGold(amount);
        return true;
    }

    @Override
    public int getMaterial(final String id) {
        return player.getMaterial(id);
    }

    @Override
    public boolean takeMaterial(final String id, final int amount) {
        return player.takeMaterial(id, amount);
    }

    @Override
    public boolean addMaterial(final String id, final int amount) {
        return player.addMaterial(id, amount);
    }

    @Override
    public int getItemCount(final String name) {
        if (name == null || name.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (final ItemData item : player.getItems()) {
            if (item != null && name.equalsIgnoreCase(item.name) && !item.isEquipped) {
                n++;
            }
        }
        return n;
    }

    @Override
    public boolean isQuestItem(final String name) {
        if (name == null) {
            return false;
        }
        for (final ItemData item : player.getItems()) {
            if (item != null && name.equalsIgnoreCase(item.name) && item.questItem) {
                return true;
            }
        }
        final ItemData proto = ItemListData.getItem(name);
        return proto != null && proto.questItem;
    }

    @Override
    public boolean takeItem(final String name, final int amount) {
        if (name == null || amount <= 0 || isQuestItem(name)) {
            return false;
        }
        int left = amount;
        final List<ItemData> toRemove = new ArrayList<>();
        for (final ItemData item : player.getItems()) {
            if (left <= 0) {
                break;
            }
            if (item != null && name.equalsIgnoreCase(item.name) && !item.isEquipped && !item.questItem) {
                toRemove.add(item);
                left--;
            }
        }
        if (left > 0) {
            return false;
        }
        for (final ItemData item : toRemove) {
            player.removeItem(item);
        }
        return true;
    }

    @Override
    public boolean addItem(final String name, final int amount) {
        if (name == null || name.isEmpty() || amount <= 0) {
            return false;
        }
        // updateEvent=false: trade apply must not touch quest listeners / Current.player().
        for (int i = 0; i < amount; i++) {
            if (!player.addItem(name, false)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getTradeableCardCount(final String cardKey) {
        final PaperCard card = resolveCard(cardKey);
        if (card == null) {
            return 0;
        }
        return Math.max(0, player.getCards().count(card)
                - player.vaultedCount(card)
                - player.getCopiesUsedInDecks(card));
    }

    @Override
    public boolean isCardBlocked(final String cardKey) {
        final PaperCard card = resolveCard(cardKey);
        if (card == null) {
            return true;
        }
        return player.vaultedCount(card) > 0 || player.getCopiesUsedInDecks(card) > 0;
    }

    @Override
    public boolean takeCard(final String cardKey, final int amount) {
        final PaperCard card = resolveCard(cardKey);
        if (card == null || amount <= 0) {
            return false;
        }
        if (getTradeableCardCount(cardKey) < amount) {
            return false;
        }
        return player.getCards().remove(card, amount);
    }

    @Override
    public boolean addCard(final String cardKey, final int amount) {
        final PaperCard card = resolveCard(cardKey);
        if (card == null || amount <= 0) {
            return false;
        }
        player.addCard(card, amount);
        return true;
    }

    @Override
    public Snapshot snapshot() {
        final Snap s = new Snap();
        s.gold = player.getGold();
        s.materials.putAll(player.getMaterials());
        for (final ItemData item : player.getItems()) {
            if (item == null || item.name == null) {
                continue;
            }
            // Skip equipped — trade never takes them; restore puts unequipped copies back.
            if (item.isEquipped) {
                continue;
            }
            s.itemNames.add(item.name);
        }
        final CardPool pool = player.getCards();
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                s.cards.put(cardKey(e.getKey()), e.getValue());
            }
        }
        // Overflow must round-trip with rollback (INV1 extras from a failed commit).
        for (final OverflowEntry entry : player.getBags().getOverflow()) {
            if (entry != null) {
                s.overflow.add(copyOverflow(entry));
            }
        }
        return s;
    }

    @Override
    public void restore(final Snapshot snap) {
        if (!(snap instanceof Snap) || !Config.ascendant()) {
            return;
        }
        final Snap s = (Snap) snap;

        // Gold
        final int deltaGold = s.gold - player.getGold();
        if (deltaGold > 0) {
            player.giveGold(deltaGold);
        } else if (deltaGold < 0) {
            player.takeGold(-deltaGold);
        }

        // Materials
        for (final Map.Entry<String, Integer> e : new HashMap<>(player.getMaterials()).entrySet()) {
            final int want = s.materials.getOrDefault(e.getKey(), 0);
            final int have = e.getValue() == null ? 0 : e.getValue();
            if (have > want) {
                player.takeMaterial(e.getKey(), have - want);
            } else if (want > have) {
                player.addMaterial(e.getKey(), want - have);
            }
        }
        for (final Map.Entry<String, Integer> e : s.materials.entrySet()) {
            if (player.getMaterial(e.getKey()) < e.getValue()) {
                player.addMaterial(e.getKey(), e.getValue() - player.getMaterial(e.getKey()));
            }
        }

        // Items (unequipped only): remove extras, re-add missing.
        final Map<String, Integer> wantItems = new HashMap<>();
        for (final String name : s.itemNames) {
            wantItems.merge(name, 1, Integer::sum);
        }
        final Map<String, Integer> haveItems = new HashMap<>();
        for (final ItemData item : new ArrayList<>(player.getItems())) {
            if (item == null || item.name == null || item.isEquipped) {
                continue;
            }
            haveItems.merge(item.name, 1, Integer::sum);
        }
        for (final Map.Entry<String, Integer> e : haveItems.entrySet()) {
            final int want = wantItems.getOrDefault(e.getKey(), 0);
            final int extra = e.getValue() - want;
            if (extra > 0) {
                takeItem(e.getKey(), extra);
            }
        }
        for (final Map.Entry<String, Integer> e : wantItems.entrySet()) {
            final int have = getItemCount(e.getKey());
            final int missing = e.getValue() - have;
            if (missing > 0) {
                addItem(e.getKey(), missing);
            }
        }

        // Cards: adjust collection counts toward the snapshot.
        final Map<String, Integer> haveCards = new HashMap<>();
        for (final Map.Entry<PaperCard, Integer> e : player.getCards()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                haveCards.put(cardKey(e.getKey()), e.getValue());
            }
        }
        for (final Map.Entry<String, Integer> e : haveCards.entrySet()) {
            final int want = s.cards.getOrDefault(e.getKey(), 0);
            if (e.getValue() > want) {
                takeCard(e.getKey(), e.getValue() - want);
            }
        }
        for (final Map.Entry<String, Integer> e : s.cards.entrySet()) {
            final int have = haveCards.getOrDefault(e.getKey(), 0);
            if (e.getValue() > have) {
                addCard(e.getKey(), e.getValue() - have);
            }
        }

        // Overflow: replace with the snapshotted entries (no auto-sell on restore).
        final OverflowEntry[] restored = new OverflowEntry[s.overflow.size()];
        for (int i = 0; i < s.overflow.size(); i++) {
            restored[i] = copyOverflow(s.overflow.get(i));
        }
        player.getBags().loadOverflowEntries(restored);
    }

    private static OverflowEntry copyOverflow(final OverflowEntry src) {
        if (src == null) {
            return null;
        }
        final OverflowEntry e = new OverflowEntry();
        e.kind = src.kind;
        e.key = src.key;
        e.amount = src.amount;
        if (src.item != null) {
            e.item = src.item.clone();
        }
        if (src.booster != null) {
            e.booster = new Deck(src.booster);
        }
        return e;
    }

    public static String cardKey(final PaperCard card) {
        if (card == null) {
            return "";
        }
        return card.getName() + '|' + card.getEdition() + '|' + card.getArtIndex();
    }

    public static PaperCard resolveCard(final String cardKey) {
        if (cardKey == null || cardKey.isEmpty()) {
            return null;
        }
        final String[] parts = cardKey.split("\\|", -1);
        if (parts.length < 1 || parts[0].isEmpty()) {
            return null;
        }
        try {
            final String name = parts[0];
            final String set = parts.length > 1 ? parts[1] : "";
            final int art = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (set == null || set.isEmpty()) {
                return FModel.getMagicDb().getCommonCards().getCard(name);
            }
            return FModel.getMagicDb().getCommonCards().getCard(name, set, art);
        } catch (final Exception e) {
            return null;
        }
    }

    private static final class Snap implements Snapshot {
        int gold;
        final Map<String, Integer> materials = new HashMap<>();
        final List<String> itemNames = new ArrayList<>();
        final Map<String, Integer> cards = new HashMap<>();
        final List<OverflowEntry> overflow = new ArrayList<>();
    }
}
