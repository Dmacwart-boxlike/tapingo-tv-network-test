```java
package com.tapingo.iremote.atv;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 1.2 socket factory for Android TV Remote v2.
 *
 * First pairing permits a self-signed certificate temporarily.
 * Normal connections require an exact previously stored certificate fingerprint.
 */
public final class AtvTlsSocketFactory {

    private static final char[] STORE_PASSWORD =
            "tapingo-local-identity".toCharArray();

    private AtvTlsSocketFactory() {}

    /**
     * First-time pairing only.
     *
     * The certificate is NOT trusted merely because TLS succeeds.
     * The Remote v2 pairing exchange must authenticate it afterward.
     */
    public static SSLSocket connectForPairing(
            String host,
            int port,
            ClientIdentityStore.Identity identity,
            int timeoutMillis
    ) throws Exception {

        return connectInternal(
                host, port, identity, timeoutMillis, null
        );
    }

    /**
     * Connects only if the TV presents the exact previously paired certificate.
     */
    public static SSLSocket connectPinned(
            String host,
            int port,
            ClientIdentityStore.Identity identity,
            int timeoutMillis,
            String expectedFingerprint
    ) throws Exception {

        if (expectedFingerprint == null ||
                !expectedFingerprint.matches("(?i)[0-9a-f]{64}")) {
            throw new SecurityException(
                    "A valid paired TV certificate fingerprint is required."
            );
        }

        return connectInternal(
                host, port, identity, timeoutMillis,
                expectedFingerprint
        );
    }

    private static SSLSocket connectInternal(
            String host,
            int port,
            ClientIdentityStore.Identity identity,
            int timeoutMillis,
            String expectedFingerprint
    ) throws Exception {

        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("TV address is required.");
        }

        if (identity == null ||
                identity.privateKey == null ||
                identity.certificate == null) {
            throw new IllegalArgumentException(
                    "A valid Tapingo client identity is required."
            );
        }

        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "Connection timeout must be positive."
            );
        }

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        clientStore.load(null, STORE_PASSWORD);

        clientStore.setKeyEntry(
                "tapingo-client",
                identity.privateKey,
                STORE_PASSWORD,
                new Certificate[]{identity.certificate}
        );

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm()
        );

        kmf.init(clientStore, STORE_PASSWORD);

        TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {

                    @Override
                    public void checkClientTrusted(
                            X509Certificate[] chain,
                            String authType
                    ) {
                        // This client does not validate incoming client certificates.
                    }

                    @Override
                    public void checkServerTrusted(
                            X509Certificate[] chain,
                            String authType
                    ) throws java.security.cert.CertificateException {

                        if (chain == null || chain.length == 0 ||
                                chain[0] == null) {
                            throw new java.security.cert.CertificateException(
                                    "TV did not present a server certificate."
                            );
                        }

                        // First pairing: temporarily permit the self-signed TV certificate.
                        // Authentication must still succeed through the Remote v2 code exchange.
                        if (expectedFingerprint == null) {
                            return;
                        }

                        try {
                            String actual = fingerprint(chain[0]);

                            if (!MessageDigest.isEqual(
                                    actual.getBytes("US-ASCII"),
                                    expectedFingerprint
                                            .toUpperCase(Locale.US)
                                            .getBytes("US-ASCII")
                            )) {
                                throw new java.security.cert.CertificateException(
                                        "TV certificate does not match the paired device."
                                );
                            }

                        } catch (java.security.cert.CertificateException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new java.security.cert.CertificateException(
                                    "Unable to verify TV certificate fingerprint.",
                                    e
                            );
                        }
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext context = SSLContext.getInstance("TLSv1.2");

        context.init(
                kmf.getKeyManagers(),
                trustManagers,
                null
        );

        Socket raw = new Socket();

        try {
            raw.connect(
                    new InetSocketAddress(host, port),
                    timeoutMillis
            );

            SSLSocket ssl = (SSLSocket)
                    context.getSocketFactory().createSocket(
                            raw, host, port, true
                    );

            ssl.setSoTimeout(timeoutMillis);
            ssl.setEnabledProtocols(new String[]{"TLSv1.2"});

            ssl.startHandshake();

            return ssl;

        } catch (Exception e) {
            try {
                raw.close();
            } catch (Exception ignored) {}

            throw e;
        }
    }

    private static String fingerprint(X509Certificate certificate)
            throws Exception {

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.getEncoded());

        StringBuilder result = new StringBuilder();

        for (byte value : digest) {
            result.append(
                    String.format(Locale.US, "%02X", value & 0xFF)
            );
        }

        return result.toString();
    }
}
```
