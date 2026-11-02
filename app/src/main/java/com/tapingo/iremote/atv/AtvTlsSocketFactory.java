package com.tapingo.iremote.atv;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS 1.2 socket setup for the TV's self-signed Remote v2 service. */
public final class AtvTlsSocketFactory {
    private static final char[] STORE_PASSWORD = "tapingo-local-identity".toCharArray();
    private AtvTlsSocketFactory() {}

    public static SSLSocket connect(String host, int port, ClientIdentityStore.Identity identity,
                                    int timeoutMillis) throws Exception {
        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        clientStore.load(null, STORE_PASSWORD);
        clientStore.setKeyEntry("tapingo-client", identity.privateKey, STORE_PASSWORD,
                new java.security.cert.Certificate[]{identity.certificate});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, STORE_PASSWORD);

        // The TV uses a self-signed certificate. First contact therefore cannot use public-CA
        // validation. The pairing code cryptographically proves the peer during the pairing flow.
        // Do not treat this trust policy alone as proof of a successful or trusted pairing.
        TrustManager[] trustManagers = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(kmf.getKeyManagers(), trustManagers, null);
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMillis);
        SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket(raw, host, port, true);
        ssl.setSoTimeout(timeoutMillis);
        ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
        ssl.startHandshake();
        return ssl;
    }
}
