package com.example.tvremote;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

/**
 * Tap-to-talk: streams the phone's raw microphone audio (16-bit mono PCM at 8 kHz — exactly the format
 * the official Android TV Remote app uses) to the TV's voice session. No extra processing is applied,
 * matching the reference implementation exactly. Stops on a second tap, when the TV ends the session,
 * or after MAX_MS as a safety net.
 */
final class VoiceRecorder implements Runnable {
    interface Sink {
        void toast(String m);

        void finished();
    }

    private static final int MAX_MS = 8000;
    private static final int RATE = 8000;
    private static final int CHUNK_BYTES = 8192; // 512 ms of audio at 8 kHz / 16-bit / mono
    // If the first ~0.5s of samples from a source are all below this, treat it as "device is muting this
    // source silently" (no error thrown, just zeros/near-zeros) and fall back to the next source.
    private static final int SILENCE_AMPLITUDE = 40;

    private final RemoteSession session;
    private final Context appCtx;
    private final Sink sink;
    private volatile boolean stop;

    VoiceRecorder(RemoteSession s, Context appCtx, Sink sink) {
        this.session = s;
        this.appCtx = appCtx;
        this.sink = sink;
    }

    void requestStop() {
        stop = true;
    }

    // Sources to try, in order. VOICE_RECOGNITION first: it skips the aggressive noise-suppression/AGC
    // processing that MIC applies, which a TV's speech recognizer needs to hear clearly. But on some
    // phones (custom Android skins especially) this source initializes fine yet silently captures only
    // zeros — no exception, no error state — so run() below checks real audio and retries with the next
    // source in this list if that happens.
    private static final int[] SOURCES = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};

    private static AudioRecord make(int rate, int source) {
        return tryMake(rate, source);
    }

    /** Best-effort audio focus request so the OS doesn't route the mic through a muted/attenuated path.
     * Some devices otherwise let AudioRecord "succeed" while silently discarding real samples when the
     * app never asked for focus. Returns an object to pass to release(), or null if unsupported/failed
     * (recording still proceeds either way — this only raises the odds of getting real audio). */
    private static Object requestFocus(Context ctx) {
        if (ctx == null) return null;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(attrs)
                        .build();
                am.requestAudioFocus(req);
                return req;
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
                return am;
            }
        } catch (Throwable t) {
            TvLog.d("audio focus: " + t);
            return null;
        }
    }

    private static void releaseFocus(Context ctx, Object token) {
        if (ctx == null || token == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= 26 && token instanceof AudioFocusRequest) {
                am.abandonAudioFocusRequest((AudioFocusRequest) token);
            } else {
                am.abandonAudioFocus(null);
            }
        } catch (Throwable ignored) {
        }
    }

    /** True if every sample's absolute value is at/under the silence threshold (device is muting capture). */
    private static boolean isSilence(byte[] pcm, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (Math.abs(sample) > SILENCE_AMPLITUDE) return false;
        }
        return true;
    }

    private static AudioRecord tryMake(int rate, int source) {
        try {
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return null;
            AudioRecord r = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate * 2 * 2));
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Throwable t) {
            TvLog.d("AudioRecord " + rate + "/" + source + ": " + t);
        }
        return null;
    }

    /** Opens a source at the given rate (falling back to 16 kHz+halving if 8 kHz isn't supported),
     * requesting audio focus first so the OS is less likely to route it through a muted path. */
    private static final class Opened {
        AudioRecord rec;
        int decim;
        Object focusToken;
    }

    private Opened open(int sourceIndex, Context ctx) {
        Opened o = new Opened();
        o.focusToken = requestFocus(ctx);
        int source = SOURCES[sourceIndex];
        o.rec = make(RATE, source);
        o.decim = 1;
        if (o.rec == null) {
            o.rec = make(16000, source); // some phones cannot record at 8 kHz: record at 16 kHz and halve it
            o.decim = 2;
        }
        return o;
    }

    public void run() {
        AudioRecord rec = null;
        Object focusToken = null;
        long id = RemoteSession.VOICE_FAILED;
        boolean everSent = false;
        try {
            int sourceIndex = 0;
            int decim = 1;
            Opened o = open(sourceIndex, appCtx);
            rec = o.rec;
            decim = o.decim;
            focusToken = o.focusToken;
            if (rec == null) {
                sink.toast("Mic start nahi ho paya");
                return;
            }
            rec.startRecording();
            id = session.voiceBegin(2500);
            if (id == RemoteSession.VOICE_FAILED) {
                sink.toast("TV ne voice session nahi kholi");
                return;
            }
            long start = System.currentTimeMillis();
            byte[] raw = new byte[CHUNK_BYTES * decim];
            int filled = 0;
            boolean silenceChecked = false;
            while (!session.isClosed()) {
                int n = rec.read(raw, filled, raw.length - filled);
                if (n > 0) filled += n;
                boolean full = filled == raw.length;
                boolean flushRemainder = (stop || n < 0) && filled > 0;
                if (full || flushRemainder) {
                    // First full chunk from this source: if it's pure silence, this device is likely
                    // muting this AudioSource silently. Swap to the next source and keep the same TV
                    // voice session going (no need to re-tap or redo the handshake).
                    if (!silenceChecked && full) {
                        silenceChecked = true;
                        if (isSilence(raw, filled) && sourceIndex + 1 < SOURCES.length) {
                            sourceIndex++;
                            try {
                                rec.stop();
                                rec.release();
                            } catch (Throwable ignored) {
                            }
                            releaseFocus(appCtx, focusToken);
                            Opened o2 = open(sourceIndex, appCtx);
                            if (o2.rec != null) {
                                rec = o2.rec;
                                decim = o2.decim;
                                focusToken = o2.focusToken;
                                rec.startRecording();
                                raw = new byte[CHUNK_BYTES * decim];
                                filled = 0;
                                silenceChecked = false; // re-check the new source's first chunk too
                                continue;
                            }
                        }
                    }
                    byte[] pcm = decim == 1 ? raw : half(raw, filled);
                    int len = decim == 1 ? filled : filled / 2;
                    session.voiceChunk(id, pcm, len); // raw PCM, unmodified
                    everSent = true;
                    filled = 0;
                }
                if (stop || n < 0 || session.voiceEndedByTv() || System.currentTimeMillis() - start > MAX_MS) break;
            }
        } catch (Throwable t) {
            TvLog.d("voice: " + t);
            sink.toast("Voice error: " + t.getClass().getSimpleName());
        } finally {
            try {
                if (id != RemoteSession.VOICE_FAILED) {
                    session.voiceEnd(id);
                    if (!everSent) sink.toast("Mic se data nahi mila");
                }
            } catch (Throwable ignored) {
            }
            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Throwable ignored) {
            }
            releaseFocus(appCtx, focusToken);
            sink.finished();
        }
    }

    /** 16 kHz -> 8 kHz: average pairs of 16-bit little-endian samples. */
    private static byte[] half(byte[] in, int len) {
        int n = len / 4;
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int a = (short) ((in[i * 4] & 0xFF) | (in[i * 4 + 1] << 8));
            int b = (short) ((in[i * 4 + 2] & 0xFF) | (in[i * 4 + 3] << 8));
            int v = (a + b) / 2;
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }
        return out;
    }
}
