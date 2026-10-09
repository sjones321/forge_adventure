package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.world.WorldSave;
import forge.assets.FSkinTexture;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopSessionCode;
import forge.gamemodes.net.server.FServerManager;
import forge.gui.GuiBase;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.screens.TransitionScreen;
import forge.sound.SoundSystem;
import forge.util.ZipUtil;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * First scene after the splash screen
 */
public class StartScene extends UIScene {
    private static StartScene object;
    Dialog exitDialog, backupDialog, zipDialog, unzipDialog, joinDialog, hostingDialog;
    TextraButton saveButton, resumeButton, continueButton, hostButton, joinButton;
    TextField joinAddressField, joinCodeField;
    TypingLabel version = Controls.newTypingLabel("{GRADIENT}[%80]v." + Forge.getDeviceAdapter().getVersionString() + "{ENDGRADIENT}");


    public StartScene() {
        super(Forge.isLandscapeMode() ? "ui/start_menu.json" : "ui/start_menu_portrait.json");
        ui.onButtonPress("Start", StartScene.this::NewGame);
        ui.onButtonPress("Start+", this::NewGamePlus);
        ui.onButtonPress("Load", StartScene.this::Load);
        ui.onButtonPress("Save", StartScene.this::Save);
        ui.onButtonPress("Resume", StartScene.this::Resume);
        ui.onButtonPress("Continue", StartScene.this::Continue);
        ui.onButtonPress("Settings", StartScene.this::settings);
        ui.onButtonPress("Backup", StartScene.this::backup);
        ui.onButtonPress("Host", StartScene.this::hostCoop);
        ui.onButtonPress("Join", StartScene.this::joinCoop);
        ui.onButtonPress("Exit", StartScene.this::Exit);
        ui.onButtonPress("Switch", StartScene.this::switchToClassic);


        saveButton = ui.findActor("Save");
        resumeButton = ui.findActor("Resume");
        continueButton = ui.findActor("Continue");
        hostButton = ui.findActor("Host");
        joinButton = ui.findActor("Join");

        saveButton.setVisible(false);
        resumeButton.setVisible(false);
        if (hostButton != null) {
            hostButton.setVisible(false);
        }
        if (joinButton != null) {
            joinButton.setVisible(false);
        }
        version.setHeight(5);
        version.skipToTheEnd();
        ui.addActor(version);
    }

    public static StartScene instance() {
        if (object == null)
            object = new StartScene();
        return object;
    }

    public boolean NewGame() {
        if (blockGuestSlotUi("New Game")) {
            return true;
        }
        Forge.switchScene(NewGameScene.instance());
        return true;
    }

    public boolean Save() {
        if (blockGuestSlotUi("Save")) {
            return true;
        }
        if (TileMapScene.instance().currentMap().isInMap()) {
            Dialog noSave = createGenericDialog("", Forge.getLocalizer().getMessage("lblGameNotSaved"), Forge.getLocalizer().getMessage("lblOK"),null, null, null);
            showDialog(noSave);
        } else {
            SaveLoadScene.instance().setMode(SaveLoadScene.Modes.Save);
            Forge.switchScene(SaveLoadScene.instance());
        }
        return true;
    }

    public boolean Load() {
        if (blockGuestSlotUi("Load")) {
            return true;
        }
        SaveLoadScene.instance().setMode(SaveLoadScene.Modes.Load);
        Forge.switchScene(SaveLoadScene.instance());
        return true;
    }

