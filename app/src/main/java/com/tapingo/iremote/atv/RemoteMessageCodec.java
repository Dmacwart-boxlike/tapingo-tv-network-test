package com.tapingo.iremote.atv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Android TV Remote v2 protocol codec.
 *
 * Handles RemoteMessage protobuf encoding and decoding.
 * Length-prefix framing remains the responsibility of
 * the existing ProtoFrameCodec.
 */
public final class RemoteMessageCodec {

    public static final int TYPE_CONFIGURE = 1;
    public static final int TYPE_SET_ACTIVE = 2;
    public static final int TYPE_ERROR = 3;
    public static final int TYPE_PING_REQUEST = 8;
    public static final int TYPE_PING_RESPONSE = 9;
    public static final int TYPE_KEY_INJECT = 10;
    public static final int TYPE_IME_KEY_INJECT = 20;
    public static final int TYPE_START = 40;
    public static final int TYPE_VOLUME = 50;

    public static final int FEATURE_PING = 1;
    public static final int FEATURE_KEY = 2;
    public static final int FEATURE_IME = 4;
    public static final int FEATURE_POWER = 32;
    public static final int FEATURE_VOLUME = 64;
    public static final int FEATURE_APP_LINK = 512;

    public static final int SUPPORTED_FEATURES = 615;

    public static final int DIRECTION_SHORT = 3;

    private RemoteMessageCodec() {}

    public static final class Message {
        public final int type;
        public final byte[] payload;

        Message(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    public static final class ConfigureInfo {
        public final int features;
        public final String manufacturer;
        public final String model;
        public final String softwareVersion;

        ConfigureInfo(
                int features,
                String manufacturer,
                String model,
                String softwareVersion
        ) {
            this.features = features;
            this.manufacturer = manufacturer;
            this.model = model;
            this.softwareVersion = softwareVersion;
        }
    }

    public static final class VolumeInfo {
        public final int maximum;
        public final int level;
        public final boolean muted;

        VolumeInfo(int maximum, int level, boolean muted) {
            this.maximum = maximum;
            this.level = level;
            this.muted = muted;
        }
    }

    /**
     * Creates the client's RemoteConfigure response.
     *
     * code1 = 1
     * device_info = 2
     */
    public static byte[] configure() {

        Writer device = new Writer();
        device.varint(3, 1);
        device.string(4, "1");
        device.string(5, "atvremote");
        device.string(6, "1.0.0");

        Writer message = new Writer();
        message.varint(1, SUPPORTED_FEATURES);
        message.message(2, device.bytes());

        return outer(TYPE_CONFIGURE, message.bytes());
    }

    /**
     * Activates the supported Remote v2 features.
     */
    public static byte[] setActive() {

        Writer message = new Writer();
        message.varint(1, SUPPORTED_FEATURES);

        return outer(TYPE_SET_ACTIVE, message.bytes());
    }

    /**
     * Replies to a TV ping using the same val1.
     */
    public static byte[] pingResponse(int value) {

        Writer message = new Writer();
        message.varint(1, value);

        return outer(TYPE_PING_RESPONSE, message.bytes());
    }

    /**
     * Creates a Remote v2 key injection command.
     */
    public static byte[] keyInject(int keyCode) {

        if (keyCode <= 0) {
            throw new IllegalArgumentException(
                    "Invalid Android key code"
            );
        }

        Writer key = new Writer();
        key.varint(1, keyCode);
        key.varint(2, DIRECTION_SHORT);

        return outer(TYPE_KEY_INJECT, key.bytes());
    }

    /**
     * Creates the top-level RemoteMessage.
     *
     * The message type is its protobuf field number.
     */
    private static byte[] outer(int type, byte[] payload) {

        Writer message = new Writer();
        message.message(type, payload);

        return message.bytes();
    }

    /**
     * Parses an incoming RemoteMessage.
     */
    public static Message parse(byte[] bytes)
            throws IOException {

        if (bytes == null || bytes.length == 0) {
            throw new IOException("Empty RemoteMessage");
        }

        Reader reader = new Reader(bytes);

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

            if (isKnownType(field) && wire == 2) {

                if (type != 0) {
                    throw new IOException(
                            "RemoteMessage contains multiple message types"
                    );
                }

                type = field;
                payload = reader.lengthDelimited();

            } else {
                reader.skip(wire);
            }
        }

        if (type == 0 || payload == null) {
            throw new IOException(
                    "RemoteMessage contains no recognized message"
            );
        }

        return new Message(type, payload);
    }

