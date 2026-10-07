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
import forge.adventure.data.GymData;
import forge.adventure.data.GymFighterData;
import forge.adventure.data.GymListData;
import forge.adventure.data.GymRewardData;
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
 * Ascendant gym challenge: 2-3 trainers then a best-of-3 leader.
 * Gyms may be cleared in any order; leader decks scale with badges held.
 */
public class GymScene extends UIScene implements IAfterMatch {
    private static GymScene object;

    private final Table list;
    private GymData gym;
    private int fightIndex;
    private boolean challengeActive;
    private boolean awaitingDuel;
    private boolean rematch;
    private Dialog infoDialog;

    public static GymScene instance() {
        if (object == null)
            object = new GymScene();
        return object;
    }

    private GymScene() {
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

    public void open(String gymId) {
        gym = GymListData.get(gymId);
        fightIndex = 0;
        challengeActive = false;
        awaitingDuel = false;
        rematch = gym != null && Current.player().hasBadge(gym.badgeId);
        rebuild();
        Forge.switchScene(this);
    }

    @Override
    public void enter() {
        super.enter();
        rebuild();
    }

    private void rebuild() {
        list.clear();
        if (gym == null) {
            addLine("[BLACK]Gym data missing.");
            setChallengeEnabled(false);
            return;
        }
        boolean hasBadge = Current.player().hasBadge(gym.badgeId);
        addHeader(gym.name + "  [" + gym.theme + "]");
        addNote("Format: " + Current.player().getRunFormat()
                + "  ·  Badges held: " + Current.player().getBadgeCount());
        if (hasBadge) {
            addLine("[FOREST]☑ Badge earned: " + gym.badgeName);
            addNote(gym.badgePerk != null ? gym.badgePerk : "");
            addNote("Rematch available — harder decks, repeatable rewards.");
        } else {
            addLine("[DARK_GRAY]☐ Badge: " + gym.badgeName);
            addNote(gym.badgePerk != null ? "Perk: " + gym.badgePerk : "");
        }
        if (!GymUtil.selectedDeckLegalForRun()) {
            addLine("[FIREBRICK]" + GymUtil.legalityMessage());
        }
        addHeader("Trainers");
        GymFighterData[] trainers = gym.trainers != null ? gym.trainers : new GymFighterData[0];
        for (int i = 0; i < trainers.length; i++) {
            String mark = challengeActive && i < fightIndex ? "[FOREST]☑ "
                    : (challengeActive && i == fightIndex ? "[GOLD]► " : "[DARK_GRAY]○ ");
            addLine(mark + trainers[i].name + "  [%80](life " + trainers[i].life + ")");
        }
        if (gym.leader != null) {
            addHeader("Leader" + (gym.leader.gamesPerMatch > 1 ? " (best of " + gym.leader.gamesPerMatch + ")" : ""));
            String mark = challengeActive && fightIndex >= trainers.length ? "[GOLD]► " : "[DARK_GRAY]○ ";
            addLine(mark + gym.leader.name + "  [%80](life " + gym.leader.life + ")");
        }
        if (challengeActive)
            addNote("Defeat every fighter in order. Leaving forfeits the challenge.");
        setChallengeEnabled(!challengeActive && GymUtil.selectedDeckLegalForRun());
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
        if (gym == null || challengeActive || !Config.ascendant())
            return;
        if (!GymUtil.selectedDeckLegalForRun()) {
            showInfo("Illegal deck", GymUtil.legalityMessage());
            return;
        }
        rematch = Current.player().hasBadge(gym.badgeId);
        fightIndex = 0;
        challengeActive = true;
        rebuild();
        startNextFight();
    }

    private void onLeave() {
        if (challengeActive) {
            challengeActive = false;
            fightIndex = 0;
            Current.player().defeated();
            showInfo("Challenge forfeited", "The challenge is over. The normal defeat penalty was applied.");
            rebuild();
            return;
        }
        GameHUD.getInstance().getTouchpad().setVisible(false);
        Forge.switchToLast();
    }

    private int totalFights() {
        int n = gym.trainers != null ? gym.trainers.length : 0;
        if (gym.leader != null)
            n++;
        return n;
    }

    private GymFighterData fighterAt(int index) {
        int trainers = gym.trainers != null ? gym.trainers.length : 0;
        if (index < trainers)
            return gym.trainers[index];
        return gym.leader;
    }

    private void startNextFight() {
        if (!challengeActive || gym == null || awaitingDuel)
            return;
        if (fightIndex >= totalFights()) {
            onGymCleared();
            return;
        }
        GymFighterData fighter = fighterAt(fightIndex);
        int badges = Current.player().getBadgeCount();
        // Leader decks scale with badges already held (not counting this gym's badge if rematching).
        EnemyData data = GymUtil.toEnemy(fighter, badges, rematch);
        EnemySprite enemy = new EnemySprite(data);
        awaitingDuel = true;
        DuelScene duelScene = DuelScene.instance();
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new TransitionScreen(() -> {
            // isArena must stay false — Hard/Insane would replace gym decks with genetic AI.
            duelScene.initDuels(WorldStage.getInstance().getPlayerSprite(), enemy, false, null, false);
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
            Current.player().defeated();
            rebuild();
            showInfo("Defeated", "The gym challenge is over. Heal up and try again.");
            return;
        }
        // Trainers/leaders grant the usual Ascendant win dust via a quiet win() call.
        boolean boss = fightIndex >= (gym.trainers != null ? gym.trainers.length : 0);
        Current.player().win(boss);
        fightIndex++;
        rebuild();
        if (fightIndex >= totalFights())
            onGymCleared();
        else
            startNextFight();
    }

    private void onGymCleared() {
        challengeActive = false;
        boolean firstClear = Current.player().addBadge(gym.badgeId);
        GymRewardData table = (rematch || !firstClear) && gym.rematchRewards != null
                ? gym.rematchRewards : gym.rewards;
        Array<Reward> rewards = GymUtil.grantRewards(table);
        rebuild();
        String title = firstClear ? "Badge earned: " + gym.badgeName : "Gym rematch won!";
        if (rewards.size > 0) {
            RewardScene.instance().loadRewards(rewards, RewardScene.Type.EventReward, null);
            Forge.switchScene(RewardScene.instance());
        } else {
            showInfo(title, firstClear ? "The " + gym.badgeName + " is yours." : "Repeat rewards granted.");
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
