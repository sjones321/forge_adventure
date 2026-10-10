package forge.adventure.world;

import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;

/**
 * Lightweight descriptor for one plane in an Ascendant multi-plane save (MV1).
 * Full world grids stay in the current {@link World} or in inactive plane blobs —
 * never more than one plane loaded as a live {@link World}.
 */
public final class PlaneMeta {
    public static final String HOME_ID = "home";

    private String id = HOME_ID;
    private PlaneKind kind = PlaneKind.HOME;
    private long seed;
    private String worldConfigPath = Paths.WORLD;
    private String displayName = "Home";
    /** Optional set code for MV2; unused by MV1 except as a label. */
    private String setCode = "";
    /**
     * Package K: plane duel format ({@code Standard}/{@code Historic}/{@code Pauper}/{@code Commander}).
     * Empty means unset — {@link PlaneFormat#resolve(PlaneMeta)} applies the default.
     */
    private String format = "";
    private float playerPosX;
    private float playerPosY;

    public PlaneMeta() {
    }

    public PlaneMeta(String id, PlaneKind kind, long seed, String worldConfigPath, String displayName) {
        this.id = id != null && !id.isEmpty() ? id : HOME_ID;
        this.kind = kind != null ? kind : PlaneKind.HOME;
        this.seed = seed;
        this.worldConfigPath = worldConfigPath != null && !worldConfigPath.isEmpty()
                ? worldConfigPath : Paths.WORLD;
        this.displayName = displayName != null && !displayName.isEmpty() ? displayName : this.id;
    }

    public static PlaneMeta home(long seed) {
        return new PlaneMeta(HOME_ID, PlaneKind.HOME, seed, Paths.WORLD, "Home");
    }

    public String getId() {
        return id;
    }

    public PlaneKind getKind() {
        return kind;
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public String getWorldConfigPath() {
        return worldConfigPath != null && !worldConfigPath.isEmpty() ? worldConfigPath : Paths.WORLD;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSetCode() {
        return setCode != null ? setCode : "";
    }

    public void setSetCode(String setCode) {
        this.setCode = setCode != null ? setCode : "";
    }

    /** Canonical format token, or empty when never chosen (legacy / pending portal). */
    public String getFormat() {
        return format != null ? format : "";
    }

    public void setFormat(String format) {
        this.format = format != null ? format : "";
    }

    public float getPlayerPosX() {
        return playerPosX;
    }

    public float getPlayerPosY() {
        return playerPosY;
    }

    public void setPlayerPos(float x, float y) {
        this.playerPosX = x;
        this.playerPosY = y;
    }

    public SaveFileData save() {
        SaveFileData data = new SaveFileData();
        data.store("id", id);
        data.store("kind", kind.name());
        data.store("seed", seed);
        data.store("worldConfigPath", getWorldConfigPath());
        data.store("displayName", displayName != null ? displayName : id);
        data.store("setCode", getSetCode());
        data.store("format", getFormat());
        data.store("playerPosX", playerPosX);
        data.store("playerPosY", playerPosY);
        return data;
    }

    public void load(SaveFileData data) {
        if (data == null) {
            return;
        }
        String loadedId = data.readString("id");
        id = loadedId != null && !loadedId.isEmpty() ? loadedId : HOME_ID;
        kind = PlaneKind.fromSave(data.readString("kind"));
        seed = data.readLong("seed");
        String path = data.readString("worldConfigPath");
        worldConfigPath = path != null && !path.isEmpty() ? path : Paths.WORLD;
        String name = data.readString("displayName");
        displayName = name != null && !name.isEmpty() ? name : id;
        String code = data.readString("setCode");
        setCode = code != null ? code : "";
        if (data.containsKey("format")) {
            String fmt = data.readString("format");
            format = fmt != null ? fmt : "";
        } else {
            format = "";
        }
        playerPosX = data.readFloat("playerPosX");
        playerPosY = data.readFloat("playerPosY");
    }
}
