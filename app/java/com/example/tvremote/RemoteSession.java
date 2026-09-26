package com.example.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** A live remote-control connection (port 6466). Sends are queued on one thread so key order is preserved. */
final class RemoteSession {
    interface Listener {
        void onVolume(int level, int max, boolean muted);

        void onPower(boolean on);

        void onClosed(RemoteSession s, String reason);
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tv-writer");
            t.setDaemon(true);
            return t;
        }
    });
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean closed;
    private volatile int active = Msgs.F_PING | Msgs.F_KEY | Msgs.F_IME | Msgs.F_VOICE | Msgs.F_POWER | Msgs.F_VOLUME | Msgs.F_APP_LINK;
    private volatile int imeCounter;
    private volatile int fieldCounter;
    private volatile boolean imeSeen;
    private volatile CountDownLatch voiceWait;
    private volatile long voiceId;
    private volatile boolean voiceEnded;
    private final java.util.Set<Integer> held = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private RemoteSession(Socket s, Listener l) throws IOException {
        this.socket = s;
        this.in = s.getInputStream();
        this.out = s.getOutputStream();
        this.listener = l;
    }

    static RemoteSession open(CertStore cs, String host, int port, Listener l) throws Exception {
        Socket s = Tls.connect(cs, host, port, 3000);
        s.setSoTimeout(25000); // TV pings every ~5 s; silence for 25 s means the link is dead
        final RemoteSession r = new RemoteSession(s, l);
        Thread t = new Thread(new Runnable() {
            public void run() {
                r.readLoop();
            }
        }, "tv-reader");
        t.setDaemon(true);
        t.start();
        return r;
    }

    boolean isClosed() {
        return closed;
    }

    boolean awaitReady(long ms) {
        try {
            return ready.await(ms, TimeUnit.MILLISECONDS) && !closed;
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reading
    private void readLoop() {
        String why = "closed";
        try {
            while (!closed) {
                byte[] f = Msgs.readFrame(in);
                handle(f);
            }
        } catch (Exception e) {
            why = String.valueOf(e);
        }
        close(why);
    }

    private void handle(byte[] raw) {
        Pb.Reader r = new Pb.Reader(raw);
        while (r.next()) {
            if (r.wire != 2) continue;
            switch (r.field) {
                case 1: { // remote_configure: TV tells which features it supports
                    int code1 = 0;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) code1 = (int) s.num;
                    active &= code1;
                    send(Msgs.remoteConfigure(active));
                    break;
                }
                case 2: // remote_set_active
                    send(Msgs.remoteSetActive(active));
                    break;
                case 3:
                    TvLog.d("remote_error from TV");
                    break;
                case 8: { // ping request -> ping response
                    int v = 0;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) v = (int) s.num;
                    send(Msgs.pingResponse(v));
                    break;
                }
                case 20:
                case 22:
                    imeSeen = true;
                    break;
                case 21: {
                    imeSeen = true;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) {
                        if (s.field == 1) imeCounter = (int) s.num;
                        else if (s.field == 2) fieldCounter = (int) s.num;
                    }
                    break;
                }
                case 30: { // remote_voice_begin from the TV (answer to KEYCODE_SEARCH)
                    long id = 0;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) id = s.num;
                    CountDownLatch w = voiceWait;
                    if (w != null) {
                        voiceId = id;
                        w.countDown();
                    }
                    break;
                }
                case 32: // TV finished listening (it understood the command or gave up)
                    voiceEnded = true;
                    break;
                case 40: { // remote_start: ready for commands
                    boolean on = false;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) on = s.num != 0;
                    ready.countDown();
                    listener.onPower(on);
                    break;
                }
                case 50: { // volume info
                    int max = 0, level = 0;
                    boolean muted = false;
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) {
                        if (s.field == 6) max = (int) s.num;
                        else if (s.field == 7) level = (int) s.num;
                        else if (s.field == 8) muted = s.num != 0;
                    }
                    listener.onVolume(level, max, muted);
                    break;
                }
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ writing
    private void writeNow(byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            close("write failed: " + e);
        }
    }

    private void write(final byte[] bytes) {
        if (closed) return;
        try {
            writer.execute(new Runnable() {
                public void run() {
                    if (!closed) writeNow(bytes);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void send(byte[] payload) {
        write(Msgs.frame(payload));
    }

    /** dir: 1 = key down (START_LONG), 2 = key up (END_LONG), 3 = full press (SHORT). */
    void key(int code, int dir) {
        if (dir == Msgs.DIR_START_LONG) held.add(code);
        else if (dir == Msgs.DIR_END_LONG) held.remove(code);
        send(Msgs.keyInject(code, dir));
    }

    /** n quick presses in a single network write. */
    void keys(int code, int n) {
        if (n <= 0) return;
        if (n > 60) n = 60;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] one = Msgs.frame(Msgs.keyInject(code, Msgs.DIR_SHORT));
        for (int i = 0; i < n; i++) b.write(one, 0, one.length);
        write(b.toByteArray());
    }

    void releaseHeld() {
        Object[] codes = held.toArray();
        held.clear();
        for (int i = 0; i < codes.length; i++) send(Msgs.keyInject((Integer) codes[i], Msgs.DIR_END_LONG));
    }

    void text(String s) {
        if (s == null || s.length() == 0) return;
        if (imeSeen && (active & Msgs.F_IME) != 0) {
            send(Msgs.imeText(imeCounter, fieldCounter, s));
            return;
        }
        for (int i = 0; i < s.length(); i++) {
            int kc = keyCodeForChar(s.charAt(i));
            if (kc > 0) send(Msgs.keyInject(kc, Msgs.DIR_SHORT));
        }
    }

    static int keyCodeForChar(char c) {
        if (c >= 'a' && c <= 'z') return 29 + (c - 'a');
        if (c >= 'A' && c <= 'Z') return 29 + (c - 'A');
        if (c >= '0' && c <= '9') return 7 + (c - '0');
        switch (c) {
            case ' ':
                return 62;
            case '.':
                return 56;
            case ',':
                return 55;
            case '-':
                return 69;
            case '@':
                return 77;
            case '/':
                return 76;
            default:
                return -1;
        }
    }

    // ---------------------------------------------------------------- voice (16-bit PCM, mono, 8000 Hz)
    static final long VOICE_FAILED = Long.MIN_VALUE;
    private static final int VOICE_CHUNK_MIN = 8 * 1024;
    private static final int VOICE_CHUNK_MAX = 20 * 1024;

    boolean voiceSupported() {
        return (active & Msgs.F_VOICE) != 0;
    }

    /** Sends KEYCODE_SEARCH, waits for the TV's voice_begin and echoes it. Returns the session id or VOICE_FAILED. */
    long voiceBegin(long timeoutMs) {
        CountDownLatch w = new CountDownLatch(1);
        voiceId = VOICE_FAILED;
        voiceEnded = false;
        voiceWait = w;
        send(Msgs.keyInject(84, Msgs.DIR_SHORT));
        boolean ok = false;
        try {
            ok = w.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        voiceWait = null;
        if (!ok || closed) return VOICE_FAILED;
        long id = voiceId;
        send(Msgs.voiceBegin(id));
        return id;
    }

    /** Sends PCM audio; chunks are split at 20 KB and padded to at least 8 KB (TV closes the link otherwise). */
    void voiceChunk(long id, byte[] pcm, int len) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < len; i += VOICE_CHUNK_MAX) {
            int n = Math.min(VOICE_CHUNK_MAX, len - i);
            byte[] samples = new byte[Math.max(n, VOICE_CHUNK_MIN)];
            System.arraycopy(pcm, i, samples, 0, n);
            byte[] f = Msgs.frame(Msgs.voicePayload(id, samples));
            b.write(f, 0, f.length);
        }
        write(b.toByteArray());
    }

    boolean voiceEndedByTv() {
        return voiceEnded;
    }

    void voiceEnd(long id) {
        send(Msgs.voiceEnd(id));
    }

    void launch(String link) {
        send(Msgs.appLink(link));
    }

    void close(String reason) {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        writer.shutdownNow();
        ready.countDown();
        try {
            listener.onClosed(this, reason);
        } catch (Exception ignored) {
        }
    }
}
