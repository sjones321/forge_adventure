package forge.adventure;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import forge.adventure.util.AdventureDialogs;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * AdventureDialogs: MapStage/UIScene hosts instead of FOptionPane under Adventure.render.
 * Isolation: no disk / userDir touch — pure actor attach + empty-options paths.
 */
public class AdventureDialogsTest {

    @Test
    public void addDialogActorAttachesToHostGroup() {
        final Group host = new Group();
        final Actor dialog = new Actor();
        Assert.assertTrue(AdventureDialogs.addDialogActor(host, dialog),
                "helper must attach the dialog actor to the host group");
        Assert.assertSame(dialog.getParent(), host);
        Assert.assertEquals(host.getChildren().size, 1);
        Assert.assertSame(host.getChildren().first(), dialog);
    }

    @Test
    public void addDialogActorRejectsNulls() {
        Assert.assertFalse(AdventureDialogs.addDialogActor(null, new Actor()));
        Assert.assertFalse(AdventureDialogs.addDialogActor(new Group(), null));
    }

    @Test
    public void showMapOptionsReturnsFalseForEmptyOptions() {
        // Empty options must not touch MapStage/WorldStage singletons.
        Assert.assertFalse(AdventureDialogs.showMapOptions("msg", List.of(), idx -> {
            throw new AssertionError("callback must not run");
        }));
        Assert.assertFalse(AdventureDialogs.showMapOptions("msg", null, idx -> {
            throw new AssertionError("callback must not run");
        }));
    }

    @Test
    public void hudNoteDoesNotThrowWhenHudUnavailable() {
        AdventureDialogs.hudNote("notice");
        AdventureDialogs.hudNote("");
        AdventureDialogs.hudNote(null);
    }
}
