package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotAckEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * CO5 guest/host partner snapshot protocol: sequenced snapshots, host acks,
 * trailing debounce, leave waits for final ack, host debounced world save.
 */
public final class CoopPartnerSync {
    private final CoopSession session;
    private final AtomicLong nextSequence = new AtomicLong(1L);
    private final CoopPartnerValidator validator = new CoopPartnerValidator();

    private volatile long lastAckedSequence;
    private volatile long pendingSequence;
    private volatile CountDownLatch pendingAckLatch;
    private volatile boolean trailingScheduled;
    private volatile long hostLastWorldSaveMs;
    private volatile boolean hostPartnerDirty;
    private volatile boolean hostSaveScheduled;
    private volatile String lastGuiPlayerName = "";

    public CoopPartnerSync(final CoopSession session) {
        this.session = session;
    }

    public CoopPartnerValidator validator() {
        return validator;
    }

    public void resetGuest() {
        nextSequence.set(1L);
        lastAckedSequence = 0L;
        pendingSequence = 0L;
        pendingAckLatch = null;
        trailingScheduled = false;
    }

    public void resetHost() {
        validator.resetRateLimit();
        hostLastWorldSaveMs = 0L;
        hostPartnerDirty = false;
        hostSaveScheduled = false;
    }

    public void rememberGuiPlayerName(final String name) {
        if (name != null && !name.isEmpty()) {
            lastGuiPlayerName = name;
        }
    }

    public String getLastGuiPlayerName() {
        return lastGuiPlayerName != null ? lastGuiPlayerName : "";
    }

    /**
     * Guest: send a snapshot now. {@code finalSnapshot} bypasses rate limits on the host
     * and is used for leave.
     *
     * @return sequence number sent, or 0 if not sent
     */
    public long sendSnapshot(final boolean finalSnapshot) {
        if (session.getRole() != CoopSessionRole.GUEST || !session.isPartnerLoaded()) {
            return 0L;
        }
        try {
            final SaveFileData data = WorldSave.getCurrentSave().getPlayer().save();
            final byte[] blob = CoopPartnerCodec.encode(data);
            if (!CoopPartnerValidator.blobSizeOk(blob)) {
                session.status("Partner snapshot too large — not sent");
                notifyGuest("Partner snapshot too large — progress not sent");
                return 0L;
            }
            final long seq = nextSequence.getAndIncrement();
            pendingSequence = seq;
            if (finalSnapshot) {
                pendingAckLatch = new CountDownLatch(1);
            }
            session.send(new CoopPartnerSnapshotEvent(session.getGuestProfileId(), seq, finalSnapshot, blob));
            return seq;
        } catch (final Exception e) {
            session.status("Partner snapshot failed: " + e.getMessage());
            notifyGuest("Partner snapshot failed");
            return 0L;
        }
    }

