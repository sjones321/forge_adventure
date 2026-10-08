package forge.gamemodes.net.coop;

/**
 * Headless model of the in-game party / location invite prompt. Tracks whether
 * a modal Accept/Decline dialog is visible and closes it when the underlying
 * invite expires ({@code coopLocationInviteTimeoutSeconds}).
 */
public final class CoopInviteUiState {
    public enum PromptKind { NONE, PARTY, LOCATION }

    private volatile PromptKind kind = PromptKind.NONE;
    private volatile long promptInviteId;
    private volatile String promptFrom = "";
    private volatile String promptDetail = "";

    public PromptKind getKind() {
        return kind;
    }

    public boolean isDialogVisible() {
        return kind != PromptKind.NONE;
    }

    public long getPromptInviteId() {
        return promptInviteId;
    }

    public String getPromptFrom() {
        return promptFrom;
    }

    public String getPromptDetail() {
        return promptDetail;
    }

    public void showPartyInvite(final long inviteId, final String fromPlayer) {
        kind = PromptKind.PARTY;
        promptInviteId = inviteId;
        promptFrom = fromPlayer == null ? "" : fromPlayer;
        promptDetail = "";
    }

    public void showLocationInvite(final long inviteId, final String fromPlayer, final String displayName) {
        kind = PromptKind.LOCATION;
        promptInviteId = inviteId;
        promptFrom = fromPlayer == null ? "" : fromPlayer;
        promptDetail = displayName == null ? "" : displayName;
    }

    public void hide() {
        kind = PromptKind.NONE;
        promptInviteId = 0L;
        promptFrom = "";
        promptDetail = "";
    }

    public static final class ExpiryResult {
        public final boolean partyExpired;
        public final boolean locationExpired;
        public final boolean dialogClosed;

        public ExpiryResult(final boolean partyExpired, final boolean locationExpired,
                            final boolean dialogClosed) {
            this.partyExpired = partyExpired;
            this.locationExpired = locationExpired;
            this.dialogClosed = dialogClosed;
        }

        public boolean anyExpired() {
            return partyExpired || locationExpired;
        }
    }

    /**
     * Tick invite expiry for the UI path. Expires party / location pending
     * invites and closes the matching Accept/Decline dialog when visible.
     */
    public ExpiryResult tickExpiry(final CoopPartyState party, final CoopLocationPolicy location,
                                   final long nowMs, final long timeoutMs) {
        boolean partyExpired = false;
        boolean locationExpired = false;
        boolean dialogClosed = false;
        if (party != null && party.expireIfNeeded(nowMs, timeoutMs)) {
            partyExpired = true;
            if (kind == PromptKind.PARTY) {
                hide();
                dialogClosed = true;
            }
        }
        if (location != null && location.expireIfNeeded(nowMs, timeoutMs)) {
            locationExpired = true;
            if (kind == PromptKind.LOCATION) {
                hide();
                dialogClosed = true;
            }
        }
        return new ExpiryResult(partyExpired, locationExpired, dialogClosed);
    }
}
