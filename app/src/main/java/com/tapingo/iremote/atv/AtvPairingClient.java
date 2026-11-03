package com.tapingo.iremote.atv;

import android.content.Context;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Locale;
import javax.net.ssl.SSLSocket;

/** Blocking Android TV Remote v2 pairing client. */
public final class AtvPairingClient {

    public static final int DEFAULT_PORT = 6467;

    private final Context appContext;
    private final ClientIdentityStore.Identity identity;
    private final int timeoutMillis;

    public AtvPairingClient(
            Context context,
            ClientIdentityStore.Identity identity,
            int timeoutMillis
    ) {
        if (context == null) {
            throw new IllegalArgumentException("Context is required");
        }
        if (identity == null || identity.certificate == null
                || identity.privateKey == null) {
            throw new IllegalArgumentException(
                    "Valid client identity is required"
            );
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "Timeout must be greater than zero"
            );
        }

        this.appContext = context.getApplicationContext();
        this.identity = identity;
        this.timeoutMillis = timeoutMillis;
    }

    public PairingSession begin(String host) throws Exception {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("TV IP address is required");
        }

        SSLSocket socket = AtvTlsSocketFactory.connectForPairing(
                host.trim(),
                DEFAULT_PORT,
                identity,
                timeoutMillis
        );

        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            send(out, PairingProtoCodec.pairingRequest(
                    "atvremote",
                    "Tapingo iRemote"
            ));

            expect(in, PairingProtoCodec.TYPE_PAIRING_REQUEST_ACK);

            send(out, PairingProtoCodec.options());

            expect(in, PairingProtoCodec.TYPE_OPTIONS);

            send(out, PairingProtoCodec.configuration());

            expect(in, PairingProtoCodec.TYPE_CONFIGURATION_ACK);

            Certificate[] peer = socket.getSession().getPeerCertificates();

            if (peer == null || peer.length == 0) {
                throw new IllegalStateException(
                        "TV did not present a TLS certificate"
                );
            }

            return new PairingSession(socket, peer[0]);

        } catch (Exception e) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    public final class PairingSession implements Closeable {

        private final SSLSocket socket;
        private final Certificate tvCertificate;
        private boolean completed;
        private boolean closed;

        private PairingSession(
                SSLSocket socket,
                Certificate tvCertificate
        ) {
            this.socket = socket;
            this.tvCertificate = tvCertificate;
        }

        public synchronized String finish(String enteredCode)
                throws Exception {

            if (closed) {
                throw new IllegalStateException(
                        "Pairing session is closed"
                );
            }

            if (completed) {
                throw new IllegalStateException(
                        "Pairing session already completed"
                );
            }

            String code = enteredCode == null
                    ? ""
                    : enteredCode.trim().toUpperCase(Locale.US);

            if (!code.matches("[0-9A-F]{6}")) {
                throw new IllegalArgumentException(
                        "Enter the six-character hexadecimal code shown by the TV."
                );
            }

            byte[] secret = deriveSecret(
                    identity.certificate.getPublicKey(),
                    tvCertificate.getPublicKey(),
                    code
            );

            send(socket.getOutputStream(), PairingProtoCodec.secret(secret));

            PairingProtoCodec.OuterMessage ack =
                    readOuter(socket.getInputStream());

            PairingProtoCodec.requireType(
                    ack,
                    PairingProtoCodec.TYPE_SECRET_ACK
            );

            byte[] echoed =
                    PairingProtoCodec.parseSecretAck(ack.payload);

            if (!MessageDigest.isEqual(secret, echoed)) {
                throw new SecurityException(
                        "TV pairing acknowledgement did not match the derived secret"
                );
            }

            // The TV identity is pinned only after successful SecretAck.
            TvIdentityStore.saveAfterPairing(
                    appContext,
                    tvCertificate
            );

            completed = true;

            return TvIdentityStore.fingerprint(tvCertificate);
        }

        public Certificate getTvCertificate() {
            return tvCertificate;
        }

        public synchronized boolean isCompleted() {
            return completed;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;

                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static byte[] deriveSecret(
            PublicKey clientKey,
            PublicKey serverKey,
            String code
    ) throws Exception {

        if (!(clientKey instanceof RSAPublicKey)
                || !(serverKey instanceof RSAPublicKey)) {
            throw new IllegalArgumentException(
                    "Android TV Remote v2 pairing requires RSA certificates"
            );
        }

        RSAPublicKey client = (RSAPublicKey) clientKey;
        RSAPublicKey server = (RSAPublicKey) serverKey;

        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        sha.update(unsigned(client.getModulus()));
        sha.update(unsignedExponent(client.getPublicExponent()));

        sha.update(unsigned(server.getModulus()));
        sha.update(unsignedExponent(server.getPublicExponent()));

        byte[] codeTail = new byte[] {
                (byte) Integer.parseInt(code.substring(2, 4), 16),
                (byte) Integer.parseInt(code.substring(4, 6), 16)
        };

        sha.update(codeTail);

        byte[] result = sha.digest();

        int check = Integer.parseInt(code.substring(0, 2), 16);

        if ((result[0] & 0xff) != check) {
            throw new SecurityException(
                    "Pairing code check failed. Re-enter the code displayed on the TV."
            );
        }

        return result;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] raw = value.toByteArray();

        if (raw.length > 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }

        return raw;
    }

    private static byte[] unsignedExponent(BigInteger value) {
        byte[] raw = unsigned(value);

        if (raw.length > 4) {
            throw new IllegalArgumentException(
                    "RSA exponent is unexpectedly large"
            );
        }

        byte[] padded = new byte[4];

        System.arraycopy(
                raw,
                0,
                padded,
                4 - raw.length,
                raw.length
        );

        return padded;
    }

    private static void send(OutputStream out, byte[] message)
            throws Exception {
        ProtoFrameCodec.writeFrame(out, message);
    }

    private static PairingProtoCodec.OuterMessage readOuter(InputStream in)
            throws Exception {
        return PairingProtoCodec.parseOuter(
                ProtoFrameCodec.readFrame(in)
        );
    }

    private static void expect(InputStream in, int type)
            throws Exception {
        PairingProtoCodec.requireType(readOuter(in), type);
    }
}