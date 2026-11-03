```java
package com.tapingo.iremote.atv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Android TV Remote v2 Polo pairing protocol codec.
 *
 * Uses the flat OuterMessage structure found in shipping
 * Android TV Remote v2 implementations.
 */
public final class PairingProtoCodec {

    public static final int TYPE_PAIRING_REQUEST = 10;
    public static final int TYPE_PAIRING_REQUEST_ACK = 11;
    public static final int TYPE_OPTIONS = 20;
    public static final int TYPE_CONFIGURATION = 30;
    public static final int TYPE_CONFIGURATION_ACK = 31;
    public static final int TYPE_SECRET = 40;
    public static final int TYPE_SECRET_ACK = 41;

    public static final int STATUS_OK = 200;
    private static final int PROTOCOL_VERSION = 2;

    private PairingProtoCodec() {}

    public static final class OuterMessage {
        public final int version;
        public final int status;
        public final int type;
        public final byte[] payload;

        OuterMessage(int version, int status, int type, byte[] payload) {
            this.version = version;
            this.status = status;
            this.type = type;
            this.payload = payload;
        }
    }

    /**
     * PairingRequest:
     * service_name = 1
     * client_name  = 2
     */
    public static byte[] pairingRequest(
            String serviceName,
            String clientName
    ) {
        if (serviceName == null || clientName == null) {
            throw new IllegalArgumentException(
                    "Service name and client name are required"
            );
        }

        Writer message = new Writer();
        message.string(1, serviceName);
        message.string(2, clientName);

        return outer(TYPE_PAIRING_REQUEST, message.bytes());
    }

    /**
     * Options:
     * input_encodings = 1
     * output_encodings = 2
     * preferred_role = 3
     *
     * The client advertises hexadecimal input
     * consisting of six characters.
     */
    public static byte[] options() {

        Writer encoding = new Writer();
        encoding.varint(1, 3); // HEXADECIMAL
        encoding.varint(2, 6); // Six characters

        Writer message = new Writer();
        message.message(1, encoding.bytes());
        message.varint(3, 1); // ROLE_TYPE_INPUT

        return outer(TYPE_OPTIONS, message.bytes());
    }

    /**
     * Configuration:
     * encoding = 1
     * client_role = 2
     */
    public static byte[] configuration() {

        Writer encoding = new Writer();
        encoding.varint(1, 3); // HEXADECIMAL
        encoding.varint(2, 6); // Six characters

        Writer message = new Writer();
        message.message(1, encoding.bytes());
        message.varint(2, 1); // INPUT role

        return outer(TYPE_CONFIGURATION, message.bytes());
    }

    /**
     * Secret:
     * secret = 1
     */
    public static byte[] secret(byte[] secret) {

        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException(
                    "Pairing secret cannot be empty"
            );
        }

        Writer message = new Writer();
        message.message(1, secret);

        return outer(TYPE_SECRET, message.bytes());
    }

    /**
     * Creates the shipping-device OuterMessage format.
     *
     * protocol_version = field 1
     * status          = field 2
     *
     * The actual message is encoded directly using
     * its dedicated field number:
     *
     * PairingRequest       = 10
     * PairingRequestAck    = 11
     * Options              = 20
     * Configuration        = 30
     * ConfigurationAck    = 31
     * Secret               = 40
     * SecretAck            = 41
     */
    public static byte[] outer(int type, byte[] payload) {

        if (payload == null) {
            throw new IllegalArgumentException(
                    "Polo payload cannot be null"
            );
        }

        if (!isKnownMessageType(type)) {
            throw new IllegalArgumentException(
                    "Unknown Polo message type: " + type
            );
        }

        Writer message = new Writer();

        message.varint(1, PROTOCOL_VERSION);
        message.varint(2, STATUS_OK);

        // Flat OuterMessage: message type is the field number.
        message.message(type, payload);

        return message.bytes();
    }

    /**
     * Parses the flat shipping-device OuterMessage.
     */
    public static OuterMessage parseOuter(byte[] bytes)
            throws IOException {

        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty Polo OuterMessage");
        }

        Reader reader = new Reader(bytes);

        int version = 0;
        int status = 0;
        int type = 0;
        byte[] payload = null;

        while (reader.hasRemaining()) {

            long tag = reader.varint();

            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (field == 0) {
                throw new IOException(
                        "Invalid protobuf field number 0"
                );
            }

            if (field == 1 && wire == 0) {
                version = checkedInt(reader.varint());

            } else if (field == 2 && wire == 0) {
                status = checkedInt(reader.varint());

            } else if (isKnownMessageType(field) && wire == 2) {

                if (type != 0) {
                    throw new IOException(
                            "OuterMessage contains multiple payloads"
                    );
                }

                type = field;
                payload = reader.lengthDelimited();

            } else {
                reader.skip(wire);
            }
        }

        if (version == 0) {
            throw new IOException(
                    "Polo OuterMessage has no protocol version"
            );
        }

        if (version != PROTOCOL_VERSION) {
            throw new IOException(
                    "Unsupported Polo protocol version: " + version
            );
        }

        if (type == 0 || payload == null) {
            throw new IOException(
                    "Polo OuterMessage has no recognized message payload"
            );
        }

        if (status != 0 && status != STATUS_OK) {
            throw new IOException(
                    "TV returned Polo status " + status
                            + " (message type " + type + ")"
            );
        }

        return new OuterMessage(
                version,
                status,
                type,
                payload
        );
    }

    public static void requireType(
            OuterMessage message,
            int expected
    ) throws IOException {

        if (message == null) {
            throw new IOException("Polo message is null");
        }

        if (message.type != expected) {
            throw new IOException(
                    "Unexpected Polo message type "
                            + message.type
                            + "; expected "
                            + expected
            );
        }
    }

    /**
     * SecretAck:
     * secret = 1
     */
    public static byte[] parseSecretAck(byte[] payload)
            throws IOException {

        if (payload == null) {
            throw new IOException("SecretAck payload is null");
        }

        Reader reader = new Reader(payload);

        while (reader.hasRemaining()) {

            long tag = reader.varint();

            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (field == 1 && wire == 2) {
                byte[] value = reader.lengthDelimited();

                if (value.length == 0) {
                    throw new IOException(
                            "SecretAck contains an empty secret"
                    );
                }

                return value;
            }

            reader.skip(wire);
        }

        throw new IOException(
                "SecretAck did not contain a secret"
        );
    }

    private static boolean isKnownMessageType(int type) {
        return type == TYPE_PAIRING_REQUEST
                || type == TYPE_PAIRING_REQUEST_ACK
                || type == TYPE_OPTIONS
                || type == TYPE_CONFIGURATION
                || type == TYPE_CONFIGURATION_ACK
                || type == TYPE_SECRET
                || type == TYPE_SECRET_ACK;
    }

    private static int checkedInt(long value)
            throws IOException {

        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(
                    "Protobuf integer is outside supported range"
            );
        }

        return (int) value;
    }

    private static final class Writer {

        private final ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        void varint(int field, long value) {

            if (field <= 0) {
                throw new IllegalArgumentException(
                        "Invalid protobuf field number"
                );
            }

            rawVarint((long) field << 3);
            rawVarint(value);
        }

        void string(int field, String value) {

            if (value == null) {
                throw new IllegalArgumentException(
                        "Protobuf string cannot be null"
                );
            }

            message(
                    field,
                    value.getBytes(StandardCharsets.UTF_8)
            );
        }

        void message(int field, byte[] value) {

            if (field <= 0 || value == null) {
                throw new IllegalArgumentException(
                        "Invalid protobuf message field"
                );
            }

            rawVarint(((long) field << 3) | 2);
            rawVarint(value.length);

            out.write(value, 0, value.length);
        }

        void rawVarint(long value) {

            while ((value & ~0x7fL) != 0) {
                out.write(
                        (int) ((value & 0x7f) | 0x80)
                );

                value >>>= 7;
            }

            out.write((int) value);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static final class Reader {

        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean hasRemaining() {
            return pos < data.length;
        }

        long varint() throws IOException {

            long result = 0;

            for (int shift = 0; shift < 64; shift += 7) {

                if (pos >= data.length) {
                    throw new IOException(
                            "Truncated protobuf varint"
                    );
                }

                int b = data[pos++] & 0xff;

                if (shift == 63 && (b & 0xfe) != 0) {
                    throw new IOException(
                            "Protobuf varint overflow"
                    );
                }

                result |= (long) (b & 0x7f) << shift;

                if ((b & 0x80) == 0) {
                    return result;
                }
            }

            throw new IOException(
                    "Malformed protobuf varint"
            );
        }

        byte[] lengthDelimited() throws IOException {

            long length = varint();

            if (length < 0
                    || length > Integer.MAX_VALUE
                    || length > data.length - pos) {

                throw new IOException(
                        "Invalid protobuf field length"
                );
            }

            int size = (int) length;

            byte[] value = Arrays.copyOfRange(
                    data,
                    pos,
                    pos + size
            );

            pos += size;

            return value;
        }

        void skip(int wire) throws IOException {

            switch (wire) {

                case 0:
                    varint();
                    return;

                case 1:
                    advance(8);
                    return;

                case 2:
                    long length = varint();

                    if (length < 0
                            || length > Integer.MAX_VALUE) {
                        throw new IOException(
                                "Invalid protobuf field size"
                        );
                    }

                    advance((int) length);
                    return;

                case 5:
                    advance(4);
                    return;

                default:
                    throw new IOException(
                            "Unsupported protobuf wire type " + wire
                    );
            }
        }

        void advance(int count) throws IOException {

            if (count < 0 || count > data.length - pos) {
                throw new IOException(
                        "Truncated protobuf field"
                );
            }

            pos += count;
        }
    }
}
```
