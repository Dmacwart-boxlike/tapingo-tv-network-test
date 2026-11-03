package com.tapingo.iremote.atv;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Length-delimited protobuf framing used by Android TV Remote v2. */
public final class ProtoFrameCodec {
    private static final int MAX_FRAME_BYTES = 1024 * 1024;

    private ProtoFrameCodec() {}

    public static void writeFrame(OutputStream out, byte[] message)
            throws IOException {
        if (out == null) {
            throw new IllegalArgumentException("Output stream is null");
        }
        if (message == null) {
            throw new IllegalArgumentException("Message is null");
        }
        if (message.length > MAX_FRAME_BYTES) {
            throw new IOException("Outgoing protobuf frame exceeds 1 MB");
        }

        writeVarint(out, message.length);
        out.write(message);
        out.flush();
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("Input stream is null");
        }

        int length = readVarint(in);

        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid protobuf frame length: " + length);
        }

        byte[] data = new byte[length];
        int offset = 0;

        while (offset < length) {
            int n = in.read(data, offset, length - offset);

            if (n < 0) {
                throw new EOFException(
                        "Socket closed inside protobuf frame"
                );
            }

            if (n == 0) {
                int single = in.read();

                if (single < 0) {
                    throw new EOFException(
                            "Socket closed inside protobuf frame"
                    );
                }

                data[offset++] = (byte) single;
            } else {
                offset += n;
            }
        }

        return data;
    }

    private static void writeVarint(OutputStream out, int value)
            throws IOException {
        long v = value & 0xffffffffL;

        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }

        out.write((int) v);
    }

    private static int readVarint(InputStream in) throws IOException {
        int result = 0;

        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();

            if (b < 0) {
                throw new EOFException(
                        "Socket closed before protobuf frame length"
                );
            }

            if (shift == 28 && (b & 0xf0) != 0) {
                throw new IOException("Protobuf frame length overflow");
            }

            result |= (b & 0x7f) << shift;

            if ((b & 0x80) == 0) {
                return result;
            }
        }

        throw new IOException("Malformed protobuf varint length");
    }
}