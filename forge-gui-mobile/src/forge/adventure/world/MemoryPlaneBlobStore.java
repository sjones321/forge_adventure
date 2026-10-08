package forge.adventure.world;

import forge.adventure.util.SaveFileData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link PlaneBlobStore} for unit tests. */
public final class MemoryPlaneBlobStore implements PlaneBlobStore {
    private final LinkedHashMap<String, SaveFileData> blobs = new LinkedHashMap<>();

    @Override
    public void write(String planeId, SaveFileData blob) {
        if (planeId == null || blob == null) {
            throw new IllegalArgumentException("planeId and blob required");
        }
        blobs.put(planeId, blob);
    }

    @Override
    public SaveFileData read(String planeId) throws IOException {
        SaveFileData blob = blobs.get(planeId);
        if (blob == null) {
            throw new IOException("No plane blob for " + planeId);
        }
        return blob;
    }

    @Override
    public boolean exists(String planeId) {
        return planeId != null && blobs.containsKey(planeId);
    }

    @Override
    public void delete(String planeId) {
        blobs.remove(planeId);
    }

    @Override
    public List<String> listIds() {
        return new ArrayList<>(blobs.keySet());
    }

    public Map<String, SaveFileData> snapshot() {
        return new LinkedHashMap<>(blobs);
    }
}
