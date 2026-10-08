package forge.gamemodes.net.event;

public class LoginEvent implements NetEvent {
    private static final long serialVersionUID = -8865183377417377938L;

    private final String username;
    private final int avatarIndex, sleeveIndex;
    private final String version;
    private final boolean libgdx;
    /**
     * Ascendant co-op (CO3): session code from the authenticated overworld session.
     * Empty for stock online play. Older peers omit this field on the wire (Java
     * serialization default).
     */
    private final String sessionCode;

    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex,
                      final String version, final boolean libgdx) {
        this(username, avatarIndex, sleeveIndex, version, libgdx, "");
    }

    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex,
                      final String version, final boolean libgdx, final String sessionCode) {
        this.username = username;
        this.avatarIndex = avatarIndex;
        this.sleeveIndex = sleeveIndex;
        this.version = version;
        this.libgdx = libgdx;
        this.sessionCode = sessionCode != null ? sessionCode : "";
    }

    public String getUsername() {
        return username;
    }

    public int getAvatarIndex() {
        return avatarIndex;
    }

    public int getSleeveIndex() {
        return sleeveIndex;
    }

    public String getVersion() {
        return version;
    }

    public boolean isLibgdx() {
        return libgdx;
    }

    /** Co-op session code; empty for stock online. */
    public String getSessionCode() {
        return sessionCode != null ? sessionCode : "";
    }
}
