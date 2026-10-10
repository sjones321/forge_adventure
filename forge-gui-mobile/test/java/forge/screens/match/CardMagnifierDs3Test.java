/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.screens.match;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import forge.Forge;
import forge.adventure.AdventureTestUserDir;
import forge.card.CardMagnifierControls;
import forge.card.HoverMagnifierPreview;
import forge.util.CardRendererUtils;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.toolbox.FGestureAdapter;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DS3: duel CardZoom on right-click; M / Shift+M for hover magnifier.
 * Drives {@link FGestureAdapter} and the same key handler {@link MatchScreen} uses.
 */
public class CardMagnifierDs3Test {

    private Input previousInput;
    private Graphics previousGraphics;
    private boolean shiftHeld;
    private boolean savedToggle;
    private boolean savedDetails;
    private boolean savedEnable;

    @BeforeClass
    public void stubGdxGraphicsOnce() {
        // FGestureAdapter → Utils clinit needs Gdx.graphics (headless suite has none).
        if (Gdx.graphics == null) {
            Gdx.graphics = stubGraphics();
        }
    }

    @BeforeMethod
    public void setUp() {
        final String testUser = System.getProperty(
                forge.localinstance.properties.ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        Assert.assertNotNull(testUser, "forge.test.userDir required");
        AdventureTestUserDir.assertConstantsUse(Paths.get(testUser));

        Assert.assertNotNull(FModel.getPreferences(), "FModel prefs from suite bootstrap");
        savedToggle = FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_TOGGLE);
        savedDetails = FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_SHOW_DETAILS);
        savedEnable = FModel.getPreferences().getPrefBoolean(FPref.UI_ENABLE_MAGNIFIER);

        FModel.getPreferences().setPref(FPref.UI_ENABLE_MAGNIFIER, true);
        FModel.getPreferences().setPref(FPref.UI_MAGNIFIER_TOGGLE, true);
        FModel.getPreferences().setPref(FPref.UI_MAGNIFIER_SHOW_DETAILS, false);
        FModel.getPreferences().save();
        CardMagnifierControls.loadFromPreferences();
        CardMagnifierControls.clearHudNote();

