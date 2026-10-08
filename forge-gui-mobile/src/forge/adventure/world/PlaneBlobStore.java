package forge.adventure.world;

import forge.adventure.util.SaveFileData;

import java.io.IOException;
import java.util.List;

/**
 * Storage for inactive MV1 plane blobs. Production uses side files next to the
 * save slot; tests may use an in-memory implementation.
 *
 * <p>Side-file layout (portable with the slot):
 * <pre>
 *   USER_ADVENTURE_DIR/&lt;adventurePack&gt;/
 *     N_save_slot.sav
 *     N_save_slot.planes/
 *       &lt;planeId&gt;.pln   — Deflater + ObjectOutputStream of a PlaneBlob SaveFileData
 *     auto_save.sav / auto_save.planes/ …
 *     quick_save.sav / quick_save.planes/ …
 * </pre>
 * The main {@code .sav} keeps only the current plane and the multiverse registry
 * (metas + inactive ids). Inactive payloads live in {@code *.planes/}.
 */
public interface PlaneBlobStore {
    void write(String planeId, SaveFileData blob) throws IOException;

    SaveFileData read(String planeId) throws IOException;

    boolean exists(String planeId);

    void delete(String planeId) throws IOException;

    List<String> listIds();
}