    /**
     * Reads the TV's supported feature information.
     */
    public static ConfigureInfo parseConfigure(byte[] payload)
            throws IOException {

        Reader reader = new Reader(payload);

        int features = 0;
        String manufacturer = "";
        String model = "";
        String softwareVersion = "";

        while (reader.hasRemaining()) {

            long tag = reader.varint();

            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (field == 1 && wire == 0) {
                features = checkedInt(reader.varint());

            } else if (field == 2 && wire == 2) {

                Reader device = new Reader(
                        reader.lengthDelimited()
                );

                while (device.hasRemaining()) {

                    long deviceTag = device.varint();

                    int deviceField =
                            (int) (deviceTag >>> 3);

                    int deviceWire =
                            (int) (deviceTag & 7);

                    if (deviceWire == 2
                            && deviceField == 1) {
                        model = device.string();

                    } else if (deviceWire == 2
                            && deviceField == 2) {
                        manufacturer = device.string();

                    } else if (deviceWire == 2
                            && deviceField == 6) {
                        softwareVersion = device.string();

                    } else {
                        device.skip(deviceWire);
                    }
                }

            } else {
                reader.skip(wire);
            }
        }

        return new ConfigureInfo(
                features,
                manufacturer,
                model,
                softwareVersion
        );
    }

    /**
     * Reads the first value from a ping request.
     */
    public static int parsePingRequest(byte[] payload)
            throws IOException {

        Reader reader = new Reader(payload);

        while (reader.hasRemaining()) {

            long tag = reader.varint();

            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (field == 1 && wire == 0) {
                return checkedInt(reader.varint());
            }

            reader.skip(wire);
        }

        throw new IOException(
                "Ping request has no val1"
        );
    }

    /**
     * Reads the TV's power state.
     */
    public static boolean parseStarted(byte[] payload)
            throws IOException {

        Reader reader = new Reader(payload);

        while (reader.hasRemaining()) {

            long tag = reader.varint();

            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (field == 1 && wire == 0) {
                return reader.varint() != 0;
            }

            reader.skip(wire);
        }

        throw new IOException(
                "RemoteStart has no started field"
        );
    }

    /**
     * Reads volume information.
     *
     * volume_max = field 6
     * volume_level = field 7
     * volume_muted = field 8
     */
    public static VolumeInfo parseVolume(byte[] payload)
            throws IOException {

        Reader reader = new Reader(payload);

        int maximum = 0;
        int level = 0;
        boolean muted = false;

        while (reader.hasRemaining()) {

            long tag = reader.varint();

            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);

            if (wire == 0 && field == 6) {
                maximum = checkedInt(reader.varint());

            } else if (wire == 0 && field == 7) {
                level = checkedInt(reader.varint());

            } else if (wire == 0 && field == 8) {
                muted = reader.varint() != 0;

            } else {
                reader.skip(wire);
            }
        }

        return new VolumeInfo(maximum, level, muted);
    }

    private static boolean isKnownType(int type) {

        return type == TYPE_CONFIGURE
                || type == TYPE_SET_ACTIVE
                || type == TYPE_ERROR
                || type == TYPE_PING_REQUEST
                || type == TYPE_PING_RESPONSE
                || type == TYPE_KEY_INJECT
                || type == TYPE_IME_KEY_INJECT
                || type == TYPE_START
                || type == TYPE_VOLUME;
    }

    private static int checkedInt(long value)
            throws IOException {

        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException(
                    "Protobuf integer outside supported range"
            );
        }

        return (int) value;
    }

    private static final class Writer {

        private final ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        void varint(int field, long value) {

            if (field <= 0 || value < 0) {
                throw new IllegalArgumentException(
                        "Invalid protobuf integer"
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
                        "Invalid protobuf message"
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
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        boolean hasRemaining() {
            return position < data.length;
        }

        long varint() throws IOException {

            long result = 0;

            for (int shift = 0; shift < 64; shift += 7) {

                if (position >= data.length) {
                    throw new IOException(
                            "Truncated protobuf varint"
                    );
                }

                int value = data[position++] & 0xff;

                if (shift == 63 && (value & 0xfe) != 0) {
                    throw new IOException(
                            "Protobuf varint overflow"
                    );
                }

                result |=
                        (long) (value & 0x7f) << shift;

                if ((value & 0x80) == 0) {
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
                    || length > data.length - position) {

                throw new IOException(
                        "Invalid protobuf field length"
                );
            }

            int size = (int) length;

            byte[] result = Arrays.copyOfRange(
                    data,
                    position,
                    position + size
            );

            position += size;

            return result;
        }

        String string() throws IOException {

            return new String(
                    lengthDelimited(),
                    StandardCharsets.UTF_8
            );
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
                            "Unsupported protobuf wire type "
                                    + wire
                    );
            }
        }

        void advance(int count) throws IOException {

            if (count < 0
                    || count > data.length - position) {

                throw new IOException(
                        "Truncated protobuf field"
                );
            }

            position += count;
        }
    }
}