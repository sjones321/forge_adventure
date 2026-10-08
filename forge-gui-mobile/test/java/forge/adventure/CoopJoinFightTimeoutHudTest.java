package forge.adventure;

import forge.adventure.coop.CoopDuelRuntime;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Join-fight invite timeout must not dismiss an unrelated HUD dialog.
 */
public class CoopJoinFightTimeoutHudTest {

    @Test
    public void timeoutHidesHudOnlyWhenJoinFightDialogIsShowing() {
        Assert.assertTrue(CoopDuelRuntime.mayHideHudOnJoinFightTimeout(true));
        Assert.assertFalse(CoopDuelRuntime.mayHideHudOnJoinFightTimeout(false));
    }
}
