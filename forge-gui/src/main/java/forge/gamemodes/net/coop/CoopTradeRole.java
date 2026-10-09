package forge.gamemodes.net.coop;

/**
 * Session role for TR1 trade peers. Wire and host state identify sides by role,
 * not by character name (names can collide / change).
 */
public enum CoopTradeRole {
    HOST,
    GUEST;

    public CoopTradeRole other() {
        return this == HOST ? GUEST : HOST;
    }
}
