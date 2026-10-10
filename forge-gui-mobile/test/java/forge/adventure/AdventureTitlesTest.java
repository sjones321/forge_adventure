package forge.adventure;

import forge.adventure.util.AdventureTitles;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Visible title strings for Ascendant mode vs the stock Shandalar world.
 */
public class AdventureTitlesTest {

    @Test
    public void ascendantPlaneShowsOfficialGameTitle() {
        Assert.assertEquals(AdventureTitles.planeDisplayName(AdventureTitles.ASCENDANT_PLANE_ID),
                AdventureTitles.GAME_TITLE);
        Assert.assertEquals(AdventureTitles.GAME_TITLE, "Shandalar Ascendant");
        Assert.assertEquals(
                AdventureTitles.resolveWindowTitle(AdventureTitles.ASCENDANT_PLANE_ID, "1.2.3"),
                "Shandalar Ascendant - 1.2.3");
        Assert.assertEquals(
                AdventureTitles.planeIdFromDisplayName(AdventureTitles.GAME_TITLE),
                AdventureTitles.ASCENDANT_PLANE_ID);
    }

    @Test
    public void stockShandalarWorldDisplayUnchanged() {
        Assert.assertEquals(
                AdventureTitles.planeDisplayName(AdventureTitles.STOCK_SHANDALAR_PLANE_ID),
                "Shandalar");
        Assert.assertEquals(
                AdventureTitles.resolveWindowTitle(AdventureTitles.STOCK_SHANDALAR_PLANE_ID, "1.2.3"),
                "Forge - 1.2.3");
        Assert.assertEquals(AdventureTitles.planeIdFromDisplayName("Shandalar"), "Shandalar");
        Assert.assertFalse(AdventureTitles.isAscendantPlaneId("Shandalar"));
    }

    @Test
    public void completionistTitleIdMapsToShandalarDisplay() {
        Assert.assertEquals(AdventureTitles.titleDisplayName(AdventureTitles.COMPLETIONIST_TITLE_ID),
                "Shandalar Completionist");
        Assert.assertEquals(AdventureTitles.titleDisplayName("Centurion"), "Centurion");
        Assert.assertEquals(AdventureTitles.titleDisplayName(null), "");
        Assert.assertEquals(
                AdventureTitles.statusTitleLabelText(AdventureTitles.COMPLETIONIST_TITLE_ID),
                "[%80][DARK_GRAY]Shandalar Completionist");
        Assert.assertEquals(AdventureTitles.statusTitleLabelText(null), "");
    }
}
