package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: host-authoritative POI mutation (cleared enemies, opened chests, visit).
 * Guests mirror; never invent POI state.
 */
public class CoopPoiChangeEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum ChangeType { CLEARED_ENEMY, OPENED_CHEST, VISITED, CUSTOM }

    private final String poiId;
    private final ChangeType changeType;
    private final String detail;
    private final long objectId;

    public CoopPoiChangeEvent(final String poiId, final ChangeType changeType,
                              final String detail, final long objectId) {
        this.poiId = poiId == null ? "" : poiId;
        this.changeType = changeType == null ? ChangeType.CUSTOM : changeType;
        this.detail = detail == null ? "" : detail;
        this.objectId = objectId;
    }

    public String getPoiId() {
        return poiId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getDetail() {
        return detail;
    }

    public long getObjectId() {
        return objectId;
    }
}
