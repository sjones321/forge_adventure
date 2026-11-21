package forge.app;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.AudioDevice;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Generated table cues. No copyrighted files. Fail silent if the device is busy.
 * All cues sit at about 20% so they are present but not a blast.
 * One warmed device plays the queue so a clash lands on the IMPACT frame,
 * not after a fresh OpenAL open, and not as a pile-up at lunge start.
 */
final class DuelSounds {
    private static final float VOLUME = 0.20f;
    private static final int RATE = 22050;
    private static final ConcurrentLinkedQueue<short[]> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Object WAIT = new Object();
    private static final short[] SILENCE = new short[64];
    private static volatile boolean started;
    private static short[] clashClip;

    private DuelSounds() {}

    /** Open the device before the first combat so IMPACT is only a queue write. */
    static void warm() {
        clashPcm();
        startPlayer();
    }

    /** His turn is low then high. Theirs is the reverse. */
    static void turnPassed(boolean yours) {
        int a = yours ? 392 : 523;
        int b = yours ? 523 : 392;
        play(join(tone(a, 0.11f, 0.9f), tone(b, 0.13f, 0.7f)));
    }

    /** One slight beep when a new choice waits on him. */
    static void choice() {
        play(tone(880, 0.07f, 0.8f));
    }

    /** Combat thud at bash IMPACT. Generated low knock; polish the clip later. */
    static void clash() {
        play(clashPcm());
    }

    /** His permanent or land hits the tile. Low thud plus a short hiss. Parked: needs a weightier summon later. */
    static void summon() {
        play(mix(tone(92, 0.06f, 1f), noise(0.22f, 0.70f, 0.18f)));
    }

    /** Creature leaves the bed. Higher scrape plus a short fall, not the summon thud. */
    static void death() {
        play(mix(mix(tone(196, 0.05f, 0.85f), tone(110, 0.10f, 0.7f)), noise(0.16f, 0.80f, 0.22f)));
    }

    private static short[] clashPcm() {
        if (clashClip == null) {
            clashClip = mix(tone(68, 0.08f, 1f), noise(0.10f, 0.58f, 0.48f));
        }
        return clashClip;
    }

    private static void play(short[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        startPlayer();
        QUEUE.add(pcm);
        synchronized (WAIT) {
            WAIT.notifyAll();
        }
    }

    private static void startPlayer() {
        if (started) return;
        synchronized (DuelSounds.class) {
            if (started) return;
            started = true;
            try {
                Thread t = new Thread(DuelSounds::runPlayer, "duel-sound");
                t.setDaemon(true);
                t.start();
            } catch (Throwable ignored) {
                started = false;
            }
        }
    }

    private static void runPlayer() {
        AudioDevice device = null;
        try {
            while (Gdx.audio == null) {
                Thread.sleep(20);
            }
            device = Gdx.audio.newAudioDevice(RATE, true);
            device.writeSamples(SILENCE, 0, SILENCE.length);
            while (true) {
                short[] pcm = QUEUE.poll();
                if (pcm == null) {
                    synchronized (WAIT) {
                        if (QUEUE.peek() == null) WAIT.wait(500);
                    }
                    continue;
                }
                device.writeSamples(pcm, 0, pcm.length);
            }
        } catch (Throwable ignored) {
        } finally {
            if (device != null) {
                try {
                    device.dispose();
                } catch (Throwable ignored) {
                }
            }
            started = false;
        }
    }

    private static short[] tone(int hz, float seconds, float peak) {
        int n = Math.max(1, Math.round(RATE * seconds));
        short[] out = new short[n];
        double step = 2.0 * Math.PI * hz / RATE;
        for (int i = 0; i < n; i++) {
            float env = envelope(i, n, 0.08f, 0.35f);
            out[i] = sample(Math.sin(step * i) * peak * env);
        }
        return out;
    }

    private static short[] noise(float seconds, float peak, float dull) {
        int n = Math.max(1, Math.round(RATE * seconds));
        short[] out = new short[n];
        float prev = 0f;
        for (int i = 0; i < n; i++) {
            float env = envelope(i, n, 0.04f, 0.55f);
            float raw = (float) (Math.random() * 2.0 - 1.0);
            prev = prev * dull + raw * (1f - dull);
            out[i] = sample(prev * peak * env);
        }
        return out;
    }

    private static short[] join(short[] a, short[] b) {
        short[] out = new short[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static short[] mix(short[] a, short[] b) {
        int n = Math.max(a.length, b.length);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            int sum = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
            out[i] = (short) sum;
        }
        return out;
    }

    private static float envelope(int i, int n, float attack, float release) {
        float t = i / (float) Math.max(1, n - 1);
        float a = t < attack ? t / Math.max(0.001f, attack) : 1f;
        float r = t > 1f - release ? (1f - t) / Math.max(0.001f, release) : 1f;
        return a * r;
    }

    private static short sample(double v) {
        v *= VOLUME;
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        return (short) Math.round(v * 32767.0);
    }
}
