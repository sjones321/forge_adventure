package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: mid-session world hash diverged (e.g. after a failed
 * {@link CoopGateUpdateEvent} apply). Host answers with a full plane re-offer
 * ({@link forge.adventure.coop.CoopSession#offerCurrentPlaneToGuest}) that
 * echoes {@link #requestId} so the guest can match the offer and not confuse a
 * real host plane-follow with a resync. Plain data only. Guests rate-limit sends.
 */
public class CoopWorldResyncRequestEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

    private final String reason;
    private final String worldPlaneId;
    /** Guest-generated id; host echoes it on the quiet {@link CoopPlaneSwitchEvent}. */
    private final long requestId;

    public CoopWorldResyncRequestEvent(final String reason, final String worldPlaneId) {
        this(reason, worldPlaneId, 0L);
    }

    public CoopWorldResyncRequestEvent(final String reason, final String worldPlaneId,
                                       final long requestId) {
        this.reason = reason != null ? reason : "";
        this.worldPlaneId = worldPlaneId != null ? worldPlaneId : "";
        this.requestId = requestId;
    }

    public String getReason() {
        return reason != null ? reason : "";
    }

    public String getWorldPlaneId() {
        return worldPlaneId != null ? worldPlaneId : "";
    }

    public long getRequestId() {
        return requestId;
    }
}
