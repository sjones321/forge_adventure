package forge.gamemodes.net.coop;

/**
 * Inventory view used by TR1 host validation and atomic apply. Live play adapts
 * {@code AdventurePlayer}; tests use {@link Simple}.
 */
public interface CoopTradeBag {

    int getGold();

    boolean takeGold(int amount);

    void addGold(int amount);

    int getMaterial(String id);

    boolean takeMaterial(String id, int amount);

    /**
     * Grant materials. Implementations may route excess to Overflow (never lose).
     * @return true if accepted (including overflow / auto-sell)
     */
    boolean addMaterial(String id, int amount);

    int getItemCount(String name);

    /** True when this item name is a quest item and must not be offered. */
    boolean isQuestItem(String name);

    boolean takeItem(String name, int amount);

    boolean addItem(String name, int amount);

    /**
     * Tradeable copies of a card key ({@code name|set|art}): owned minus vaulted
     * minus copies used in any deck.
     */
    int getTradeableCardCount(String cardKey);

    /** True when any of the requested copies are vaulted or reserved by a deck. */
    boolean isCardBlocked(String cardKey);

    boolean takeCard(String cardKey, int amount);

    boolean addCard(String cardKey, int amount);

    /** Snapshot for atomic rollback. */
    Snapshot snapshot();

    void restore(Snapshot snap);

    /** Opaque bag snapshot. */
    interface Snapshot {
    }

    /**
     * In-memory bag for headless tests. Supports an optional Overflow sink so
     * received items are never lost when a capacity cap is set.
     */
    final class Simple implements CoopTradeBag {
        private int gold;
        private final java.util.Map<String, Integer> materials = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Integer> items = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Integer> cards = new java.util.LinkedHashMap<>();
        private final java.util.Set<String> questItems = new java.util.HashSet<>();
        private final java.util.Set<String> vaultedOrDeckCards = new java.util.HashSet<>();
        private final java.util.Map<String, Integer> tradeableCards = new java.util.LinkedHashMap<>();
        /** Distinct item types that fit before Overflow. 0 = unlimited. */
        private int itemCapacity;
        private final java.util.List<String> overflowItems = new java.util.ArrayList<>();

        public void setGold(final int gold) {
            this.gold = Math.max(0, gold);
        }

        public void setMaterial(final String id, final int count) {
            if (id == null || id.isEmpty()) {
                return;
            }
            if (count <= 0) {
                materials.remove(id);
            } else {
                materials.put(id, count);
            }
        }

        public void setItem(final String name, final int count) {
            if (name == null || name.isEmpty()) {
                return;
            }
            if (count <= 0) {
                items.remove(name);
            } else {
                items.put(name, count);
            }
        }

        public void markQuestItem(final String name) {
            if (name != null) {
                questItems.add(name);
            }
        }

        /**
         * Register a card. {@code tradeable} copies may be offered; any further
         * owned copies that are vaulted/in-deck mark the key blocked when
         * tradeable is 0.
         */
        public void setCard(final String cardKey, final int tradeable, final boolean blocked) {
            if (cardKey == null || cardKey.isEmpty()) {
                return;
            }
            if (tradeable > 0) {
                tradeableCards.put(cardKey, tradeable);
                cards.put(cardKey, tradeable);
            } else {
                tradeableCards.remove(cardKey);
                cards.remove(cardKey);
            }
            if (blocked) {
                vaultedOrDeckCards.add(cardKey);
            } else {
                vaultedOrDeckCards.remove(cardKey);
            }
        }

        public void setItemCapacity(final int capacity) {
            this.itemCapacity = Math.max(0, capacity);
        }

        public java.util.List<String> getOverflowItems() {
            return java.util.Collections.unmodifiableList(overflowItems);
        }

        public int overflowCount() {
            return overflowItems.size();
        }

        @Override
        public int getGold() {
            return gold;
        }

        @Override
        public boolean takeGold(final int amount) {
            if (amount <= 0 || gold < amount) {
                return false;
            }
            gold -= amount;
            return true;
        }

        @Override
        public void addGold(final int amount) {
            if (amount > 0) {
                gold += amount;
            }
        }

        @Override
        public int getMaterial(final String id) {
            if (id == null) {
                return 0;
            }
            final Integer n = materials.get(id);
            return n == null ? 0 : Math.max(0, n);
        }

        @Override
        public boolean takeMaterial(final String id, final int amount) {
            if (id == null || amount <= 0) {
                return false;
            }
            final int have = getMaterial(id);
            if (have < amount) {
                return false;
            }
            final int left = have - amount;
            if (left <= 0) {
                materials.remove(id);
            } else {
                materials.put(id, left);
            }
            return true;
        }

