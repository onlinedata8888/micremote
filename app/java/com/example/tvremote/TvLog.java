package com.example.tvremote;

final class TvLog {
    private TvLog() {}

    static void d(String m) {
        try {
            android.util.Log.d("TVRemote", m);
        } catch (Throwable t) {
            System.out.println("[TVRemote] " + m);
        }
    }
}
