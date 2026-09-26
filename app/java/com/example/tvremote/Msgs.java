package com.example.tvremote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Message builders / framing for the Android TV Remote protocol v2 (polo pairing + remote). */
final class Msgs {
    private Msgs() {}

    // ---- framing: varint length prefix + payload ----
    static byte[] frame(byte[] payload) {
        Pb.Writer w = new Pb.Writer();
        w.varint(payload.length);
        byte[] head = w.toByteArray();
        byte[] out = new byte[head.length + payload.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(payload, 0, out, head.length, payload.length);
        return out;
    }

    static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("closed");
            len |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) throw new IOException("bad frame length");
        }
        if (len < 0 || len > (1 << 20)) throw new IOException("frame too large: " + len);
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("closed");
            off += n;
        }
        return buf;
    }

    // ================= polo (pairing, port 6467) =================
    static final int STATUS_OK = 200;
    static final int ENC_HEX = 3;
    static final int ROLE_INPUT = 1;

    private static Pb.Writer outer(int field, Pb.Writer body) {
        Pb.Writer w = new Pb.Writer();
        w.uint(1, 2).uint(2, STATUS_OK);
        if (body != null) w.msg(field, body);
        return w;
    }

    static byte[] pairingRequest(String clientName) {
        Pb.Writer b = new Pb.Writer().str(1, "atvremote").str(2, clientName);
        return outer(10, b).toByteArray();
    }

    static byte[] pairingOptions() {
        Pb.Writer enc = new Pb.Writer().uint(1, ENC_HEX).uint(2, 6);
        Pb.Writer b = new Pb.Writer().msg(1, enc).uint(3, ROLE_INPUT);
        return outer(20, b).toByteArray();
    }

    static byte[] pairingConfiguration() {
        Pb.Writer enc = new Pb.Writer().uint(1, ENC_HEX).uint(2, 6);
        Pb.Writer b = new Pb.Writer().msg(1, enc).uint(2, ROLE_INPUT);
        return outer(30, b).toByteArray();
    }

    static byte[] pairingSecret(byte[] secret) {
        return outer(40, new Pb.Writer().bytes(1, secret)).toByteArray();
    }

    /** Parsed polo OuterMessage: status + which body field (10,11,20,30,31,40,41) is present. */
    static final class Polo {
        int status;
        int bodyField;
        String serverName = "";
    }

    static Polo parsePolo(byte[] raw) {
        Polo p = new Polo();
        Pb.Reader r = new Pb.Reader(raw);
        while (r.next()) {
            if (r.field == 2) p.status = (int) r.num;
            else if (r.field >= 10 && r.field <= 41 && r.wire == 2) {
                p.bodyField = r.field;
                if (r.field == 11) {
                    Pb.Reader s = new Pb.Reader(r.data);
                    while (s.next()) if (s.field == 1) p.serverName = s.str();
                }
            }
        }
        return p;
    }

    // ================= remote (port 6466) =================
    static final int F_PING = 1;
    static final int F_KEY = 2;
    static final int F_IME = 4;
    static final int F_VOICE = 8;
    static final int F_POWER = 32;
    static final int F_VOLUME = 64;
    static final int F_APP_LINK = 512;

    static final int DIR_START_LONG = 1;
    static final int DIR_END_LONG = 2;
    static final int DIR_SHORT = 3;

    static byte[] remoteConfigure(int features) {
        Pb.Writer info = new Pb.Writer().uint(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0");
        Pb.Writer cfg = new Pb.Writer().uint(1, features).msg(2, info);
        return new Pb.Writer().msg(1, cfg).toByteArray();
    }

    static byte[] remoteSetActive(int features) {
        return new Pb.Writer().msg(2, new Pb.Writer().uint(1, features)).toByteArray();
    }

    static byte[] pingResponse(int val1) {
        return new Pb.Writer().msg(9, new Pb.Writer().uint(1, val1)).toByteArray();
    }

    static byte[] keyInject(int keyCode, int direction) {
        Pb.Writer k = new Pb.Writer().uint(1, keyCode).uint(2, direction);
        return new Pb.Writer().msg(10, k).toByteArray();
    }

    static byte[] imeText(int imeCounter, int fieldCounter, String text) {
        int p = text.length() - 1;
        Pb.Writer obj = new Pb.Writer().uint(1, p).uint(2, p).str(3, text);
        Pb.Writer edit = new Pb.Writer().uint(1, 1).msg(2, obj);
        Pb.Writer batch = new Pb.Writer().uint(1, imeCounter).uint(2, fieldCounter).msg(3, edit);
        return new Pb.Writer().msg(21, batch).toByteArray();
    }

    static byte[] voiceBegin(long session) {
        return new Pb.Writer().msg(30, new Pb.Writer().uint(1, session)).toByteArray();
    }

    static byte[] voicePayload(long session, byte[] samples) {
        return new Pb.Writer().msg(31, new Pb.Writer().uint(1, session).bytes(2, samples)).toByteArray();
    }

    static byte[] voiceEnd(long session) {
        return new Pb.Writer().msg(32, new Pb.Writer().uint(1, session)).toByteArray();
    }

    static byte[] appLink(String link) {
        return new Pb.Writer().msg(90, new Pb.Writer().str(1, link)).toByteArray();
    }
}
