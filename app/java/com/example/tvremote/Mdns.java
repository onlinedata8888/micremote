package com.example.tvremote;

import java.io.ByteArrayOutputStream;

/** Minimal multicast-DNS helper: builds a PTR query for the Android TV remote service and parses the answer. */
final class Mdns {
    private Mdns() {}

    static final String SERVICE = "_androidtvremote2._tcp.local";

    static byte[] query() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int[] head = {0x4a, 0x11, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0}; // id, flags, 1 question
        for (int i = 0; i < head.length; i++) o.write(head[i]);
        String[] labels = SERVICE.split("\\.");
        for (int i = 0; i < labels.length; i++) {
            byte[] b = labels[i].getBytes(Pb.UTF8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        o.write(0);
        o.write(12); // PTR
        o.write(0);
        o.write(1); // IN
        return o.toByteArray();
    }

    private static int u16(byte[] p, int o) {
        return ((p[o] & 0xFF) << 8) | (p[o + 1] & 0xFF);
    }

    /** Reads a (possibly compressed) DNS name starting at pos[0]; advances pos[0] past it. */
    private static String readName(byte[] p, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int off = pos[0];
        boolean jumped = false;
        int guard = 0;
        while (guard++ < 64) {
            if (off >= len) return null;
            int l = p[off] & 0xFF;
            if (l == 0) {
                if (!jumped) pos[0] = off + 1;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (off + 1 >= len) return null;
                int ptr = ((l & 0x3F) << 8) | (p[off + 1] & 0xFF);
                if (!jumped) pos[0] = off + 2;
                jumped = true;
                off = ptr;
                continue;
            }
            off++;
            if (off + l > len) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(p, off, l, Pb.UTF8));
            off += l;
        }
        return sb.toString();
    }

    /**
     * If the packet is an mDNS response that announces our service, returns the TV's instance name
     * (its friendly name, or "" if it cannot be read). Otherwise returns null.
     */
    static String parseInstance(byte[] p, int len) {
        try {
            if (len < 12 || (p[2] & 0x80) == 0) return null; // not a response
            int qd = u16(p, 4);
            int rr = u16(p, 6) + u16(p, 8) + u16(p, 10);
            int[] pos = {12};
            for (int i = 0; i < qd; i++) {
                if (readName(p, len, pos) == null) return null;
                pos[0] += 4;
            }
            boolean sawService = false;
            for (int i = 0; i < rr; i++) {
                String name = readName(p, len, pos);
                if (name == null || pos[0] + 10 > len) return sawService ? "" : null;
                int type = u16(p, pos[0]);
                int rdlen = u16(p, pos[0] + 8);
                int rdoff = pos[0] + 10;
                if (name.equalsIgnoreCase(SERVICE)) {
                    sawService = true;
                    if (type == 12) {
                        int[] rp = {rdoff};
                        String inst = readName(p, len, rp);
                        if (inst != null) {
                            int cut = inst.toLowerCase().indexOf("._androidtvremote2");
                            return cut > 0 ? inst.substring(0, cut) : "";
                        }
                    }
                } else if (name.toLowerCase().endsWith("._androidtvremote2._tcp.local")) {
                    sawService = true; // SRV/TXT record of an instance
                    int cut = name.toLowerCase().indexOf("._androidtvremote2");
                    if (cut > 0) return name.substring(0, cut);
                }
                pos[0] = rdoff + rdlen;
            }
            return sawService ? "" : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
