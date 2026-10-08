package forge.gamemodes.net.coop;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Headless model of in-game invite prompts (party / location / join-fight).
 * Invites are queued — never replace an open dialog (especially exit-dungeon).
 * Tracks whether a modal Accept/Decline dialog is visible and closes it when
 * the underlying invite expires ({@code coopLocationInviteTimeoutSeconds}).
 */
public final class CoopInviteUiState {
    public enum PromptKind { NONE, PARTY, LOCATION, JOIN_FIGHT }

    /** One queued or active invite prompt. */
    public static final class Prompt {
        public final PromptKind kind;
        public final long inviteId;
        public final String from;
        public final String detail;

        public Prompt(final PromptKind kind, final long inviteId, final String from, final String detail) {
            this.kind = kind != null ? kind : PromptKind.NONE;
            this.inviteId = inviteId;
            this.from = from == null ? "" : from;
            this.detail = detail == null ? "" : detail;
        }
    }

    private volatile PromptKind kind = PromptKind.NONE;
    private volatile long promptInviteId;
    private volatile String promptFrom = "";
    private volatile String promptDetail = "";
    private final Deque<Prompt> queue = new ArrayDeque<>();

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

    public int queueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    /**
     * Enqueue an invite. If no prompt is showing, activates it immediately and
     * returns it; otherwise returns null (caller should not replace an open UI).
     */
    public Prompt enqueue(final PromptKind kind, final long inviteId, final String from,
                          final String detail) {
        if (kind == null || kind == PromptKind.NONE) {
            return null;
        }
        final Prompt p = new Prompt(kind, inviteId, from, detail);
        synchronized (queue) {
            if (this.kind == PromptKind.NONE) {
                activate(p);
                return p;
            }
            queue.addLast(p);
            return null;
        }
    }

    public void showPartyInvite(final long inviteId, final String fromPlayer) {
        enqueue(PromptKind.PARTY, inviteId, fromPlayer, "");
    }

    public void showLocationInvite(final long inviteId, final String fromPlayer, final String displayName) {
        enqueue(PromptKind.LOCATION, inviteId, fromPlayer, displayName);
    }

    public void showJoinFightInvite(final long inviteId, final String fromPlayer, final String encounter) {
        enqueue(PromptKind.JOIN_FIGHT, inviteId, fromPlayer, encounter);
    }

    /**
     * Hide the current prompt and activate the next queued one (if any).
     * @return the next prompt to show, or null
     */
    public Prompt hideAndPollNext() {
        synchronized (queue) {
            kind = PromptKind.NONE;
            promptInviteId = 0L;
            promptFrom = "";
            promptDetail = "";
            final Prompt next = queue.pollFirst();
            if (next != null) {
                activate(next);
            }
            return next;
        }
    }

    public void hide() {
        synchronized (queue) {
            kind = PromptKind.NONE;
            promptInviteId = 0L;
            promptFrom = "";
            promptDetail = "";
            queue.clear();
        }
    }

    private void activate(final Prompt p) {
        kind = p.kind;
        promptInviteId = p.inviteId;
        promptFrom = p.from;
        promptDetail = p.detail;
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
     * Also drops matching entries from the queue.
     */
    public ExpiryResult tickExpiry(final CoopPartyState party, final CoopLocationPolicy location,
                                   final long nowMs, final long timeoutMs) {
        boolean partyExpired = false;
        boolean locationExpired = false;
        boolean dialogClosed = false;
        if (party != null && party.expireIfNeeded(nowMs, timeoutMs)) {
            partyExpired = true;
            synchronized (queue) {
                queue.removeIf(p -> p.kind == PromptKind.PARTY);
            }
            if (kind == PromptKind.PARTY) {
                hideAndPollNext();
                dialogClosed = true;
            }
        }
        if (location != null && location.expireIfNeeded(nowMs, timeoutMs)) {
            locationExpired = true;
            synchronized (queue) {
                queue.removeIf(p -> p.kind == PromptKind.LOCATION);
            }
            if (kind == PromptKind.LOCATION) {
                hideAndPollNext();
                dialogClosed = true;
            }
        }
        return new ExpiryResult(partyExpired, locationExpired, dialogClosed);
    }
}