        @Override
        public boolean addMaterial(final String id, final int amount) {
            if (id == null || id.isEmpty() || amount <= 0) {
                return false;
            }
            materials.put(id, getMaterial(id) + amount);
            return true;
        }

        @Override
        public int getItemCount(final String name) {
            if (name == null) {
                return 0;
            }
            final Integer n = items.get(name);
            return n == null ? 0 : Math.max(0, n);
        }

        @Override
        public boolean isQuestItem(final String name) {
            return name != null && questItems.contains(name);
        }

        @Override
        public boolean takeItem(final String name, final int amount) {
            if (name == null || amount <= 0) {
                return false;
            }
            final int have = getItemCount(name);
            if (have < amount) {
                return false;
            }
            final int left = have - amount;
            if (left <= 0) {
                items.remove(name);
            } else {
                items.put(name, left);
            }
            return true;
        }

        @Override
        public boolean addItem(final String name, final int amount) {
            if (name == null || name.isEmpty() || amount <= 0) {
                return false;
            }
            int remaining = amount;
            while (remaining > 0) {
                if (itemCapacity > 0 && distinctItemSlots() >= itemCapacity) {
                    overflowItems.add(name);
                } else {
                    items.put(name, getItemCount(name) + 1);
                }
                remaining--;
            }
            return true;
        }

        private int distinctItemSlots() {
            int slots = 0;
            for (final Integer n : items.values()) {
                if (n != null && n > 0) {
                    slots++;
                }
            }
            return slots;
        }

        @Override
        public int getTradeableCardCount(final String cardKey) {
            if (cardKey == null) {
                return 0;
            }
            final Integer n = tradeableCards.get(cardKey);
            return n == null ? 0 : Math.max(0, n);
        }

        @Override
        public boolean isCardBlocked(final String cardKey) {
            return cardKey != null && vaultedOrDeckCards.contains(cardKey)
                    && getTradeableCardCount(cardKey) <= 0;
        }

        @Override
        public boolean takeCard(final String cardKey, final int amount) {
            if (cardKey == null || amount <= 0) {
                return false;
            }
            final int have = getTradeableCardCount(cardKey);
            if (have < amount) {
                return false;
            }
            final int left = have - amount;
            if (left <= 0) {
                tradeableCards.remove(cardKey);
                cards.remove(cardKey);
            } else {
                tradeableCards.put(cardKey, left);
                cards.put(cardKey, left);
            }
            return true;
        }

        @Override
        public boolean addCard(final String cardKey, final int amount) {
            if (cardKey == null || cardKey.isEmpty() || amount <= 0) {
                return false;
            }
            tradeableCards.put(cardKey, getTradeableCardCount(cardKey) + amount);
            cards.put(cardKey, cards.getOrDefault(cardKey, 0) + amount);
            return true;
        }

        @Override
        public Snapshot snapshot() {
            final BagSnap s = new BagSnap();
            s.gold = gold;
            s.materials.putAll(materials);
            s.items.putAll(items);
            s.cards.putAll(cards);
            s.tradeable.putAll(tradeableCards);
            s.overflow.addAll(overflowItems);
            s.quest.addAll(questItems);
            s.blocked.addAll(vaultedOrDeckCards);
            s.itemCapacity = itemCapacity;
            return s;
        }

        @Override
        public void restore(final Snapshot snap) {
            if (!(snap instanceof BagSnap)) {
                return;
            }
            final BagSnap s = (BagSnap) snap;
            gold = s.gold;
            materials.clear();
            materials.putAll(s.materials);
            items.clear();
            items.putAll(s.items);
            cards.clear();
            cards.putAll(s.cards);
            tradeableCards.clear();
            tradeableCards.putAll(s.tradeable);
            overflowItems.clear();
            overflowItems.addAll(s.overflow);
            questItems.clear();
            questItems.addAll(s.quest);
            vaultedOrDeckCards.clear();
            vaultedOrDeckCards.addAll(s.blocked);
            itemCapacity = s.itemCapacity;
        }

        private static final class BagSnap implements Snapshot {
            int gold;
            final java.util.Map<String, Integer> materials = new java.util.LinkedHashMap<>();
            final java.util.Map<String, Integer> items = new java.util.LinkedHashMap<>();
            final java.util.Map<String, Integer> cards = new java.util.LinkedHashMap<>();
            final java.util.Map<String, Integer> tradeable = new java.util.LinkedHashMap<>();
            final java.util.List<String> overflow = new java.util.ArrayList<>();
            final java.util.Set<String> quest = new java.util.HashSet<>();
            final java.util.Set<String> blocked = new java.util.HashSet<>();
            int itemCapacity;
        }
    }
}
