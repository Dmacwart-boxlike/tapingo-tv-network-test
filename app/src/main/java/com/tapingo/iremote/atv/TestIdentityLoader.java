package com.tapingo.iremote.atv;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Enumeration;

public final class TestIdentityLoader {

    private static final String INTERNAL_PASSWORD =
            "tapingo-local-identity";

    private static final String INTERNAL_ALIAS =
            "tapingo-client";

    private static final String IDENTITY_FILE =
            "tapingo-atv-client.p12";

    private TestIdentityLoader() {
    }

    public static ClientIdentityStore.Identity importIdentity(
            Context context,
            Uri sourceUri,
            char[] externalPassword
    ) throws Exception {

        if (context == null || sourceUri == null
                || externalPassword == null) {
            throw new IllegalArgumentException(
                    "Missing identity import information."
            );
        }

        File destination = new File(
                context.getFilesDir(),
                IDENTITY_FILE
        );

        if (destination.exists()) {
            throw new IllegalStateException(
                    "An app identity already exists. "
                    + "Import cancelled to protect it."
            );
        }

        KeyStore externalStore = KeyStore.getInstance("PKCS12");

        try (InputStream input =
                     context.getContentResolver()
                             .openInputStream(sourceUri)) {

            if (input == null) {
                throw new IllegalStateException(
                        "Could not open the selected identity file."
                );
            }

            externalStore.load(input, externalPassword);
        }

        PrivateKey privateKey = null;
        Certificate certificate = null;

        Enumeration<String> aliases =
                externalStore.aliases();

        while (aliases.hasMoreElements()) {

            String alias = aliases.nextElement();

            if (!externalStore.isKeyEntry(alias)) {
                continue;
            }

            Key key = externalStore.getKey(
                    alias,
                    externalPassword
            );

            Certificate cert =
                    externalStore.getCertificate(alias);

            if (key instanceof PrivateKey && cert != null) {
                privateKey = (PrivateKey) key;
                certificate = cert;
                break;
            }
        }

        if (privateKey == null || certificate == null) {
            throw new IllegalStateException(
                    "No private key and certificate were found "
                    + "in the selected PKCS12 file."
            );
        }

        verifyKeyPair(privateKey, certificate);

        KeyStore internalStore =
                KeyStore.getInstance("PKCS12");

        internalStore.load(null, INTERNAL_PASSWORD.toCharArray());

        internalStore.setKeyEntry(
                INTERNAL_ALIAS,
                privateKey,
                INTERNAL_PASSWORD.toCharArray(),
                new Certificate[]{certificate}
        );

        File temporary = new File(
                context.getFilesDir(),
                IDENTITY_FILE + ".tmp"
        );

        try (FileOutputStream output =
                     new FileOutputStream(temporary)) {

            internalStore.store(
                    output,
                    INTERNAL_PASSWORD.toCharArray()
            );

            output.flush();
            output.getFD().sync();
        }

        if (!temporary.renameTo(destination)) {
            temporary.delete();

            throw new IllegalStateException(
                    "Could not save the imported identity "
                    + "inside app storage."
            );
        }

        return ClientIdentityStore.loadOrCreate(context);
    }

    private static void verifyKeyPair(
            PrivateKey privateKey,
            Certificate certificate
    ) throws Exception {

        Signature signer =
                Signature.getInstance("SHA256withRSA");

        signer.initSign(privateKey);
        signer.update("Tapingo Remote v2 identity check"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        byte[] signature = signer.sign();

        Signature verifier =
                Signature.getInstance("SHA256withRSA");

        verifier.initVerify(certificate.getPublicKey());
        verifier.update("Tapingo Remote v2 identity check"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException(
                    "The private key does not match the certificate."
            );
        }
    }
}