        previousInput = Gdx.input;
        previousGraphics = Gdx.graphics;
        if (Gdx.graphics == null) {
            Gdx.graphics = stubGraphics();
        }
        shiftHeld = false;
        Gdx.input = stubInput();
    }

    @AfterMethod
    public void tearDown() {
        Gdx.input = previousInput;
        Gdx.graphics = previousGraphics;
        CardMagnifierControls.clearHudNote();
        if (FModel.getPreferences() != null) {
            FModel.getPreferences().setPref(FPref.UI_ENABLE_MAGNIFIER, savedEnable);
            FModel.getPreferences().setPref(FPref.UI_MAGNIFIER_TOGGLE, savedToggle);
            FModel.getPreferences().setPref(FPref.UI_MAGNIFIER_SHOW_DETAILS, savedDetails);
            FModel.getPreferences().save();
            CardMagnifierControls.loadFromPreferences();
        }
    }

    @Test
    public void rightClickViaFGestureAdapterOpensZoomPathAndLeavesMagnifierUnchanged() {
        final AtomicInteger tapCount = new AtomicInteger();
        final RecordingAdapter adapter = new RecordingAdapter(tapCount);

        final boolean beforeToggle = Forge.magnifyToggle;
        final boolean beforeDetails = Forge.magnifyShowDetails;

        // Real input path: public FGestureAdapter.touchDown/Up with RIGHT (same as MainInputProcessor).
        Assert.assertTrue(adapter.touchDown(40, 60, 0, Input.Buttons.RIGHT));
        Assert.assertTrue(adapter.touchUp(40, 60, 0, Input.Buttons.RIGHT));

        Assert.assertEquals(tapCount.get(), 1, "right-click must fire tap (CardZoom path)");
        Assert.assertEquals(Forge.magnifyToggle, beforeToggle, "right-click must not toggle magnifier");
        Assert.assertEquals(Forge.magnifyShowDetails, beforeDetails, "right-click must not toggle details");

        // Second right-click also must not flip details (old double-right-click behaviour).
        Assert.assertTrue(adapter.touchDown(40, 60, 0, Input.Buttons.RIGHT));
        Assert.assertTrue(adapter.touchUp(40, 60, 0, Input.Buttons.RIGHT));
        Assert.assertEquals(tapCount.get(), 2);
        Assert.assertEquals(Forge.magnifyToggle, beforeToggle);
        Assert.assertEquals(Forge.magnifyShowDetails, beforeDetails);
    }

    @Test
    public void mKeyTogglesMagnifierAndPersists() {
        Assert.assertTrue(Forge.magnifyToggle);
        // Same handler MatchScreen.keyDown / ItemManager.keyDown call.
        Assert.assertTrue(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertFalse(Forge.magnifyToggle);
        Assert.assertEquals(CardMagnifierControls.getHudNote(), CardMagnifierControls.HUD_NOTE);
        Assert.assertFalse(FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_TOGGLE));

        // Survive "restart" via loadFromPreferences.
        Forge.magnifyToggle = true;
        CardMagnifierControls.loadFromPreferences();
        Assert.assertFalse(Forge.magnifyToggle);
    }

    @Test
    public void hoverPreviewUsesCleanArtNeverBattlefieldMarkers() {
        Assert.assertEquals(HoverMagnifierPreview.styleFor(false),
                HoverMagnifierPreview.Style.CLEAN_IMAGE);
        Assert.assertEquals(HoverMagnifierPreview.styleFor(true),
                HoverMagnifierPreview.Style.DETAILS_TEXT);
        Assert.assertFalse(HoverMagnifierPreview.drawsBattlefieldMarkersOnPreview(),
                "preview must not draw counters/P/T/damage overlays on art");
        // magnify=true must suppress damage cracks even when a damaged card is supplied.
        Assert.assertFalse(CardRendererUtils.drawCracks(null, true));
        Assert.assertFalse(HoverMagnifierPreview.drawsDamageCracksOnPreview(null));
    }

    @Test
    public void shiftMTogglesDetailsAndRightClickNeverDoes() {
        Assert.assertFalse(Forge.magnifyShowDetails);
        shiftHeld = true;
        Assert.assertTrue(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertTrue(Forge.magnifyShowDetails);
        Assert.assertEquals(CardMagnifierControls.getHudNote(), CardMagnifierControls.HUD_NOTE);
        Assert.assertTrue(FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_SHOW_DETAILS));

        final boolean toggleBefore = Forge.magnifyToggle;
        final AtomicInteger taps = new AtomicInteger();
        final RecordingAdapter adapter = new RecordingAdapter(taps);
        adapter.touchDown(10, 10, 0, Input.Buttons.RIGHT);
        adapter.touchUp(10, 10, 0, Input.Buttons.RIGHT);
        Assert.assertEquals(Forge.magnifyShowDetails, true, "right-click must not clear details");
        Assert.assertEquals(Forge.magnifyToggle, toggleBefore);
        Assert.assertEquals(taps.get(), 1);
    }

    private Input stubInput() {
        return (Input) Proxy.newProxyInstance(
                Input.class.getClassLoader(),
                new Class<?>[]{Input.class},
                (proxy, method, args) -> defaultStub(method.getName(), method.getReturnType(), args));
    }

    private Graphics stubGraphics() {
        return (Graphics) Proxy.newProxyInstance(
                Graphics.class.getClassLoader(),
                new Class<?>[]{Graphics.class},
                (proxy, method, args) -> {
                    final String name = method.getName();
                    if ("getWidth".equals(name)) {
                        return 1280;
                    }
                    if ("getHeight".equals(name)) {
                        return 720;
                    }
                    if ("getPpcX".equals(name) || "getPpcY".equals(name)) {
                        return 96f;
                    }
                    if ("getDensity".equals(name)) {
                        return 1f;
                    }
                    return defaultStub(name, method.getReturnType(), args);
                });
    }

    private Object defaultStub(final String name, final Class<?> rt, final Object[] args) {
        if ("isKeyPressed".equals(name)) {
            final int key = (Integer) args[0];
            return shiftHeld && (key == Input.Keys.SHIFT_LEFT || key == Input.Keys.SHIFT_RIGHT);
        }
        if ("getCurrentEventTime".equals(name)) {
            return System.nanoTime();
        }
        if ("isTouched".equals(name)) {
            return false;
        }
        if (rt == boolean.class) {
            return false;
        }
        if (rt == int.class || rt == short.class || rt == byte.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        if (rt == float.class || rt == double.class) {
            return 0f;
        }
        return null;
    }

    /** Records tap() from the real {@link FGestureAdapter} right-click path. */
    private static final class RecordingAdapter extends FGestureAdapter {
        private final AtomicInteger tapCount;

        RecordingAdapter(final AtomicInteger tapCount) {
            // Explicit sizes avoid Utils.AVG_FINGER_* if graphics stub races clinit.
            super(16f, 0.25f, 0.5f, 0.15f);
            this.tapCount = tapCount;
        }

        @Override
        public boolean press(float x, float y) {
            return false;
        }

        @Override
        public boolean longPress(float x, float y) {
            return false;
        }

        @Override
        public boolean release(float x, float y) {
            return false;
        }

        @Override
        public boolean tap(float x, float y, int count) {
            tapCount.incrementAndGet();
            return true;
        }

        @Override
        public boolean flick(float x, float y) {
            return false;
        }

        @Override
        public boolean fling(float velocityX, float velocityY) {
            return false;
        }

        @Override
        public boolean pan(float x, float y, float deltaX, float deltaY, boolean moreVertical) {
            return false;
        }

        @Override
        public boolean panStop(float x, float y) {
            return false;
        }

        @Override
        public boolean zoom(float x, float y, float amount) {
            return false;
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            return false;
        }
    }
}
