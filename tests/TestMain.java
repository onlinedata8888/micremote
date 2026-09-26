package com.example.tvremote;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Base64;

public class TestMain {
    public static void main(String[] a) throws Exception {
        if (a[0].equals("gen")) {
            CertStore cs = CertStore.loadOrCreate(new File(a[1]));
            String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cs.cert.getEncoded()) + "\n-----END CERTIFICATE-----\n";
            Files.write(new File(a[1], "client.pem").toPath(), pem.getBytes());
            System.out.println("cert ok: " + cs.cert.getSubjectX500Principal() + " sigalg=" + cs.cert.getSigAlgName());
            return;
        }
        final CertStore cs = CertStore.loadOrCreate(new File(a[1]));
        int pairPort = Integer.parseInt(a[2]), remPort = Integer.parseInt(a[3]);
        File codeFile = new File(a[4]);
        PairingSession ps = new PairingSession(cs, "127.0.0.1", pairPort, "Test Phone");
        ps.begin();
        System.out.println("PAIR begin ok, tv=" + ps.serverName);
        for (int i = 0; i < 50 && !codeFile.exists(); i++) Thread.sleep(100);
        String code = new String(Files.readAllBytes(codeFile.toPath())).trim();
        try { ps.finish("000000"); System.out.println("PAIR ERROR: wrong code accepted"); }
        catch (PairingSession.WrongCodeException e) { System.out.println("PAIR wrong code rejected locally: " + e.getMessage()); }
        try { ps.finish("zzz"); System.out.println("PAIR ERROR: bad format accepted"); }
        catch (PairingSession.WrongCodeException e) { System.out.println("PAIR bad format rejected: " + e.getMessage()); }
        ps.finish(code.toLowerCase());
        System.out.println("PAIR finished ok with code " + code);
        ps.close();

        RemoteSession.Listener l = new RemoteSession.Listener() {
            public void onVolume(int level, int max, boolean muted) { System.out.println("VOL " + level + "/" + max + " muted=" + muted); }
            public void onPower(boolean on) { System.out.println("POWER on=" + on); }
            public void onClosed(RemoteSession s, String r) { System.out.println("CLOSED " + r); }
        };
        RemoteSession rs = RemoteSession.open(cs, "127.0.0.1", remPort, l);
        System.out.println("REMOTE ready=" + rs.awaitReady(5000));
        Thread.sleep(300);
        rs.key(19, 1); rs.key(19, 2);          // dpad up down/up
        rs.key(4, 3);                           // back short
        rs.keys(24, 3);                         // volume up x3
        rs.text("hi");                          // IME text
        rs.launch("https://www.youtube.com");
        rs.key(23, 1);                          // OK held...
        rs.releaseHeld();                       // ...released on pause
        long vid = rs.voiceBegin(2000);
        System.out.println("VOICE supported=" + rs.voiceSupported() + " session=" + vid);
        rs.voiceChunk(vid, new byte[3000], 3000);      // padded to 8 KB
        rs.voiceChunk(vid, new byte[25000], 25000);    // split 20 KB + padded remainder
        Thread.sleep(400);
        System.out.println("VOICE ended by TV=" + rs.voiceEndedByTv());
        rs.voiceEnd(vid);
        Thread.sleep(800);
        rs.close("test done");
        Thread.sleep(200);
    }
}
