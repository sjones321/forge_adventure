package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: mid-session world hash diverged (e.g. after a failed
 * {@link CoopGateUpdateEvent} apply). Host answers with a full plane re-offer
 * ({@link forge.adventure.coop.CoopSession#offerCurrentPlaneToGuest}).
 * Plain data only. Guests rate-limit sends.
 */
public class CoopWorldResyncRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String reason;
    private final String worldPlaneId;

    public CoopWorldResyncRequestEvent(final String reason, final String worldPlaneId) {
        this.reason = reason != null ? reason : "";
        this.worldPlaneId = worldPlaneId != null ? worldPlaneId : "";
    }

    public String getReason() {
        return reason != null ? reason : "";
    }

    public String getWorldPlaneId() {
        return worldPlaneId != null ? worldPlaneId : "";
    }
}