    /** CO5: guests must leave co-op before Load / New Game (partner is host-world-bound). */
    private boolean blockGuestSlotUi(final String action) {
        if (!CoopSession.get().isGuestSession()) {
            return false;
        }
        showDialog(createGenericDialog("Co-op",
                action + " is unavailable while joined as a co-op guest.\nLeave the session first.",
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
        return true;
    }

    public boolean Resume() {
        if (MapStage.getInstance().isInMap())
            Forge.switchScene(TileMapScene.instance());
        else
            Forge.switchScene(GameScene.instance());
        GameHUD.getInstance().getTouchpad().setVisible(false);
        return true;
    }

    boolean loaded = false;

    public boolean Continue() {
        final String lastActiveSave = Config.instance().getSettingData().lastActiveSave;

        if (WorldSave.isSafeFile(lastActiveSave)) {
            if (loaded)
                return true;
            loaded = true;
            try {
                Forge.setTransitionScreen(new TransitionScreen(() -> {
                    loaded = false;
                    if (WorldSave.load(WorldSave.filenameToSlot(lastActiveSave))) {
                        SoundSystem.instance.changeBackgroundTrack();
                        Forge.switchScene(GameScene.instance());
                    } else {
                        Forge.clearTransitionScreen();
                    }
                }, null, false, true, Forge.getLocalizer().getMessage("lblLoadingWorld")));
            } catch (Exception e) {
                loaded = false;
                Forge.clearTransitionScreen();
            }
        }

        return true;
    }

    public boolean settings() {
        Forge.switchScene(SettingsScene.instance());
        return true;
    }

    /**
     * Ascendant co-op Host (CO1). Requires a loaded world; starts the overworld
     * listener and shows a visible Hosting status with session code and Stop.
     */
    public boolean hostCoop() {
        if (!Config.ascendant()) {
            return true;
        }
        if (WorldSave.getCurrentSave().getWorld().getData() == null) {
            showDialog(createGenericDialog("Co-op",
                    "Load or Continue a game before hosting.\nThe host's save owns the world.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            return true;
        }
        if (CoopSession.get().getState() == CoopSession.State.HOSTING
                || CoopSession.get().getState() == CoopSession.State.READY) {
            showHostingDialog();
            return true;
        }
        final boolean skipUPnP = Config.instance().getConfigData().coopSkipUPnP;
        new Thread(() -> {
            try {
                CoopSession.get().ensureConsoleStatusListener();
                CoopSession.get().host(skipUPnP);
                Gdx.app.postRunnable(this::showHostingDialog);
            } catch (final Exception e) {
                Gdx.app.postRunnable(() -> showDialog(createGenericDialog("Co-op",
                        "Failed to host:\n" + e.getMessage(),
                        Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null)));
            }
        }, "Coop-Host").start();
        return true;
    }

    private void showHostingDialog() {
        final StringBuilder msg = new StringBuilder();
        msg.append("HOSTING Ascendant co-op\n\n");
        msg.append("Session code: ").append(CoopSession.get().getSessionCode()).append("\n");
        msg.append("(guest must enter this ").append(CoopPorts.SESSION_CODE_LENGTH)
                .append("-character code)\n\n");
        msg.append("Overworld port: ").append(CoopSession.get().getOverworldPort()).append('\n');
        final String bind = CoopSession.get().getBindAddress();
        if (bind != null && !bind.isEmpty()) {
            msg.append("Bound to: ").append(bind).append('\n');
        } else {
            msg.append("Bound to: all interfaces\n");
        }
        if (Config.instance().getConfigData().coopSkipUPnP) {
            msg.append("UPnP skipped (Tailscale / manual firewall).\n");
        }
        msg.append("\nAddresses for your guest:\n");
        for (final Map.Entry<String, String> e : FServerManager.getAllLocalAddresses().entrySet()) {
            msg.append(e.getKey()).append(": ")
                    .append(e.getValue()).append(':')
                    .append(CoopSession.get().getOverworldPort()).append('\n');
        }
        msg.append("\nGuests play a partner character stored in your world save (CO5).");
        if (CoopSession.get().getLastError() != null
                && !CoopSession.get().getLastError().isEmpty()) {
            msg.append("\n\nLast reject: ").append(CoopSession.get().getLastError());
        }
        hostingDialog = createGenericDialog("Hosting", msg.toString(),
                Forge.getLocalizer().getMessage("lblOK"), "Stop",
                this::removeDialog,
                this::confirmStopHosting);
        // One-click copy so the guest can paste instead of typing (address + code in one line).
        final String joinInfo = coopJoinInfo();
        hostingDialog.getContentTable().row();
        hostingDialog.getContentTable().add(Controls.newTextButton("Copy join info", () -> {
            Gdx.app.getClipboard().setContents(joinInfo);
            showCoopCopied("Copied: " + joinInfo);
        })).pad(2);
        hostingDialog.getContentTable().add(Controls.newTextButton("Copy code", () -> {
            Gdx.app.getClipboard().setContents(CoopSession.get().getSessionCode());
            showCoopCopied("Copied code: " + CoopSession.get().getSessionCode());
        })).pad(2);
        showDialog(hostingDialog);
    }

    /** "address:port CODE", preferring the Tailscale (100.x) address. */
    private static String coopJoinInfo() {
        String best = null;
        for (final String addr : FServerManager.getAllLocalAddresses().values()) {
            if (addr == null || addr.isEmpty())
                continue;
            if (addr.startsWith("100.")) {
                best = addr;
                break;
            }
            if (best == null)
                best = addr;
        }
        final String bind = CoopSession.get().getBindAddress();
        if (bind != null && !bind.isEmpty())
            best = bind;
        return (best != null ? best : "?") + ":" + CoopSession.get().getOverworldPort()
                + " " + CoopSession.get().getSessionCode();
    }

    private void showCoopCopied(final String text) {
        try {
            forge.adventure.stage.GameHUD.getInstance().addNotification(text);
        } catch (final Exception ignored) {
            System.out.println(text);
        }
    }

    /** Fills the join fields from a pasted "address:port CODE" (either part may be missing). */
    private void pasteJoinInfo() {
        final String clip = Gdx.app.getClipboard().getContents();
        if (clip == null || clip.trim().isEmpty())
            return;
        for (final String token : clip.trim().split("\s+")) {
            if (token.contains(".") || token.contains(":"))
                joinAddressField.setText(token);
            else if (CoopSessionCode.normalize(token).length() == CoopPorts.SESSION_CODE_LENGTH)
                joinCodeField.setText(token);
        }
    }

    /** Ascendant co-op Join (CO1). Address + session code from the host screen. */
    public boolean joinCoop() {
        if (!Config.ascendant()) {
            return true;
        }
        if (WorldSave.getCurrentSave().getWorld().getData() == null) {
            showDialog(createGenericDialog("Co-op",
                    "Load or Continue a game before joining so the client can rebuild the host world.\n"
                            + "Your solo save is never modified — you play a partner character in the host's world.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            return true;
        }
        if (joinAddressField == null) {
            joinAddressField = Controls.newTextField("100.");
        }
        if (joinCodeField == null) {
            joinCodeField = Controls.newTextField("");
        }
        // Rebuild join dialog each time so fields stay current.
        joinDialog = createGenericDialog("Co-op Join", null,
                Forge.getLocalizer().getMessage("lblOK"),
                Forge.getLocalizer().getMessage("lblAbort"),
                () -> {
                    final String address = joinAddressField.getText();
                    final String code = joinCodeField.getText();
                    removeDialog();
                    connectJoin(address, code);
                },
                this::removeDialog);
        joinDialog.getContentTable().add(Controls.newLabel(
                "Host address (Tailscale 100.x or LAN).\nDefault port " + CoopPorts.OVERWORLD_PORT)).colspan(2);
        joinDialog.getContentTable().row();
        joinDialog.getContentTable().add(joinAddressField).fillX().expandX().colspan(2);
        joinDialog.getContentTable().row();
        joinDialog.getContentTable().add(Controls.newLabel("Session code from host:")).colspan(2);
        joinDialog.getContentTable().row();
        joinDialog.getContentTable().add(joinCodeField).fillX().expandX().colspan(2);
        joinDialog.getContentTable().row();
        joinDialog.getContentTable().add(Controls.newTextButton("Paste join info", this::pasteJoinInfo))
                .colspan(2).pad(2);
        joinDialog.getContentTable().row();
        showDialog(joinDialog);
        return true;
    }

    private void connectJoin(final String address, String sessionCode) {
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        String target = address.trim();
        // The whole "address:port CODE" line pasted into the address box.
        if (target.contains(" ") && (sessionCode == null || sessionCode.trim().isEmpty())) {
            final String[] parts = target.split("\s+");
            target = parts[0];
            sessionCode = parts[parts.length - 1];
        }
        if ("100.".equals(target)) {
            final String typed = SOptionPane.showInputDialog(
                    "Enter host address (Tailscale 100.x.y.z or LAN IP)",
                    "Co-op Join");
            if (typed == null || typed.trim().isEmpty()) {
                return;
            }
            target = typed.trim();
        }
        String code = sessionCode != null ? sessionCode.trim() : "";
        if (code.isEmpty()) {
            final String typed = SOptionPane.showInputDialog(
                    "Enter the session code shown on the host",
                    "Co-op Join");
            if (typed == null || typed.trim().isEmpty()) {
                return;
            }
            code = typed.trim();
        }
        final String joinTarget = target;
        final String joinCode = code;
        new Thread(() -> {
            try {
                CoopSession.get().ensureConsoleStatusListener();
                CoopSession.get().join(joinTarget, joinCode);
                Gdx.app.postRunnable(() -> {
                    final String statusNote = CoopSession.get().getState() == CoopSession.State.REJECTED
                            ? "Rejected: " + CoopSession.get().getLastError()
                            : "Connecting to " + joinTarget + "…\nWrong code or version → host refuses.";
                    showDialog(createGenericDialog("Co-op Join", statusNote,
                            Forge.getLocalizer().getMessage("lblOK"), "Disconnect",
                            this::removeDialog,
                            () -> {
                                CoopSession.get().disconnect();
                                removeDialog();
                            }));
                });
            } catch (final Exception e) {
                Gdx.app.postRunnable(() -> showDialog(createGenericDialog("Co-op",
                        "Failed to join:\n" + e.getMessage(),
                        Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null)));
            }
        }, "Coop-Join").start();
    }

    public boolean backup() {
        if (Forge.getDeviceAdapter().needFileAccess()) {
            Forge.getDeviceAdapter().requestFileAcces();
            return true;
        }
        if (backupDialog == null) {
            backupDialog = createGenericDialog(Forge.getLocalizer().getMessage("lblData"),
                null, Forge.getLocalizer().getMessage("lblBackup"),
                Forge.getLocalizer().getMessage("lblRestore"),
                    () -> {
                        removeDialog();
                        Timer.schedule(new Timer.Task() {
                            @Override
                            public void run() {
                                generateBackup();
                            }
                        }, 0.2f);
                    },
                    () -> {
                        removeDialog();
                        Timer.schedule(new Timer.Task() {
                            @Override
                            public void run() {
                                restoreBackup();
                            }
                        }, 0.2f);
                    }, true, Forge.getLocalizer().getMessage("lblCancel"), false);
        }
        showDialog(backupDialog);
        return true;
    }
    private final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("yyMMdd_HHmmss");
    private final SelectBox<String> backupSelectBox = Controls.newComboBox();
    private final Array<String> fileNames = new Array<>();
    private final String prefixPattern = ZipUtil.backupAdvFile.replace(".adv", "");
    private final Pattern strictBackupRegex = Pattern.compile("^" + Pattern.quote(prefixPattern) + "_\\d{6}_\\d{6}\\.adv$");
    public boolean generateBackup() {
        try {
            File source = new FileHandle(ForgeProfileProperties.getUserDir() + "/adventure").file();
            File targetDir = new FileHandle(Forge.getDeviceAdapter().getDownloadsDir()).file();

            String baseName = ZipUtil.backupAdvFile.replace(".adv", "");
            String timestampedFileName = baseName + "_" + TIMESTAMP_FORMAT.format(new Date()) + ".adv";
            File targetFile = new File(targetDir, timestampedFileName);

            ZipUtil.zip(source, targetDir, timestampedFileName);

            if (targetFile.exists() && ZipUtil.isValidZip(targetFile)) {
                zipDialog = createGenericDialog("",
                    Forge.getLocalizer().getMessage("lblSaveLocation") + "\n" + targetFile.getAbsolutePath(),
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null);
            } else {
                throw new IOException("Backup verification failed. The generated file is corrupted.");
            }
        } catch (IOException e) {
            zipDialog = createGenericDialog("",
                Forge.getLocalizer().getMessage("lblErrorSavingFile") + "\n\n" + e.getMessage(),
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null);
        } finally {
            showDialog(zipDialog);
        }
        return true;
    }
    public boolean restoreBackup() {
        File downloadDir = new FileHandle(Forge.getDeviceAdapter().getDownloadsDir()).file();

        File[] files = downloadDir.listFiles((dir, name) -> strictBackupRegex.matcher(name).matches());

        if (files == null || files.length == 0) {
            zipDialog = createGenericDialog("",
                Forge.getLocalizer().getMessageorUseDefault("lblNoBackupsFound", "No backups found!"),
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null);
            showDialog(zipDialog);
            return false;
        }

        Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));

        fileNames.clear();
        for (File file : files) {
            fileNames.add(file.getName());
        }
        backupSelectBox.clearItems();
        backupSelectBox.setItems(fileNames);

        unzipDialog = createGenericDialog("",
            Forge.getLocalizer().getMessage("lblDoYouWantToRestoreBackup"),
            Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"),
            () -> {
                String selectedName = backupSelectBox.getSelected();
                File source = new File(downloadDir, selectedName);
                File target = new FileHandle(ForgeProfileProperties.getUserDir() + "/adventure").file().getParentFile();
                removeDialog();

                if (!ZipUtil.isValidZip(source)) {
                    zipDialog = createGenericDialog("",
                        Forge.getLocalizer().getMessageorUseDefault("lblCorruptedBackupError", "Corrupted Backup!"),
                        Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null);
                    showDialog(zipDialog);
                    return;
                }

                Timer.schedule(new Timer.Task() {
                    @Override
                    public void run() {
                        try {
                            extract(source, target);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, 0.1f);
            },
            this::removeDialog, false, "", true
        );

        unzipDialog.getContentTable().row();
        unzipDialog.getContentTable().add(backupSelectBox).width(150).pad(5).center();
        unzipDialog.pack();

        showDialog(unzipDialog);
        return true;
    }
    public boolean extract(File source, File target) {
        String title = "", val = "";
        //boolean isError = false;

        try {
            val = Forge.getLocalizer().getMessage("lblFiles") + ":\n" + ZipUtil.unzip(source, target);
        } catch (IOException e) {
            title = Forge.getLocalizer().getMessage("lblError");
            val = e.getMessage();
            //isError = true;
        } finally {
            Config.instance().getSettingData().lastActiveSave = null;
            Config.instance().saveSettings();

            TextraLabel messageLabel = Controls.newTextraLabel(val);
            messageLabel.setWrap(true);

            ScrollPane scrollPane = new ScrollPane(messageLabel);
            scrollPane.setFadeScrollBars(false);

            var resultsDialog = createGenericDialog(title, "",
                Forge.getLocalizer().getMessage("lblOK"), null, () -> {
                    messageLabel.remove();
                    scrollPane.remove();
                    removeDialog();
                }, null);

            //resultsDialog.getContentTable().row().pad(10);

            float dialogWidth = Forge.isLandscapeMode() ? 150f : 100f;
            float dialogHeight = Forge.isLandscapeMode() ? 100f : 150f;

            resultsDialog.getContentTable().add(scrollPane).width(dialogWidth).height(dialogHeight).expand().fill();
            resultsDialog.pack();

            showDialog(resultsDialog);
        }
        return true;
    }

    public boolean Exit() {
        if (CoopSession.get().getRole() == CoopSessionRole.HOST
                && CoopSession.get().isHostPartnerDirty()) {
            showDialog(createGenericDialog("Unsaved partner progress",
                    "A co-op partner has unsaved progress in this world.\n"
                            + "Save now before quitting?",
                    "Save and quit", "Quit without saving",
                    () -> {
                        boolean saved = false;
                        try {
                            saved = CoopSession.get().saveHostWorldNow();
                        } catch (final Exception ignored) {
                        }
                        removeDialog();
                        if (!saved && CoopSession.get().isHostPartnerDirty()) {
                            showDialog(createGenericDialog("Save deferred",
                                    "Could not save yet (still in a map, or write failed).\n"
                                            + "Partner progress is still marked unsaved.\nQuit anyway?",
                                    "Quit", "Stay",
                                    () -> {
                                        removeDialog();
                                        Forge.exit(true);
                                    },
                                    this::removeDialog));
                            return;
                        }
                        Forge.exit(true);
                    },
                    () -> {
                        removeDialog();
                        Forge.exit(true);
                    }));
            return true;
        }
        if (exitDialog == null) {
            exitDialog = createGenericDialog(Forge.getLocalizer().getMessage("lblExitForge"),
                    Forge.getLocalizer().getMessage("lblAreYouSureYouWishExitForge"), Forge.getLocalizer().getMessage("lblOK"),
                    Forge.getLocalizer().getMessage("lblAbort"), () -> {
                        Forge.exit(true);
                        removeDialog();
                    }, this::removeDialog);
        }
        showDialog(exitDialog);
        return true;
    }

    private void confirmStopHosting() {
        if (CoopSession.get().getRole() == CoopSessionRole.HOST
                && CoopSession.get().isHostPartnerDirty()) {
            removeDialog();
            showDialog(createGenericDialog("Unsaved partner progress",
                    "A co-op partner has unsaved progress.\nSave the world before stopping host?",
                    "Save and stop", "Stop without saving",
                    () -> {
                        boolean saved = false;
                        try {
                            saved = CoopSession.get().saveHostWorldNow();
                        } catch (final Exception ignored) {
                        }
                        if (!saved && CoopSession.get().isHostPartnerDirty()) {
                            removeDialog();
                            showDialog(createGenericDialog("Save deferred",
                                    "Could not save yet (still in a map, or write failed).\n"
                                            + "Partner progress is still marked unsaved.\nStop hosting anyway?",
                                    "Stop", "Stay",
                                    () -> {
                                        CoopSession.get().disconnect();
                                        removeDialog();
                                    },
                                    this::removeDialog));
                            return;
                        }
                        CoopSession.get().disconnect();
                        removeDialog();
                    },
                    () -> {
                        CoopSession.get().disconnect();
                        removeDialog();
                    }));
            return;
        }
        CoopSession.get().disconnect();
        removeDialog();
    }

    public void switchToClassic() {
        SoundSystem.instance.stopBackgroundMusic();
        Forge.switchToClassic();
    }

    public void updateResumeContinue() {
        boolean hasResumeButton = WorldSave.getCurrentSave().getWorld().getData() != null;
        resumeButton.setVisible(hasResumeButton);

        // Continue button mutually exclusive with resume button
        if (Config.instance().getSettingData().lastActiveSave != null && !hasResumeButton) {
            continueButton.setVisible(true);
            if (!Forge.isLandscapeMode()) {
                continueButton.setX(resumeButton.getX());
                continueButton.setY(resumeButton.getY());
            }
        } else {
            continueButton.setVisible(false);
        }
    }

    @Override
    public void enter() {
        boolean hasSaveButton = WorldSave.getCurrentSave().getWorld().getData() != null;
        if (hasSaveButton) {
            TileMapScene scene = TileMapScene.instance();
            hasSaveButton = !scene.currentMap().isInMap() || scene.isAutoHealLocation();
        }
        saveButton.setVisible(hasSaveButton);
        saveButton.setDisabled(TileMapScene.instance().currentMap().isInMap());
        updateResumeContinue();

        final boolean showCoop = Config.ascendant();
        if (hostButton != null) {
            hostButton.setVisible(showCoop);
        }
        if (joinButton != null) {
            joinButton.setVisible(showCoop);
        }

        FSkinTexture.invalidateAdventureTextures();
        GuiBase.setAdventureDirectory(Config.instance().getPrefix());

        if (Forge.createNewAdventureMap) {
            this.NewGame();
            GameStage.maximumScrollDistance = 4f;
        }

        super.enter();
    }

    private void NewGamePlus() {
        SaveLoadScene.instance().setMode(SaveLoadScene.Modes.NewGamePlus);
        Forge.switchScene(SaveLoadScene.instance());
    }
}
