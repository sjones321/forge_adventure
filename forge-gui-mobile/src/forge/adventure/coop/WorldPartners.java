package forge.adventure.coop;

import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.SaveFileData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CO5: partner characters stored in the host's world save, keyed by guest profile id.
 * Host is the source of truth; guests never write this map.
 */
public final class WorldPartners {
    private final Map<String, SaveFileData> partners = new LinkedHashMap<>();

    public void clear() {
        partners.clear();
    }

    public boolean has(final String profileId) {
        final String id = CoopProfileId.sanitize(profileId);
        return !id.isEmpty() && partners.containsKey(id);
    }

    public SaveFileData get(final String profileId) {
        final String id = CoopProfileId.sanitize(profileId);
        if (id.isEmpty()) {
            return null;
        }
        return partners.get(id);
    }

    public void put(final String profileId, final SaveFileData playerData) {
        final String id = CoopProfileId.sanitize(profileId);
        if (id.isEmpty() || playerData == null) {
            return;
        }
        partners.put(id, playerData);
    }

    public void putPlayer(final String profileId, final AdventurePlayer player) {
        if (player == null) {
            return;
        }
        put(profileId, player.save());
    }

    public int size() {
        return partners.size();
    }

    /** Nested SaveFileData for the world save ({@code store("partners", …)}). */
    public SaveFileData save() {
        final SaveFileData out = new SaveFileData();
        for (final Map.Entry<String, SaveFileData> e : partners.entrySet()) {
            final String id = CoopProfileId.sanitize(e.getKey());
            if (id.isEmpty() || e.getValue() == null) {
                continue;
            }
            out.store(id, e.getValue());
        }
        return out;
    }

    /** Load from a world-save nested blob. Missing/empty → clear. */
    public void load(final SaveFileData data) {
        partners.clear();
        if (data == null || data.isEmpty()) {
            return;
        }
        for (final String key : data.keySet()) {
            final String id = CoopProfileId.sanitize(key);
            if (id.isEmpty()) {
                continue;
            }
            try {
                final SaveFileData player = data.readSubData(key);
                if (player != null) {
                    partners.put(id, player);
                }
            } catch (final OutOfMemoryError oom) {
                System.err.println("CO5 partners load OOM for " + id + ": " + oom);
            } catch (final Throwable t) {
                System.err.println("CO5 partners load skip " + id + ": " + t);
            }
        }
    }

    /** Snapshot of profile ids (tests / diagnostics). */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(partners.keySet());
    }
}
