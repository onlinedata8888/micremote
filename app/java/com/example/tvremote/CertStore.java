package com.example.tvremote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * The phone's identity for the TV: a self-signed RSA-2048 certificate, generated once and stored in the
 * app's private storage. The TV remembers this certificate after pairing.
 * Certificate is built by hand (DER) so no extra crypto library is needed.
 */
final class CertStore {
    final PrivateKey key;
    final X509Certificate cert;

    private CertStore(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    static synchronized CertStore loadOrCreate(File dir) throws Exception {
        File kf = new File(dir, "client_key.pk8");
        File cf = new File(dir, "client_cert.der");
        if (kf.exists() && cf.exists()) {
            try {
                PrivateKey k = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(kf)));
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readAll(cf)));
                return new CertStore(k, c);
            } catch (Exception e) {
                TvLog.d("stored identity unreadable, regenerating: " + e);
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] der = buildCert(kp, "atvremote");
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        dir.mkdirs();
        writeAll(kf, kp.getPrivate().getEncoded());
        writeAll(cf, der);
        return new CertStore(kp.getPrivate(), c);
    }

    // ---------------- DER helpers ----------------
    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < parts.length; i++) o.write(parts[i], 0, parts[i].length);
        return o.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] c) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = c.length;
        if (n < 128) {
            o.write(n);
        } else if (n < 256) {
            o.write(0x81);
            o.write(n);
        } else {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n & 0xFF);
        }
        o.write(c, 0, c.length);
        return o.toByteArray();
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(Pb.UTF8));
    }

    private static byte[] buildCert(KeyPair kp, String cn) throws Exception {
        byte[] sigAlg = tlv(0x30, cat(
                new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B},
                new byte[]{0x05, 0x00}));
        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, cat(
                new byte[]{0x06, 0x03, 0x55, 0x04, 0x03},
                tlv(0x0C, cn.getBytes(Pb.UTF8))))));
        long now = System.currentTimeMillis();
        byte[] validity = tlv(0x30, cat(
                utcTime(new Date(now - 24L * 3600 * 1000)),
                utcTime(new Date(now + 10L * 365 * 24 * 3600 * 1000))));
        byte[] serialBytes = new byte[8];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] &= 0x7F; // positive
        serialBytes[0] |= 0x40; // never a leading zero byte
        byte[] tbs = tlv(0x30, cat(
                new byte[]{(byte) 0xA0, 0x03, 0x02, 0x01, 0x02}, // v3
                tlv(0x02, serialBytes),
                sigAlg,
                name,
                validity,
                name,
                kp.getPublic().getEncoded()));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] sigBits = tlv(0x03, cat(new byte[]{0x00}, sig));
        return tlv(0x30, cat(tbs, sigAlg, sigBits));
    }

    // ---------------- key material helpers used by the pairing hash ----------------
    /** Big-endian magnitude without a leading sign byte. */
    static byte[] magnitude(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] c = new byte[b.length - 1];
            System.arraycopy(b, 1, c, 0, c.length);
            return c;
        }
        return b;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }
}
