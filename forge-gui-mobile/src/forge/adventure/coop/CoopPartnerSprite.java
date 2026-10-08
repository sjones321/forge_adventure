package forge.adventure.coop;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import forge.adventure.character.CharacterSprite;
import forge.adventure.util.Controls;
import forge.gamemodes.net.coop.CoopPositionSync;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;

/**
 * Remote co-op partner drawn on the overworld. Uses the peer's avatar id
 * (atlas path) — never a texture over the wire. Position is interpolated
 * toward the latest accepted sample.
 */
public final class CoopPartnerSprite extends CharacterSprite {
    private volatile String displayName = "";
    private volatile float targetX;
    private volatile float targetY;
    private volatile float targetFacing;
    private final GlyphLayout layout = new GlyphLayout();
    private BitmapFont nameFont;

    public CoopPartnerSprite(final String avatarId) {
        super(avatarId == null || avatarId.isEmpty()
                ? "sprites/heroes/Human_m.atlas"
                : avatarId);
        setVisible(false);
    }

    public void setDisplayName(final String name) {
        displayName = name == null ? "" : name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void applySample(final CoopPlayerMoveEvent sample) {
        if (sample == null) {
            return;
        }
        targetX = sample.getX();
        targetY = sample.getY();
        targetFacing = sample.getFacing();
        if (sample.getPlayerName() != null && !sample.getPlayerName().isEmpty()) {
            displayName = sample.getPlayerName();
        }
        final String avatar = sample.getAvatarId();
        if (avatar != null && !avatar.isEmpty() && !avatar.equals(getAtlasPath())) {
            load(avatar);
        }
        setVisible(true);
        if (!hasParent()) {
            setPosition(targetX, targetY);
        }
    }

    /** Smoothly approach the latest sample. Call from the GL thread. */
    public void interpolate(final float delta, final float interpRate) {
        if (!isVisible()) {
            return;
        }
        final float t = Math.min(1f, Math.max(0f, delta * Math.max(0.1f, interpRate)));
        setX(CoopPositionSync.lerp(getX(), targetX, t));
        setY(CoopPositionSync.lerp(getY(), targetY, t));
        final int ord = Math.round(targetFacing);
        final AnimationDirections[] dirs = AnimationDirections.values();
        if (ord >= 0 && ord < dirs.length) {
            setDirection(dirs[ord]);
        }
        setAnimation(AnimationTypes.Idle);
    }

    @Override
    public void draw(final Batch batch, final float parentAlpha) {
        super.draw(batch, parentAlpha);
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        if (nameFont == null) {
            try {
                nameFont = Controls.getBitmapFont("default");
            } catch (final Exception e) {
                return;
            }
        }
        if (nameFont == null) {
            return;
        }
        layout.setText(nameFont, displayName);
        final float x = getX() + (getWidth() - layout.width) / 2f;
        final float y = getY() + getHeight() + layout.height + 2f;
        final Color prev = nameFont.getColor().cpy();
        nameFont.setColor(1f, 1f, 1f, parentAlpha);
        nameFont.draw(batch, displayName, x, y);
        nameFont.setColor(prev);
    }
}
