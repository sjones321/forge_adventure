package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.GymFighterData;
import forge.adventure.data.GymListData;
import forge.adventure.data.GymRewardData;
import forge.adventure.data.LeagueData;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.IAfterMatch;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.GymUtil;
import forge.adventure.util.Reward;
import forge.gui.FThreads;
import forge.screens.TransitionScreen;
import forge.util.ScreenUtil;

/**
 * Ascendant League: Elite Four then Champion, each best of 3, no healing between matches.
 * Unlocked with all eight gym badges. Rematches unlock after the first clear.
 */
public class LeagueScene extends UIScene implements IAfterMatch {
    private static LeagueScene object;

    private final Table list;
    private LeagueData league;
    private Array<GymFighterData> lineup = new Array<>();
    private int fightIndex;
    private boolean challengeActive;
    private boolean awaitingDuel;
    private boolean rematch;
    private int lifeAtStart;
    private Dialog infoDialog;

    public static LeagueScene instance() {
        if (object == null)
            object = new LeagueScene();
        return object;
    }

    private LeagueScene() {
        super(Forge.isLandscapeMode() ? "ui/gym.json" : "ui/gym_portrait.json");
        Window scrollWindow = ui.findActor("scrollWindow");
        if (scrollWindow != null)
            scrollWindow.setTouchable(Touchable.disabled);
        Table root = ui.findActor("skillList");
        ui.onButtonPress("return", this::onLeave);
        ui.onButtonPress("challenge", this::onChallenge);
        list = new Table(Controls.getSkin());
        if (root != null) {
            ScrollPane scroller = new ScrollPane(list);
            scroller.setScrollingDisabled(true, false);
            root.add(scroller).fill().expand();
        }
    }

    public void open() {
        league = GymListData.getLeague();
        fightIndex = 0;
        challengeActive = false;
        awaitingDuel = false;
        rematch = Current.player().isLeagueCleared();
        rebuildLineup();
        rebuild();
        Forge.switchScene(this);
    }

    private void rebuildLineup() {
        lineup.clear();
        if (league == null)
            return;
        if (league.eliteFour != null) {
            for (GymFighterData f : league.eliteFour)
                lineup.add(f);
        }
        if (league.champion != null)
            lineup.add(league.champion);
    }

    @Override
    public void enter() {
        super.enter();
        rebuild();
    }

    private void rebuild() {
        list.clear();
        if (league == null) {
            addLine("[BLACK]League data missing.");
            setChallengeEnabled(false);
            return;
        }
        addHeader(league.name);
        addNote("Format: " + Current.player().getRunFormat()
                + "  ·  Badges: " + Current.player().getBadgeCount() + "/8");
        int need = Math.max(1, Config.instance().getConfigData().leagueBadgeRequirement);
        if (Current.player().getBadgeCount() < need || !Current.player().hasAllGymBadges()) {
            addLine("[FIREBRICK]Earn all " + need + " gym badges to challenge the League.");
            setChallengeEnabled(false);
            return;
        }
        if (Current.player().isLeagueCleared())
            addLine("[FOREST]☑ League Champion title earned — rematches available.");
        else
            addLine("[DARK_GRAY]☐ Defeat the Elite Four and the Champion.");
        addNote("Best of 3 each. No healing between matches.");
        if (!GymUtil.selectedDeckLegalForRun())
            addLine("[FIREBRICK]" + GymUtil.legalityMessage());
        addHeader("Lineup");
        for (int i = 0; i < lineup.size; i++) {
            GymFighterData f = lineup.get(i);
            boolean isChamp = league.champion != null && f == league.champion;
            String role = isChamp ? "Champion" : "Elite Four";
            String mark = challengeActive && i < fightIndex ? "[FOREST]☑ "
                    : (challengeActive && i == fightIndex ? "[GOLD]► " : "[DARK_GRAY]○ ");
            addLine(mark + role + ": " + f.name + "  [%80]Bo" + f.gamesPerMatch);
        }
        if (challengeActive)
            addNote("Life is not restored between matches. Forfeit returns you to town.");
        setChallengeEnabled(!challengeActive && GymUtil.selectedDeckLegalForRun()
                && Current.player().hasAllGymBadges());
        TextraButton leave = ui.findActor("return");
        if (leave != null)
            leave.setText(challengeActive ? "Forfeit" : "Leave");
    }

    private void setChallengeEnabled(boolean on) {
        TextraButton challenge = ui.findActor("challenge");
        if (challenge != null) {
            challenge.setDisabled(!on);
            challenge.setText(rematch && !challengeActive ? "Rematch" : "Challenge");
        }
    }

