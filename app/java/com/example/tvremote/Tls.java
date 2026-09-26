package com.example.tvremote;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/** TLS client with our self-signed identity; the TV's own self-signed cert is accepted (pairing code secures it). */
final class Tls {
    private Tls() {}

    private static SSLContext context(final CertStore cs) throws Exception {
        KeyManager km = new X509KeyManager() {
            public String[] getClientAliases(String t, Principal[] i) {
                return new String[]{"atv"};
            }

            public String chooseClientAlias(String[] t, Principal[] i, Socket s) {
                return "atv";
            }

            public String[] getServerAliases(String t, Principal[] i) {
                return null;
            }

            public String chooseServerAlias(String t, Principal[] i, Socket s) {
                return null;
            }

            public X509Certificate[] getCertificateChain(String a) {
                return new X509Certificate[]{cs.cert};
            }

            public PrivateKey getPrivateKey(String a) {
                return cs.key;
            }
        };
        TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
        return ctx;
    }

    /** Connects and completes the TLS handshake. Tries default protocols first, then TLS 1.2 only. */
    static SSLSocket connect(CertStore cs, String host, int port, int connectTimeoutMs) throws Exception {
        SSLContext ctx = context(cs);
        SSLException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket raw = new Socket();
            SSLSocket ss = null;
            try {
                raw.setTcpNoDelay(true);
                raw.setKeepAlive(true);
                raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                raw.setSoTimeout(8000);
                ss = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
                if (attempt == 1) ss.setEnabledProtocols(new String[]{"TLSv1.2"});
                ss.startHandshake();
                return ss;
            } catch (SSLException e) {
                if (first == null) first = e;
                try {
                    if (ss != null) ss.close();
                    else raw.close();
                } catch (IOException ignored) {
                }
                TvLog.d("TLS attempt " + attempt + " failed: " + e);
            } catch (IOException e) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
                throw e;
            }
        }
        throw first;
    }
}
