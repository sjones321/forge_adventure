package forge.adventure.character;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.ParticleEffect;
import com.badlogic.gdx.graphics.g2d.ParticleEmitter;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.MaterialData;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Overworld resource node (Package B). Stationary; uses Steve's node atlas when
 * {@link MaterialData#nodeAtlas}/{@link MaterialData#nodeRegion} are set, otherwise a tinted treasure sprite.
 * Ascendant ash vents and waters get cheap pooled looping particle FX while visible.
 */
public class ResourceNodeSprite extends CharacterSprite {
    public static final String DEFAULT_ATLAS = "sprites/treasure.atlas";

    private static Texture pixelTex;
    private static final Array<ParticleEffect> SMOKE_POOL = new Array<>();
    private static final Array<ParticleEffect> EMBER_POOL = new Array<>();
    private static final Array<ParticleEffect> SPARKLE_POOL = new Array<>();
    private static final int POOL_CAP = 8;

    private MaterialData material;
    private float channelProgress = -1f; // <0 = not channeling this node
    private float shakeTimer = 0f;
    private static final float SHAKE_TIME = 0.18f;

    private TextureRegion nodeRegion;
    private boolean useNodeArt;
    private ParticleEffect ambientA;
    private ParticleEffect ambientB;
    private boolean ambientActive;
    private float sparkleTimer;
    private boolean wasVisible;

    public ResourceNodeSprite(MaterialData material) {
        super(DEFAULT_ATLAS);
        setMaterial(material);
        collisionHeight = 1f;
    }

    public void setMaterial(MaterialData material) {
        this.material = material;
        useNodeArt = false;
        nodeRegion = null;
        if (material != null && material.nodeAtlas != null && !material.nodeAtlas.isEmpty()
                && material.nodeRegion != null && !material.nodeRegion.isEmpty()
                && Config.ascendant()) {
            try {
                TextureAtlas atlas = Config.instance().getAtlas(material.nodeAtlas);
                if (atlas != null) {
                    TextureAtlas.AtlasRegion region = atlas.findRegion(material.nodeRegion);
                    if (region != null) {
                        nodeRegion = region;
                        useNodeArt = true;
                        setColor(Color.WHITE);
                        setWidth(region.getRegionWidth());
                        setHeight(region.getRegionHeight());
                        releaseAmbient();
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Fall back to tinted treasure sprite.
            }
        }
        setColor(tintForFamily(material != null ? material.family : null));
        releaseAmbient();
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

    /** A gathering hit: shake the node briefly. */
    public void hit() {
        shakeTimer = SHAKE_TIME;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (shakeTimer > 0f)
            shakeTimer = Math.max(0f, shakeTimer - delta);
        updateAmbient(delta);
    }

    private void updateAmbient(float delta) {
        if (!Config.ascendant() || material == null) {
            releaseAmbient();
            return;
        }
        boolean visible = isVisible() && getStage() != null && getParent() != null;
        if (!visible) {
            if (wasVisible)
                releaseAmbient();
            wasVisible = false;
            return;
        }
        wasVisible = true;
        String nodeType = material.nodeType != null ? material.nodeType : "";
        String family = material.family != null ? material.family : "";
        boolean ashVent = "vent".equalsIgnoreCase(nodeType) || "ash".equalsIgnoreCase(family);
        boolean water = "water".equalsIgnoreCase(nodeType) || "waters".equalsIgnoreCase(family);

        if (ashVent) {
            ensureAshAmbient();
            tickAmbient(ambientA, delta, 0.35f);
            tickAmbient(ambientB, delta, 0.2f);
        } else if (water) {
            ensureWaterAmbient();
            sparkleTimer -= delta;
            if (ambientA != null) {
                if (sparkleTimer <= 0f) {
                    ambientA.start();
                    sparkleTimer = 1.6f + MathUtils.random(0f, 2.2f);
                }
                tickAmbient(ambientA, delta, 0.45f);
            }
        } else if (ambientActive) {
            releaseAmbient();
        }
    }

    private void ensureAshAmbient() {
        if (ambientActive && ambientA != null && ambientB != null)
            return;
        releaseAmbient();
        ambientA = borrow(SMOKE_POOL, Paths.EFFECT_ASH_VENT_SMOKE);
        ambientB = borrow(EMBER_POOL, Paths.EFFECT_ASH_VENT_EMBERS);
        scaleEmitters(ambientA, 0.35f);
        scaleEmitters(ambientB, 0.28f);
        if (ambientA != null)
            ambientA.start();
        if (ambientB != null)
            ambientB.start();
        ambientActive = true;
    }

    private void ensureWaterAmbient() {
        if (ambientActive && ambientA != null)
            return;
        releaseAmbient();
        ambientA = borrow(SPARKLE_POOL, Paths.EFFECT_WATER_SPARKLE);
        scaleEmitters(ambientA, 0.4f);
        sparkleTimer = MathUtils.random(0.2f, 1.2f);
        ambientActive = true;
    }

    private void tickAmbient(ParticleEffect effect, float delta, float yLift) {
        if (effect == null)
            return;
        effect.setPosition(getX() + getWidth() * 0.5f, getY() + getHeight() * yLift);
        effect.update(delta);
    }

    private void releaseAmbient() {
        if (ambientA != null) {
            recycle(ambientA, poolFor(ambientA));
            ambientA = null;
        }
        if (ambientB != null) {
            recycle(ambientB, poolFor(ambientB));
            ambientB = null;
        }
        ambientActive = false;
        sparkleTimer = 0f;
    }

    private static Array<ParticleEffect> poolFor(ParticleEffect effect) {
        // Heuristic: smoke pool first path, etc. — tag via emitter name.
        if (effect.getEmitters().size > 0) {
            String name = effect.getEmitters().first().getName();
            if (name != null) {
                if (name.toLowerCase().contains("smoke"))
                    return SMOKE_POOL;
                if (name.toLowerCase().contains("flame"))
                    return EMBER_POOL;
            }
        }
        return SPARKLE_POOL;
    }

    private static ParticleEffect borrow(Array<ParticleEffect> pool, String path) {
        if (pool.size > 0)
            return pool.pop();
        return loadPooledEffect(path);
    }

    private static void recycle(ParticleEffect effect, Array<ParticleEffect> pool) {
        if (effect == null)
            return;
        effect.reset();
        if (pool.size < POOL_CAP)
            pool.add(effect);
        else
            effect.dispose();
    }

    private static ParticleEffect loadPooledEffect(String path) {
        try {
            var file = Config.instance().getFile(path);
            if (file == null || !file.exists())
                return null;
            ParticleEffect effect = new ParticleEffect();
            effect.load(file, file.parent());
            return effect;
        } catch (Exception e) {
            return null;
        }
    }

    private static void scaleEmitters(ParticleEffect effect, float scale) {
        if (effect == null)
            return;
        for (ParticleEmitter emitter : effect.getEmitters()) {
            emitter.scaleSize(scale);
            // Keep continuous emitters cheap.
            if (emitter.getEmission().getHighMax() > 40f) {
                float hi = emitter.getEmission().getHighMax() * 0.25f;
                emitter.getEmission().setHigh(hi);
            }
        }
    }

    public static Color tintForFamily(String family) {
        if (family == null)
            return Color.WHITE;
        switch (family.toLowerCase()) {
            case "logs":
                return new Color(0.55f, 0.38f, 0.18f, 1f); // brown wood
            case "plants":
                return new Color(0.35f, 0.8f, 0.35f, 1f); // green
            case "ore":
                return new Color(0.75f, 0.75f, 0.8f, 1f); // metal grey
            case "ash":
                return new Color(0.95f, 0.4f, 0.2f, 1f); // fire red
            case "sacred_stone":
            case "stone":
                return new Color(0.95f, 0.92f, 0.7f, 1f); // pale gold / white
            case "dead":
            case "herbs":
                return new Color(0.45f, 0.25f, 0.55f, 1f); // black-purple
            case "waters":
            case "crystal":
                return new Color(0.3f, 0.6f, 1f, 1f); // blue
            case "scrap":
                return new Color(0.65f, 0.65f, 0.7f, 1f);
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
        float shake = shakeTimer > 0f
                ? MathUtils.sin(shakeTimer * 90f) * 1.5f * (shakeTimer / SHAKE_TIME) : 0f;
        float drawX = getX() + shake;
        float drawY = getY();

        if (useNodeArt && nodeRegion != null) {
            Color prev = batch.getColor();
            batch.setColor(prev.r, prev.g, prev.b, prev.a * parentAlpha);
            batch.draw(nodeRegion, drawX, drawY, getWidth(), getHeight());
            batch.setColor(prev);
        } else {
            if (shake != 0f)
                setX(getX() + shake);
            super.draw(batch, parentAlpha);
            if (shake != 0f)
                setX(getX() - shake);
        }

        if (ambientA != null)
            ambientA.draw(batch);
        if (ambientB != null)
            ambientB.draw(batch);

        if (channelProgress < 0f)
            return;
        Texture px = pixel();
        if (px == null)
            return;
        float w = Math.max(12f, getWidth());
        float h = 3f;
        float x = drawX + (getWidth() - w) / 2f;
        float y = drawY + getHeight() + 2f;
        Color prev = batch.getColor();
        batch.setColor(0f, 0f, 0f, 0.7f * parentAlpha);
        batch.draw(px, x - 1f, y - 1f, w + 2f, h + 2f);
        batch.setColor(0.15f, 0.15f, 0.15f, 0.9f * parentAlpha);
        batch.draw(px, x, y, w, h);
        batch.setColor(0.35f, 0.85f, 0.4f, parentAlpha);
        batch.draw(px, x, y, w * Math.max(0f, Math.min(1f, channelProgress)), h);
        batch.setColor(prev);
    }

    @Override
    public boolean remove() {
        releaseAmbient();
        return super.remove();
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
