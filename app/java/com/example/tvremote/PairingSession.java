package com.example.tvremote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import javax.net.ssl.SSLSocket;

/** Pairing (port 6467): the TV shows a 6-digit hex code, we prove we typed it with a SHA-256 secret. */
final class PairingSession {
    static class PairingException extends IOException {
        PairingException(String m) {
            super(m);
        }
    }

    /** The code was wrong (checked locally, session is still usable: ask for another code). */
    static final class WrongCodeException extends PairingException {
        WrongCodeException(String m) {
            super(m);
        }
    }

    /** The TV rejected the secret; it closes the session, so pairing must restart. */
    static final class RejectedException extends PairingException {
        RejectedException(String m) {
            super(m);
        }
    }

    private final CertStore cs;
    private final String host;
    private final String clientName;
    private final int port;
    private SSLSocket sock;
    private InputStream in;
    private OutputStream out;
    private RSAPublicKey serverKey;
    String serverName = "";

    PairingSession(CertStore cs, String host, int port, String clientName) {
        this.cs = cs;
        this.host = host;
        this.port = port;
        this.clientName = clientName;
    }

    private void send(byte[] payload) throws IOException {
        out.write(Msgs.frame(payload));
        out.flush();
    }

    private Msgs.Polo expect(int bodyField) throws IOException {
        Msgs.Polo p = Msgs.parsePolo(Msgs.readFrame(in));
        if (p.status != Msgs.STATUS_OK) throw new RejectedException("TV status " + p.status);
        if (p.bodyField != bodyField) throw new PairingException("unexpected pairing message " + p.bodyField);
        return p;
    }

    /** Runs the handshake up to the point where the TV displays the code. */
    void begin() throws Exception {
        sock = Tls.connect(cs, host, port, 4000);
        sock.setSoTimeout(10000);
        in = sock.getInputStream();
        out = sock.getOutputStream();
        Certificate[] chain = sock.getSession().getPeerCertificates();
        Certificate c = chain[0];
        if (!(c instanceof X509Certificate) || !(c.getPublicKey() instanceof RSAPublicKey))
            throw new PairingException("TV certificate is not RSA");
        serverKey = (RSAPublicKey) c.getPublicKey();

        send(Msgs.pairingRequest(clientName));
        serverName = expect(11).serverName;
        send(Msgs.pairingOptions());
        expect(20);
        send(Msgs.pairingConfiguration());
        expect(31);
    }

    static byte[] secretFor(RSAPublicKey clientKey, RSAPublicKey serverKey, String code) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(CertStore.magnitude(clientKey.getModulus()));
        sha.update(CertStore.magnitude(clientKey.getPublicExponent()));
        sha.update(CertStore.magnitude(serverKey.getModulus()));
        sha.update(CertStore.magnitude(serverKey.getPublicExponent()));
        String tail = code.substring(2);
        byte[] nonce = new byte[tail.length() / 2];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16);
        sha.update(nonce);
        return sha.digest();
    }

    /** Verifies the code and sends the secret. */
    void finish(String rawCode) throws Exception {
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("[0-9a-fA-F]{6}")) throw new WrongCodeException("Code must be 6 characters (0-9, A-F)");
        byte[] secret = secretFor((RSAPublicKey) cs.cert.getPublicKey(), serverKey, code);
        if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16))
            throw new WrongCodeException("Wrong code");
        send(Msgs.pairingSecret(secret));
        try {
            expect(41);
        } catch (RejectedException e) {
            throw e;
        }
    }

    void close() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
    }

    /** Used by tests. */
    BigInteger serverModulus() {
        return serverKey.getModulus();
    }
}
