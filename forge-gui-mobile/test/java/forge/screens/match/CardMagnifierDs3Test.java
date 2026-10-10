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
import forge.screens.match.views.VAvatar;
import forge.screens.match.views.VStack;
import forge.toolbox.FButton;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FGestureAdapter;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DS3: duel CardZoom on right-click (card panels only); M / Shift+M for hover magnifier.
 * Drives {@link FGestureAdapter} and the same key handler {@link MatchScreen} uses.
 */
public class CardMagnifierDs3Test {

    private Input previousInput;
    private Graphics previousGraphics;
    private boolean shiftHeld;
    private boolean ctrlHeld;
    private boolean altHeld;
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
        ctrlHeld = false;
        altHeld = false;
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
    public void rightClickViaFGestureAdapterFiresRightClickNotTap() {
        final AtomicInteger tapCount = new AtomicInteger();
        final AtomicInteger rightClickCount = new AtomicInteger();
        final RecordingAdapter adapter = new RecordingAdapter(tapCount, rightClickCount);

        final boolean beforeToggle = Forge.magnifyToggle;
        final boolean beforeDetails = Forge.magnifyShowDetails;

        Assert.assertTrue(adapter.touchDown(40, 60, 0, Input.Buttons.RIGHT));
        Assert.assertTrue(adapter.touchUp(40, 60, 0, Input.Buttons.RIGHT));

        Assert.assertEquals(rightClickCount.get(), 1, "right-click must fire rightClick (CardZoom path)");
        Assert.assertEquals(tapCount.get(), 0, "right-click must not fire tap");
        Assert.assertEquals(Forge.magnifyToggle, beforeToggle, "right-click must not toggle magnifier");
        Assert.assertEquals(Forge.magnifyShowDetails, beforeDetails, "right-click must not toggle details");

        Assert.assertTrue(adapter.touchDown(40, 60, 0, Input.Buttons.RIGHT));
        Assert.assertTrue(adapter.touchUp(40, 60, 0, Input.Buttons.RIGHT));
        Assert.assertEquals(rightClickCount.get(), 2);
        Assert.assertEquals(tapCount.get(), 0);
        Assert.assertEquals(Forge.magnifyToggle, beforeToggle);
        Assert.assertEquals(Forge.magnifyShowDetails, beforeDetails);
    }

    @Test
    public void rightClickOnNonCardControlsDoesNothing() {
        // Default FDisplayObject.rightClick is a no-op; FButton / VAvatar / VStack item /
        // ItemManager rows must not override it to act like tap.
        Assert.assertFalse(declaresRightClick(FButton.class),
                "FButton must not implement rightClick (OK/Cancel/End Turn stay left-click)");
        Assert.assertFalse(declaresRightClick(VAvatar.class),
                "VAvatar must not implement rightClick (avatar select stays left-click)");
        Assert.assertFalse(declaresRightClick(VStack.StackInstanceDisplay.class),
                "VStack item must not implement rightClick");
        Assert.assertFalse(declaresRightClick(forge.itemmanager.views.ItemListView.class),
                "ItemManager list view must not implement rightClick");
        Assert.assertFalse(declaresRightClick(forge.itemmanager.views.ImageView.class),
                "ItemManager image view must not implement rightClick");
        // Runtime: default rightClick returns false (no activation).
        final FDisplayObject inert = new FDisplayObject() {
            @Override
            public void draw(forge.Graphics g) {
            }
        };
        Assert.assertFalse(inert.rightClick(1, 1));
    }

    @Test
    public void cardAreaPanelImplementsRightClickForCardZoom() {
        Assert.assertTrue(declaresRightClick(forge.screens.match.views.VCardDisplayArea.CardAreaPanel.class),
                "CardAreaPanel must implement rightClick → CardZoom");
    }

