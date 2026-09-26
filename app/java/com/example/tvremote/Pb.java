package com.example.tvremote;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/** Tiny protobuf wire-format writer/reader (only what the TV remote protocol needs). */
final class Pb {
    private Pb() {}

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Writer {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream(64);

        Writer varint(long v) {
            while ((v & ~0x7FL) != 0) {
                o.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            o.write((int) v);
            return this;
        }

        Writer tag(int field, int wire) {
            return varint(((long) field << 3) | wire);
        }

        Writer uint(int field, long v) {
            return tag(field, 0).varint(v);
        }

        Writer bool(int field, boolean b) {
            return uint(field, b ? 1 : 0);
        }

        Writer bytes(int field, byte[] b) {
            tag(field, 2).varint(b.length);
            o.write(b, 0, b.length);
            return this;
        }

        Writer str(int field, String s) {
            return bytes(field, s.getBytes(UTF8));
        }

        Writer msg(int field, Writer w) {
            return bytes(field, w.toByteArray());
        }

        byte[] toByteArray() {
            return o.toByteArray();
        }
    }

    /** Pull parser: call next(), then look at field / wire / num (varint) / data (length-delimited). */
    static final class Reader {
        private final byte[] b;
        private int p;
        private final int end;
        int field;
        int wire;
        long num;
        byte[] data;

        Reader(byte[] b) {
            this.b = b;
            this.p = 0;
            this.end = b.length;
        }

        private long readVarint() {
            long r = 0;
            int shift = 0;
            while (true) {
                if (p >= end) throw new IllegalArgumentException("truncated varint");
                int c = b[p++] & 0xFF;
                r |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return r;
                shift += 7;
                if (shift > 63) throw new IllegalArgumentException("varint too long");
            }
        }

        boolean next() {
            if (p >= end) return false;
            long t = readVarint();
            field = (int) (t >>> 3);
            wire = (int) (t & 7);
            data = null;
            num = 0;
            switch (wire) {
                case 0:
                    num = readVarint();
                    break;
                case 1:
                    p += 8;
                    break;
                case 2: {
                    int n = (int) readVarint();
                    if (n < 0 || p + n > end) throw new IllegalArgumentException("bad length");
                    byte[] d = new byte[n];
                    System.arraycopy(b, p, d, 0, n);
                    p += n;
                    data = d;
                    break;
                }
                case 5:
                    p += 4;
                    break;
                default:
                    throw new IllegalArgumentException("unsupported wire type " + wire);
            }
            if (p > end) throw new IllegalArgumentException("truncated");
            return true;
        }

        String str() {
            return data == null ? "" : new String(data, UTF8);
        }
    }
}
