package com.tapingo.iremote.atv;

import android.content.Context;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.Certificate;
import java.util.Locale;
import javax.net.ssl.SSLSocket;

/** Blocking Android TV Remote v2 pairing client. Call from a worker thread, never the UI thread. */
public final class AtvPairingClient {
    public static final int DEFAULT_PORT=6467;
    private final Context appContext;
    private final ClientIdentityStore.Identity identity;
    private final int timeoutMillis;

    public AtvPairingClient(Context context, ClientIdentityStore.Identity identity, int timeoutMillis) {
        this.appContext=context.getApplicationContext();
        this.identity=identity;
        this.timeoutMillis=timeoutMillis;
    }

    /** Opens TLS and advances through request/options/configuration. TV should display its code on success. */
    public PairingSession begin(String host) throws Exception {
        SSLSocket socket=AtvTlsSocketFactory.connect(host,DEFAULT_PORT,identity,timeoutMillis);
        try {
            InputStream in=socket.getInputStream(); OutputStream out=socket.getOutputStream();
            send(out,PairingProtoCodec.pairingRequest("atvremote","Tapingo iRemote"));
            expect(in,PairingProtoCodec.TYPE_PAIRING_REQUEST_ACK);
            send(out,PairingProtoCodec.options());
            // Server may send its own Options message; require it before client Configuration.
            expect(in,PairingProtoCodec.TYPE_OPTIONS);
            send(out,PairingProtoCodec.configuration());
            expect(in,PairingProtoCodec.TYPE_CONFIGURATION_ACK);
            Certificate[] peer=socket.getSession().getPeerCertificates();
            if(peer.length==0) throw new IllegalStateException("TV did not present a TLS certificate");
            return new PairingSession(socket,peer[0]);
        } catch(Exception e) {
            try { socket.close(); } catch(Exception ignored) {}
            throw e;
        }
    }

    public final class PairingSession implements Closeable {
        private final SSLSocket socket;
        private final Certificate tvCertificate;
        private boolean completed;
        private PairingSession(SSLSocket socket, Certificate tvCertificate) {
            this.socket=socket; this.tvCertificate=tvCertificate;
        }

        /** Accepts the six-character hexadecimal code shown by the TV and completes cryptographic pairing. */
        public synchronized String finish(String enteredCode) throws Exception {
            if(completed) throw new IllegalStateException("Pairing session already completed");
            String code=enteredCode==null?"":enteredCode.trim().toUpperCase(Locale.US);
            if(!code.matches("[0-9A-F]{6}")) throw new IllegalArgumentException("Enter the six-character hexadecimal code shown by the TV.");
            byte[] secret=deriveSecret(identity.certificate.getPublicKey(),tvCertificate.getPublicKey(),code);
            send(socket.getOutputStream(),PairingProtoCodec.secret(secret));
            PairingProtoCodec.OuterMessage ack=readOuter(socket.getInputStream());
            PairingProtoCodec.requireType(ack,PairingProtoCodec.TYPE_SECRET_ACK);
            byte[] echoed=PairingProtoCodec.parseSecretAck(ack.payload);
            if(!MessageDigest.isEqual(secret,echoed)) throw new SecurityException("TV pairing acknowledgement did not match the derived secret");
            // Pin only after the TV explicitly acknowledges the code-derived secret.
            TvIdentityStore.saveAfterPairing(appContext,tvCertificate);
            completed=true;
            return TvIdentityStore.fingerprint(tvCertificate);
        }

        public Certificate getTvCertificate() { return tvCertificate; }
        @Override public void close() { try { socket.close(); } catch(Exception ignored) {} }
    }

    private static byte[] deriveSecret(PublicKey clientKey, PublicKey serverKey, String code) throws Exception {
        if(!(clientKey instanceof RSAPublicKey) || !(serverKey instanceof RSAPublicKey))
            throw new IllegalArgumentException("Android TV Remote v2 pairing requires RSA client and TV certificates");
        RSAPublicKey c=(RSAPublicKey)clientKey, s=(RSAPublicKey)serverKey;
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        sha.update(unsigned(c.getModulus()));
        sha.update(unsignedExponent(c.getPublicExponent()));
        sha.update(unsigned(s.getModulus()));
        sha.update(unsignedExponent(s.getPublicExponent()));
        byte[] codeTail=new byte[]{
                (byte)Integer.parseInt(code.substring(2,4),16),
                (byte)Integer.parseInt(code.substring(4,6),16)
        };
        sha.update(codeTail);
        byte[] result=sha.digest();
        int check=Integer.parseInt(code.substring(0,2),16);
        if((result[0]&0xff)!=check) throw new SecurityException("Pairing code check failed. Re-enter the code displayed on the TV.");
        return result;
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] raw=value.toByteArray();
        if(raw.length>1 && raw[0]==0) return java.util.Arrays.copyOfRange(raw,1,raw.length);
        return raw;
    }

    // The protocol's RSA exponent representation is four bytes (e.g. 00 01 00 01).
    private static byte[] unsignedExponent(BigInteger value) {
        byte[] raw=unsigned(value);
        if(raw.length>4) throw new IllegalArgumentException("RSA exponent is unexpectedly large");
        byte[] padded=new byte[4];
        System.arraycopy(raw,0,padded,4-raw.length,raw.length);
        return padded;
    }

    private static void send(OutputStream out,byte[] message) throws Exception { ProtoFrameCodec.writeFrame(out,message); }
    private static PairingProtoCodec.OuterMessage readOuter(InputStream in) throws Exception {
        return PairingProtoCodec.parseOuter(ProtoFrameCodec.readFrame(in));
    }
    private static void expect(InputStream in,int type) throws Exception {
        PairingProtoCodec.requireType(readOuter(in),type);
    }
}