    @Test
    public void mKeyTogglesMagnifierAndPersistsWithStateHud() {
        Assert.assertTrue(Forge.magnifyToggle);
        Assert.assertTrue(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertFalse(Forge.magnifyToggle);
        Assert.assertEquals(CardMagnifierControls.getHudNote(), "Hover preview: off");
        Assert.assertFalse(FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_TOGGLE));

        Assert.assertTrue(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertTrue(Forge.magnifyToggle);
        Assert.assertEquals(CardMagnifierControls.getHudNote(), "Hover preview: on");

        // Survive "restart" via loadFromPreferences (magnify flag not forced on).
        Forge.magnifyToggle = true;
        Forge.magnify = false;
        CardMagnifierControls.loadFromPreferences();
        Assert.assertTrue(Forge.magnifyToggle);
        Assert.assertFalse(Forge.magnify, "loadFromPreferences must not set magnify=true");
    }

    @Test
    public void ctrlOrAltMDoesNotToggle() {
        Assert.assertTrue(Forge.magnifyToggle);
        ctrlHeld = true;
        Assert.assertFalse(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertTrue(Forge.magnifyToggle);
        ctrlHeld = false;
        altHeld = true;
        Assert.assertFalse(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertTrue(Forge.magnifyToggle);
    }

    @Test
    public void hoverPreviewUsesCleanArtNeverBattlefieldMarkers() {
        Assert.assertEquals(HoverMagnifierPreview.styleFor(false),
                HoverMagnifierPreview.Style.CLEAN_IMAGE);
        Assert.assertEquals(HoverMagnifierPreview.styleFor(true),
                HoverMagnifierPreview.Style.DETAILS_TEXT);
        Assert.assertFalse(HoverMagnifierPreview.drawsBattlefieldMarkersOnPreview(),
                "preview must not draw counters/P/T/damage overlays on art");
        Assert.assertFalse(CardRendererUtils.drawCracks(null, true));
        Assert.assertFalse(HoverMagnifierPreview.drawsDamageCracksOnPreview(null));
    }

    @Test
    public void detailsModeIsWhatReplacesArtWithTextNotBattlefieldMarkers() {
        // Steve: "markers covering preview" was a misdiagnosis — details mode replaces art.
        Assert.assertEquals(HoverMagnifierPreview.styleFor(true),
                HoverMagnifierPreview.Style.DETAILS_TEXT,
                "Shift+M details mode is the text panel covering card art");
        Assert.assertFalse(HoverMagnifierPreview.drawsBattlefieldMarkersOnPreview());
    }

    @Test
    public void shiftMTogglesDetailsAndRightClickNeverDoes() {
        Assert.assertFalse(Forge.magnifyShowDetails);
        shiftHeld = true;
        Assert.assertTrue(CardMagnifierControls.handleKeyDown(Input.Keys.M));
        Assert.assertTrue(Forge.magnifyShowDetails);
        Assert.assertEquals(CardMagnifierControls.getHudNote(), "Hover preview: details on");
        Assert.assertTrue(FModel.getPreferences().getPrefBoolean(FPref.UI_MAGNIFIER_SHOW_DETAILS));

        final boolean toggleBefore = Forge.magnifyToggle;
        final AtomicInteger taps = new AtomicInteger();
        final AtomicInteger rights = new AtomicInteger();
        final RecordingAdapter adapter = new RecordingAdapter(taps, rights);
        adapter.touchDown(10, 10, 0, Input.Buttons.RIGHT);
        adapter.touchUp(10, 10, 0, Input.Buttons.RIGHT);
        Assert.assertEquals(Forge.magnifyShowDetails, true, "right-click must not clear details");
        Assert.assertEquals(Forge.magnifyToggle, toggleBefore);
        Assert.assertEquals(rights.get(), 1);
        Assert.assertEquals(taps.get(), 0);
    }

    private static boolean declaresRightClick(final Class<?> type) {
        for (Method m : type.getDeclaredMethods()) {
            if ("rightClick".equals(m.getName()) && m.getParameterCount() == 2) {
                return true;
            }
        }
        return false;
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
            if (shiftHeld && (key == Input.Keys.SHIFT_LEFT || key == Input.Keys.SHIFT_RIGHT)) {
                return true;
            }
            if (ctrlHeld && (key == Input.Keys.CONTROL_LEFT || key == Input.Keys.CONTROL_RIGHT)) {
                return true;
            }
            if (altHeld && (key == Input.Keys.ALT_LEFT || key == Input.Keys.ALT_RIGHT)) {
                return true;
            }
            return false;
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

    /** Records rightClick/tap from the real {@link FGestureAdapter} path. */
    private static final class RecordingAdapter extends FGestureAdapter {
        private final AtomicInteger tapCount;
        private final AtomicInteger rightClickCount;

        RecordingAdapter(final AtomicInteger tapCount, final AtomicInteger rightClickCount) {
            super(16f, 0.25f, 0.5f, 0.15f);
            this.tapCount = tapCount;
            this.rightClickCount = rightClickCount;
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
        public boolean rightClick(float x, float y) {
            rightClickCount.incrementAndGet();
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
