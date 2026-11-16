package com.tapingo.iremote.atv;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Enumeration;

/** Imports the already-paired Windows PKCS12 identity into app-private storage. */
public final class TestIdentityLoader {

    private static final String INTERNAL_PASSWORD = "tapingo-local-identity";
    private static final String INTERNAL_ALIAS = "tapingo-client";
    private static final String IDENTITY_FILE = "tapingo-atv-client.p12";

    private TestIdentityLoader() {
    }

    /** Existing Uri entry point retained for compatibility with the old picker flow. */
    public static ClientIdentityStore.Identity importIdentity(
            Context context,
            Uri sourceUri,
            char[] sourcePassword
    ) throws Exception {
        if (context == null || sourceUri == null || sourcePassword == null) {
            throw new IllegalArgumentException("Context, identity file and password are required.");
        }

        try (InputStream input = context.getContentResolver().openInputStream(sourceUri)) {
            if (input == null) {
                throw new java.io.IOException("Could not open the selected identity file.");
            }
            return importIdentityFromStream(context, input, sourcePassword);
        }
    }

    /** New file entry point used by the laptop-to-TV transfer server. */
    public static ClientIdentityStore.Identity importIdentity(
            Context context,
            File sourceFile,
            char[] sourcePassword
    ) throws Exception {
        if (context == null || sourceFile == null || sourcePassword == null) {
            throw new IllegalArgumentException("Context, identity file and password are required.");
        }
        if (!sourceFile.isFile() || !sourceFile.canRead() || sourceFile.length() == 0) {
            throw new java.io.IOException("The received identity file is missing or empty.");
        }

        try (InputStream input = new FileInputStream(sourceFile)) {
            return importIdentityFromStream(context, input, sourcePassword);
        }
    }

    private static ClientIdentityStore.Identity importIdentityFromStream(
            Context context,
            InputStream input,
            char[] sourcePassword
    ) throws Exception {
        File destination = new File(context.getFilesDir(), IDENTITY_FILE);
        if (destination.exists()) {
            throw new java.io.IOException(
                    "An identity is already stored on this TV. It was not overwritten."
            );
        }

        KeyStore sourceStore = KeyStore.getInstance("PKCS12");
        sourceStore.load(input, sourcePassword);

        PrivateKey privateKey = null;
        Certificate certificate = null;
        Enumeration<String> aliases = sourceStore.aliases();

        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Key key = sourceStore.getKey(alias, sourcePassword);
            Certificate candidateCertificate = sourceStore.getCertificate(alias);

            if (key instanceof PrivateKey && candidateCertificate != null) {
                privateKey = (PrivateKey) key;
                certificate = candidateCertificate;
                break;
            }
        }

        if (privateKey == null || certificate == null) {
            throw new java.security.KeyStoreException(
                    "The PKCS12 file does not contain a private key and certificate."
            );
        }

        verifyKeyPair(privateKey, certificate);

        KeyStore internalStore = KeyStore.getInstance("PKCS12");
        internalStore.load(null, INTERNAL_PASSWORD.toCharArray());
        internalStore.setKeyEntry(
                INTERNAL_ALIAS,
                privateKey,
                INTERNAL_PASSWORD.toCharArray(),
                new Certificate[]{certificate}
        );

        File temporary = new File(context.getFilesDir(), IDENTITY_FILE + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new java.io.IOException("Could not clear the temporary identity file.");
        }

        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                internalStore.store(output, INTERNAL_PASSWORD.toCharArray());
                output.flush();
                output.getFD().sync();
            }

            if (!temporary.renameTo(destination)) {
                throw new java.io.IOException("Could not save the imported identity on the TV.");
            }
        } catch (Exception e) {
            if (temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            throw e;
        }

        return ClientIdentityStore.loadOrCreate(context);
    }

    private static void verifyKeyPair(
            PrivateKey privateKey,
            Certificate certificate
    ) throws Exception {
        byte[] challenge = new byte[32];
        new java.security.SecureRandom().nextBytes(challenge);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(challenge);
        byte[] signed = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);

        if (!verifier.verify(signed)) {
            throw new java.security.KeyStoreException(
                    "The private key does not match the certificate in this identity file."
            );
        }
    }
}
