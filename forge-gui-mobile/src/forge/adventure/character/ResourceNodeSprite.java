package forge.adventure.character;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import forge.adventure.data.MaterialData;

/**
 * Overworld resource node (Package B). Stationary; tinted treasure sprite per material family.
 * Channel progress is drawn above the sprite while the player gathers.
 */
public class ResourceNodeSprite extends CharacterSprite {
    public static final String DEFAULT_ATLAS = "sprites/treasure.atlas";

    private static Texture pixelTex;

    private MaterialData material;
    private float channelProgress = -1f; // <0 = not channeling this node

    public ResourceNodeSprite(MaterialData material) {
        super(DEFAULT_ATLAS);
        setMaterial(material);
        collisionHeight = 1f;
    }

    public void setMaterial(MaterialData material) {
        this.material = material;
        setColor(tintForFamily(material != null ? material.family : null));
    }

    public MaterialData getMaterial() {
        return material;
    }

    public String getMaterialId() {
        return material != null ? material.id : null;
    }

    public void setChannelProgress(float progress01) {
        channelProgress = progress01;
    }

    public void clearChannelProgress() {
        channelProgress = -1f;
    }

    public static Color tintForFamily(String family) {
        if (family == null)
            return Color.WHITE;
        switch (family.toLowerCase()) {
            case "logs":
                return new Color(0.45f, 0.85f, 0.35f, 1f);
            case "ore":
                return new Color(0.95f, 0.45f, 0.3f, 1f);
            case "stone":
                return new Color(0.9f, 0.9f, 0.75f, 1f);
            case "herbs":
                return new Color(0.55f, 0.35f, 0.75f, 1f);
            case "crystal":
                return new Color(0.35f, 0.65f, 1f, 1f);
            case "scrap":
                return new Color(0.7f, 0.7f, 0.75f, 1f);
            default:
                return Color.WHITE;
        }
    }

    @Override
    void updateBoundingRect() {
        boundingRect.set(getX(), getY(), getWidth(), getHeight());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        if (channelProgress < 0f)
            return;
        Texture px = pixel();
        if (px == null)
            return;
        float w = Math.max(12f, getWidth());
        float h = 3f;
        float x = getX() + (getWidth() - w) / 2f;
        float y = getY() + getHeight() + 2f;
        Color prev = batch.getColor();
        batch.setColor(0f, 0f, 0f, 0.7f * parentAlpha);
        batch.draw(px, x - 1f, y - 1f, w + 2f, h + 2f);
        batch.setColor(0.15f, 0.15f, 0.15f, 0.9f * parentAlpha);
        batch.draw(px, x, y, w, h);
        batch.setColor(0.35f, 0.85f, 0.4f, parentAlpha);
        batch.draw(px, x, y, w * Math.max(0f, Math.min(1f, channelProgress)), h);
        batch.setColor(prev);
    }

    private static Texture pixel() {
        if (pixelTex == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            pixelTex = new Texture(pm);
            pm.dispose();
        }
        return pixelTex;
    }
}