    private void onChallenge() {
        if (league == null || challengeActive || !Config.ascendant())
            return;
        if (!Current.player().hasAllGymBadges()) {
            showInfo("Not yet", "Earn all eight gym badges first.");
            return;
        }
        if (!GymUtil.selectedDeckLegalForRun()) {
            showInfo("Illegal deck", GymUtil.legalityMessage());
            return;
        }
        rematch = Current.player().isLeagueCleared();
        fightIndex = 0;
        challengeActive = true;
        lifeAtStart = Current.player().getLife();
        rebuild();
        startNextFight();
    }

    private void onLeave() {
        if (challengeActive) {
            challengeActive = false;
            fightIndex = 0;
            // Restore life to what they entered with so a mid-League forfeit is not a soft-lock at 1 life.
            Current.player().heal(Math.max(0, lifeAtStart - Current.player().getLife()));
            showInfo("League forfeited", "Your life was restored to when you entered.");
            rebuild();
            return;
        }
        GameHUD.getInstance().getTouchpad().setVisible(false);
        Forge.switchToLast();
    }

    private void startNextFight() {
        if (!challengeActive || awaitingDuel)
            return;
        if (fightIndex >= lineup.size) {
            onLeagueCleared();
            return;
        }
        GymFighterData fighter = lineup.get(fightIndex);
        EnemyData data = GymUtil.toEnemy(fighter, Current.player().getBadgeCount(), rematch);
        EnemySprite enemy = new EnemySprite(data);
        awaitingDuel = true;
        DuelScene duelScene = DuelScene.instance();
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new TransitionScreen(() -> {
            duelScene.initDuels(WorldStage.getInstance().getPlayerSprite(), enemy, true, null);
            Forge.switchScene(duelScene);
        }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "",
                Current.player().avatar(), enemy.getAtlasPath(), Current.player().getName(), enemy.getName())));
    }

    @Override
    public void setWinner(boolean winner, boolean isArena) {
        awaitingDuel = false;
        if (!challengeActive)
            return;
        if (!winner) {
            challengeActive = false;
            fightIndex = 0;
            rebuild();
            showInfo("Defeated", "The League challenge ends here. No healing was granted mid-run.");
            return;
        }
        boolean isChamp = fightIndex == lineup.size - 1;
        Current.player().win(isChamp);
        // Intentionally no heal — League rules.
        fightIndex++;
        rebuild();
        if (fightIndex >= lineup.size)
            onLeagueCleared();
        else
            startNextFight();
    }

    private void onLeagueCleared() {
        challengeActive = false;
        boolean first = !Current.player().isLeagueCleared();
        Current.player().setLeagueCleared(true);
        Current.player().fullHeal();
        GymRewardData table = (!first || rematch) && league.rematchRewards != null
                ? league.rematchRewards : league.rewards;
        Array<Reward> rewards = GymUtil.grantRewards(table);
        rebuild();
        if (rewards.size > 0) {
            RewardScene.instance().loadRewards(rewards, RewardScene.Type.EventReward, null);
            Forge.switchScene(RewardScene.instance());
        } else {
            showInfo(first ? "League Champion!" : "League rematch won!",
                    first ? "The title is yours." : "Repeat rewards granted.");
        }
    }

    private void addHeader(String text) {
        TypingLabel l = Controls.newTypingLabel("[BLACK][%110]" + text);
        l.skipToTheEnd();
        list.add(l).align(Align.left).padLeft(8).padTop(8);
        list.row();
    }

    private void addNote(String text) {
        if (text == null || text.isEmpty())
            return;
        TypingLabel l = Controls.newTypingLabel("[DARK_GRAY][%85]" + text);
        l.skipToTheEnd();
        l.setWrap(true);
        list.add(l).align(Align.left).growX().padLeft(8).padRight(8);
        list.row();
    }

    private void addLine(String text) {
        TypingLabel l = Controls.newTypingLabel(text);
        l.skipToTheEnd();
        l.setWrap(true);
        list.add(l).align(Align.left).growX().padLeft(18).padRight(8);
        list.row().padTop(1);
    }

    private void showInfo(String title, String body) {
        if (infoDialog != null)
            removeDialog();
        infoDialog = createGenericDialog(title, "\n" + body,
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null);
        showDialog(infoDialog);
    }

    @Override
    public void dispose() {
    }

    @Override
    public boolean back() {
        onLeave();
        return true;
    }
}
