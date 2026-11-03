package com.tapingo.iremote.atv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Minimal encoder/decoder for the AOSP Polo pairing protocol (OuterMessage). */
public final class PairingProtoCodec {
    public static final int TYPE_PAIRING_REQUEST = 10;
    public static final int TYPE_PAIRING_REQUEST_ACK = 11;
    public static final int TYPE_OPTIONS = 20;
    public static final int TYPE_CONFIGURATION = 30;
    public static final int TYPE_CONFIGURATION_ACK = 31;
    public static final int TYPE_SECRET = 40;
    public static final int TYPE_SECRET_ACK = 41;
    public static final int STATUS_OK = 200;

    private PairingProtoCodec() {}

    public static final class OuterMessage {
        public final int version, status, type;
        public final byte[] payload;
        OuterMessage(int version, int status, int type, byte[] payload) {
            this.version=version; this.status=status; this.type=type; this.payload=payload;
        }
    }

    public static byte[] pairingRequest(String serviceName, String clientName) {
        Writer p = new Writer();
        p.string(1, serviceName);
        p.string(2, clientName);
        return outer(TYPE_PAIRING_REQUEST, p.bytes());
    }

    /** Client advertises six-character hexadecimal input and INPUT role. */
    public static byte[] options() {
        Writer encoding = new Writer();
        encoding.varint(1, 3); // ENCODING_TYPE_HEXADECIMAL
        encoding.varint(2, 6); // six hex symbols
        Writer options = new Writer();
        options.message(1, encoding.bytes()); // input_encodings
        options.varint(3, 1); // ROLE_TYPE_INPUT
        return outer(TYPE_OPTIONS, options.bytes());
    }

    public static byte[] configuration() {
        Writer encoding = new Writer();
        encoding.varint(1, 3); // hexadecimal
        encoding.varint(2, 6);
        Writer config = new Writer();
        config.message(1, encoding.bytes());
        config.varint(2, 1); // client_role INPUT
        return outer(TYPE_CONFIGURATION, config.bytes());
    }

    public static byte[] secret(byte[] secret) {
        Writer p = new Writer();
        p.message(1, secret);
        return outer(TYPE_SECRET, p.bytes());
    }

    public static byte[] outer(int type, byte[] payload) {
        Writer w = new Writer();
        w.varint(1, 2);       // protocol_version
        w.varint(2, STATUS_OK);
        w.varint(3, type);
        w.message(4, payload);
        return w.bytes();
    }

    public static OuterMessage parseOuter(byte[] bytes) throws IOException {
        Reader r = new Reader(bytes);
        int version=0, status=0, type=0;
        byte[] payload=null;
        while (r.hasRemaining()) {
            long tag=r.varint();
            int field=(int)(tag >>> 3), wire=(int)(tag & 7);
            if (field==1 && wire==0) version=(int)r.varint();
            else if (field==2 && wire==0) status=(int)r.varint();
            else if (field==3 && wire==0) type=(int)r.varint();
            else if (field==4 && wire==2) payload=r.lengthDelimited();
            else r.skip(wire);
        }
        if (version==0 || status==0 || type==0 || payload==null)
            throw new IOException("Incomplete Polo OuterMessage");
        if (status != STATUS_OK)
            throw new IOException("TV returned Polo status " + status + " (type " + type + ")");
        return new OuterMessage(version,status,type,payload);
    }

    public static void requireType(OuterMessage m, int expected) throws IOException {
        if (m.type != expected) throw new IOException("Unexpected Polo message type " + m.type + "; expected " + expected);
    }

    /** Read nested SecretAck.secret (field 1). */
    public static byte[] parseSecretAck(byte[] payload) throws IOException {
        Reader r=new Reader(payload);
        while (r.hasRemaining()) {
            long tag=r.varint(); int field=(int)(tag>>>3), wire=(int)(tag&7);
            if (field==1 && wire==2) return r.lengthDelimited();
            r.skip(wire);
        }
        throw new IOException("SecretAck did not contain a secret");
    }

    private static final class Writer {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        void varint(int field, long value) { rawVarint(((long)field<<3)); rawVarint(value); }
        void string(int field, String value) { message(field, value.getBytes(StandardCharsets.UTF_8)); }
        void message(int field, byte[] value) { rawVarint(((long)field<<3)|2); rawVarint(value.length); out.write(value,0,value.length); }
        void rawVarint(long value) { while ((value & ~0x7fL)!=0) { out.write((int)((value&0x7f)|0x80)); value >>>=7; } out.write((int)value); }
        byte[] bytes() { return out.toByteArray(); }
    }

    private static final class Reader {
        final byte[] data; int pos;
        Reader(byte[] data) { this.data=data; }
        boolean hasRemaining() { return pos<data.length; }
        long varint() throws IOException {
            long result=0;
            for (int shift=0; shift<64; shift+=7) {
                if (pos>=data.length) throw new IOException("Truncated protobuf varint");
                int b=data[pos++]&0xff;
                if (shift==63 && (b&0xfe)!=0) throw new IOException("Protobuf varint overflow");
                result |= (long)(b&0x7f)<<shift;
                if ((b&0x80)==0) return result;
            }
            throw new IOException("Malformed protobuf varint");
        }
        byte[] lengthDelimited() throws IOException {
            long n=varint();
            if (n<0 || n>data.length-pos) throw new IOException("Invalid protobuf field length");
            byte[] value=Arrays.copyOfRange(data,pos,pos+(int)n); pos+=(int)n; return value;
        }
        void skip(int wire) throws IOException {
            switch(wire) {
                case 0: varint(); return;
                case 1: advance(8); return;
                case 2: long n=varint(); if(n<0 || n>Integer.MAX_VALUE) throw new IOException("Invalid field size"); advance((int)n); return;
                case 5: advance(4); return;
                default: throw new IOException("Unsupported protobuf wire type " + wire);
            }
        }
        void advance(int n) throws IOException { if(n<0 || n>data.length-pos) throw new IOException("Truncated protobuf field"); pos+=n; }
    }
}
