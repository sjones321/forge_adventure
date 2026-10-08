package forge.adventure.player;

/**
 * Outcome of an Ascendant INV1 grant. Adds always succeed (item kept, overflowed, or auto-sold);
 * never silently deleted.
 */
public class GrantResult {
    public enum Fate {
        /** Accepted into the target bag / pouch. */
        ACCEPTED,
        /** Target bag full — placed in Overflow. */
        OVERFLOW,
        /** Overflow at cap — auto-sold (gold / dust). */
        AUTO_SOLD
    }

    public Fate fate = Fate.ACCEPTED;
    public String message;
    public int goldEarned;
    public int dustCommon;
    public int dustUncommon;
    public int dustRare;
    public int dustMythic;
    public String autoSoldName;

    public static GrantResult accepted() {
        return new GrantResult();
    }

    public static GrantResult overflow() {
        GrantResult r = new GrantResult();
        r.fate = Fate.OVERFLOW;
        r.message = "Bag full — sent to Overflow";
        return r;
    }

    public boolean wentToOverflow() {
        return fate == Fate.OVERFLOW;
    }

    public boolean wasAutoSold() {
        return fate == Fate.AUTO_SOLD;
    }
}