    /**
     * Trailing debounce: schedule one snapshot after the configured window.
     * Multiple calls within the window collapse to a single trailing send.
     */
    public void requestDebouncedSnapshot() {
        if (session.getRole() != CoopSessionRole.GUEST || !session.isPartnerLoaded()) {
            return;
        }
        if (trailingScheduled) {
            return;
        }
        trailingScheduled = true;
        final int debounceSec;
        try {
            debounceSec = Math.max(1, Config.instance().getConfigData().coopPartnerSnapshotDebounceSeconds);
        } catch (final Exception e) {
            trailingScheduled = false;
            sendSnapshot(false);
            return;
        }
        final Runnable send = () -> {
            trailingScheduled = false;
            sendSnapshot(false);
        };
        if (Gdx.app != null) {
            // LibGDX Timer is seconds; schedule on GL-friendly path.
            com.badlogic.gdx.utils.Timer.schedule(new com.badlogic.gdx.utils.Timer.Task() {
                @Override
                public void run() {
                    send.run();
                }
            }, debounceSec);
        } else {
            // Headless tests: run after a short delay on a daemon thread.
            final Thread t = new Thread(() -> {
                try {
                    Thread.sleep(debounceSec * 1000L);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                send.run();
            }, "coop-partner-debounce");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Guest leave: send final snapshot and wait for ack (timeout → visible warning).
     *
     * @return true if ack received
     */
    public boolean sendFinalSnapshotAndAwaitAck(final long timeoutMs) {
        final long seq = sendSnapshot(true);
        if (seq == 0L) {
            notifyGuest("Could not send final partner snapshot");
            return false;
        }
        final CountDownLatch latch = pendingAckLatch;
        if (latch == null) {
            return false;
        }
        try {
            final boolean ok = latch.await(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!ok) {
                notifyGuest("Host did not confirm partner save — progress may be lost");
                session.status("Final partner snapshot ack timed out");
            }
            return ok;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyGuest("Interrupted waiting for partner save ack");
            return false;
        }
    }

    public void onSnapshotAck(final CoopPartnerSnapshotAckEvent ack) {
        if (ack == null) {
            return;
        }
        if (ack.getSequence() == pendingSequence || ack.getSequence() >= lastAckedSequence) {
            if (ack.isAccepted()) {
                lastAckedSequence = Math.max(lastAckedSequence, ack.getSequence());
                notifyGuest("Progress saved");
            } else {
                notifyGuest("Partner snapshot rejected: "
                        + (ack.getReason().isEmpty() ? "unknown" : ack.getReason()));
            }
        }
        final CountDownLatch latch = pendingAckLatch;
        if (latch != null && ack.getSequence() == pendingSequence) {
            latch.countDown();
        }
    }

    /**
     * Host: validate and store a snapshot. Must be called on the GL thread.
     * Never rate-limits {@code finalSnapshot}.
     */
    public boolean applySnapshotOnGl(final CoopPartnerSnapshotEvent event) {
        if (event == null) {
            return false;
        }
        final String id = CoopProfileId.sanitize(event.getProfileId());
        if (id.isEmpty() || !id.equals(session.getGuestProfileId())) {
            sendAck(event, false, "profile mismatch");
            return false;
        }
        final byte[] blob = event.getPartnerBlob();
        if (!CoopPartnerValidator.blobSizeOk(blob)) {
            sendAck(event, false, "size");
            notifyGuestDrop("Partner snapshot dropped: size");
            return false;
        }
        if (!event.isFinalSnapshot() && !validator.acceptSnapshot()) {
            sendAck(event, false, "rate limit");
            notifyGuestDrop("Partner snapshot dropped: rate limit");
            return false;
        }
        final SaveFileData data = CoopPartnerCodec.decodeSafe(blob);
        final String problem = CoopPartnerValidator.validateDecoded(data);
        if (problem != null) {
            sendAck(event, false, problem);
            return false;
        }
        final String name = CoopPartnerValidator.capName(data.readString("name"));
        if (!name.isEmpty()) {
            data.store("name", name);
        }
        // Overlay host live Standard window so stored partner follows rotation.
        try {
            final AdventurePlayer tmp = new AdventurePlayer();
            SaveFileData.beginWireFilteredReads();
            try {
                tmp.load(data);
            } finally {
                SaveFileData.endWireFilteredReads();
            }
            CoopPartnerStarter.applyHostStandardWindow(tmp, null);
            WorldSave.getCurrentSave().getPartners().putPlayer(id, tmp);
        } catch (final Exception e) {
            WorldSave.getCurrentSave().getPartners().put(id, data);
        }
        hostPartnerDirty = true;
        sendAck(event, true, "");
        session.status("Stored partner snapshot #" + event.getSequence());
        if (event.isFinalSnapshot()) {
            saveHostWorldNow();
        } else {
            scheduleHostWorldSave();
        }
        return true;
    }

    private void sendAck(final CoopPartnerSnapshotEvent event, final boolean accepted, final String reason) {
        session.send(new CoopPartnerSnapshotAckEvent(
                event.getProfileId(), event.getSequence(), accepted, reason));
    }

    private void notifyGuestDrop(final String msg) {
        // Guest is remote — status goes over session status channel; also try HUD if local.
        session.status(msg);
    }

    private void notifyGuest(final String msg) {
        session.status(msg);
        try {
            if (Gdx.app != null) {
                Gdx.app.postRunnable(() -> {
                    try {
                        forge.adventure.stage.GameHUD.getInstance().addNotification(msg);
                    } catch (final Exception ignored) {
                    }
                });
            }
        } catch (final Exception ignored) {
        }
    }

    public void scheduleHostWorldSave() {
        if (hostSaveScheduled) {
            return;
        }
        hostSaveScheduled = true;
        final int debounceSec = 30;
        final Runnable task = () -> {
            hostSaveScheduled = false;
            if (!hostPartnerDirty) {
                return;
            }
            saveHostWorldNow();
        };
        if (Gdx.app != null) {
            com.badlogic.gdx.utils.Timer.schedule(new com.badlogic.gdx.utils.Timer.Task() {
                @Override
                public void run() {
                    task.run();
                }
            }, debounceSec);
        } else {
            final Thread t = new Thread(() -> {
                try {
                    Thread.sleep(debounceSec * 1000L);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                task.run();
            }, "coop-host-world-save");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Host: persist world (with partners) if not blocked by an interior map. */
    public boolean saveHostWorldNow() {
        if (session.getRole() != CoopSessionRole.HOST && session.getRole() != CoopSessionRole.NONE) {
            // Allow NONE during host disconnect cleanup when dirty.
        }
        try {
            if (MapStage.getInstance().isInMap()) {
                // Defer — schedule again.
                scheduleHostWorldSave();
                return false;
            }
        } catch (final Exception ignored) {
        }
        try {
            final int slot = forge.adventure.util.Config.instance().getSettingData().lastActiveSave != null
                    ? forge.adventure.world.WorldSave.filenameToSlot(
                    forge.adventure.util.Config.instance().getSettingData().lastActiveSave)
                    : 0;
            final boolean ok = WorldSave.getCurrentSave().save(
                    "co-op partner save", Math.max(0, slot));
            if (ok) {
                hostPartnerDirty = false;
                hostLastWorldSaveMs = System.currentTimeMillis();
                session.status("Host world saved (partners)");
            }
            return ok;
        } catch (final Exception e) {
            session.status("Host world save failed: " + e.getMessage());
            return false;
        }
    }

    public boolean isHostPartnerDirty() {
        return hostPartnerDirty;
    }

    public void markHostPartnerDirty() {
        hostPartnerDirty = true;
    }

    /** Run {@code action} on the GL thread (inline when Gdx.app is null). */
    public static void runOnGl(final Runnable action) {
        if (action == null) {
            return;
        }
        if (Gdx.app != null) {
            Gdx.app.postRunnable(action);
        } else {
            action.run();
        }
    }

    public static void runOnGl(final Consumer<Void> action) {
        runOnGl(() -> action.accept(null));
    }
}
