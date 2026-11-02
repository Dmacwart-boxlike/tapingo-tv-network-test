package com.tapingo.iremote.atv;

import android.content.Context;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Creates and persists the client identity used by Android TV Remote v2.
 * The PKCS#12 file is stored in app-private internal storage, never external storage.
 * Reuse the same identity for pairing and all later control connections.
 */
public final class ClientIdentityStore {
    private static final String FILE_NAME = "tapingo-atv-client.p12";
    private static final char[] STORE_PASSWORD = "tapingo-local-identity".toCharArray();
    private static final String ALIAS = "tapingo-client";

    private ClientIdentityStore() {}

    public static Identity loadOrCreate(Context context) throws Exception {
        installBouncyCastleIfNeeded();
        File file = new File(context.getFilesDir(), FILE_NAME);
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        if (file.exists()) {
            try (FileInputStream input = new FileInputStream(file)) {
                keyStore.load(input, STORE_PASSWORD);
            }
            PrivateKey key = (PrivateKey) keyStore.getKey(ALIAS, STORE_PASSWORD);
            X509Certificate cert = (X509Certificate) keyStore.getCertificate(ALIAS);
            if (key != null && cert != null) return new Identity(key, cert, file);
            // Invalid/incomplete file: fail rather than silently changing a previously paired identity.
            throw new IllegalStateException("Saved Tapingo identity is incomplete; do not delete it unless you intend to pair again.");
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 60L * 60L * 1000L);
        Date notAfter = new Date(now + 10L * 365L * 24L * 60L * 60L * 1000L);
        X500Name subject = new X500Name("CN=Tapingo iRemote");
        BigInteger serial = new BigInteger(160, new SecureRandom()).abs().add(BigInteger.ONE);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, pair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(pair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(holder);
        certificate.checkValidity(new Date());
        certificate.verify(pair.getPublic());

        keyStore.load(null, STORE_PASSWORD);
        keyStore.setKeyEntry(ALIAS, pair.getPrivate(), STORE_PASSWORD,
                new java.security.cert.Certificate[]{certificate});
        try (FileOutputStream output = new FileOutputStream(file)) {
            keyStore.store(output, STORE_PASSWORD);
            output.getFD().sync();
        }
        return new Identity(pair.getPrivate(), certificate, file);
    }

    private static void installBouncyCastleIfNeeded() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static final class Identity {
        public final PrivateKey privateKey;
        public final X509Certificate certificate;
        public final File backingFile;

        private Identity(PrivateKey privateKey, X509Certificate certificate, File backingFile) {
            this.privateKey = privateKey;
            this.certificate = certificate;
            this.backingFile = backingFile;
        }
    }
}
