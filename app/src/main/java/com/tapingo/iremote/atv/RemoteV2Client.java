package com.tapingo.iremote.atv;

import android.view.KeyEvent;

import javax.net.ssl.SSLSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Temporary Android TV Remote v2 control client.
 *
 * Test implementation:
 * - Connects to port 6466.
 * - Uses the imported client identity.
 * - Sends configure and set-active messages.
 * - Handles TV ping requests.
 * - Sends remote key commands.
 *
 * This is a development test client, not production-hardened code.
 */
public final class RemoteV2Client {

    private static final String TAG = "TapingoRemoteV2";

    private static final int REMOTE_PORT = 6466;
    private static final int CONNECT_TIMEOUT = 10000;

    private static final int TYPE_CONFIGURE = 1;
    private static final int TYPE_PING_REQUEST = 8;
    private static final int TYPE_REMOTE_START = 40;

    private final String host;
    private final ClientIdentityStore.Identity identity;
    private final Listener listener;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private SSLSocket socket;
    private InputStream input;
    private OutputStream output;

    private Thread connectionThread;

    public interface Listener {

        void onStatus(String message);

        void onConnected();

        void onDisconnected(String reason);

        void onError(String error);
    }

    public RemoteV2Client(
            String host,
            ClientIdentityStore.Identity identity,
            Listener listener
    ) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("TV IP address is required.");
        }

        if (identity == null) {
            throw new IllegalArgumentException("Client identity is required.");
        }

        if (listener == null) {
            throw new IllegalArgumentException("Listener is required.");
        }

        this.host = host.trim();
        this.identity = identity;
        this.listener = listener;
    }

    /**
     * Starts the connection process in a background thread.
     */
    public synchronized void connect() {

        if (running.get()) {
            notifyStatus("Connection already running.");
            return;
        }

        running.set(true);

        connectionThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        connectionLoop();
                    }
                },
                "Tapingo-RemoteV2"
        );

        connectionThread.start();
    }

    private void connectionLoop() {

        try {
            notifyStatus("Connecting to TV on port 6466...");

            /*
             * TEMPORARY TEST MODE:
             * Uses the existing TLS factory's pairing connection method.
             * This permits the TV's self-signed certificate.
             *
             * Do not use this trust mode in the production app.
             */
            socket = AtvTlsSocketFactory.connectForPairing(
                    host,
                    REMOTE_PORT,
                    identity,
                    CONNECT_TIMEOUT
            );

            socket.setSoTimeout(0);

            input = socket.getInputStream();
            output = socket.getOutputStream();

            connected.set(true);

            notifyStatus("TLS connection established.");

            /*
             * Tell the TV which Remote v2 features this client supports.
             */
            send(RemoteMessageCodec.configure());

            notifyStatus("Configuration sent.");

            /*
             * Activate the supported remote-control features.
             */
            send(RemoteMessageCodec.setActive());

            notifyStatus("Remote activation requested.");

            /*
             * Keep reading messages until the TV closes the connection.
             */
            readMessages();

        } catch (Exception e) {

            if (running.get()) {
                notifyError(
                        e.getClass().getSimpleName() +
                        ": " + safeMessage(e)
                );
            }

        } finally {

            boolean wasConnected = connected.getAndSet(false);

            running.set(false);

            closeSocket();

            if (wasConnected) {
                notifyDisconnected("Remote v2 connection closed.");
            }
        }
    }

    /**
     * Reads incoming length-delimited protobuf messages.
     */
    private void readMessages() throws Exception {

        notifyStatus("Waiting for TV Remote v2 messages...");

        while (running.get() && !socket.isClosed()) {

            byte[] frame = ProtoFrameCodec.readFrame(input);

            RemoteMessageCodec.Message message =
                    RemoteMessageCodec.parse(frame);

            if (message == null) {
                continue;
            }

            handleMessage(message);
        }
    }

    /**
     * Handles incoming TV messages.
     */
    private void handleMessage(RemoteMessageCodec.Message message)
            throws Exception {

        int type = message.type;

        switch (type) {

            case TYPE_CONFIGURE:

                RemoteMessageCodec.ConfigureInfo info =
                        RemoteMessageCodec.parseConfigure(message.payload);

                if (info != null) {
                    notifyStatus(
                            "TV configuration received. Features: " +
                            info.features
                    );
                } else {
                    notifyStatus("TV configuration received.");
                }

                break;

            case TYPE_PING_REQUEST:

                int pingValue =
                        RemoteMessageCodec.parsePingRequest(message.payload);

                send(RemoteMessageCodec.pingResponse(pingValue));

                break;

            case TYPE_REMOTE_START:

                if (RemoteMessageCodec.parseStarted(message.payload)) {

                    notifyStatus("TV Remote v2 session started.");

                    if (connected.get()) {
                        notifyConnected();
                    }
                }

                break;

            default:

                notifyStatus(
                        "TV message received. Type: " + type
                );

                break;
        }
    }

    /**
     * Sends a protobuf message using the existing frame codec.
     */
    private synchronized void send(byte[] message) throws Exception {

        if (!connected.get() && output == null) {
            throw new IllegalStateException(
                    "Remote v2 connection is not ready."
            );
        }

        if (output == null) {
            throw new IllegalStateException(
                    "Remote v2 output stream is unavailable."
            );
        }

        ProtoFrameCodec.writeFrame(output, message);
    }

    /**
     * Sends an Android key code to the TV.
     */
    public void sendKey(int keyCode) {

        if (!connected.get()) {
            notifyStatus("Cannot send key: TV is not connected.");
            return;
        }

        try {

            send(RemoteMessageCodec.keyInject(keyCode));

            notifyStatus("Key sent: " + keyCode);

        } catch (Exception e) {

            notifyError("Key send failed: " + safeMessage(e));
        }
    }

    /**
     * Convenience remote-control methods.
     */
    public void pressUp() {
        sendKey(KeyEvent.KEYCODE_DPAD_UP);
    }

    public void pressDown() {
        sendKey(KeyEvent.KEYCODE_DPAD_DOWN);
    }

    public void pressLeft() {
        sendKey(KeyEvent.KEYCODE_DPAD_LEFT);
    }

    public void pressRight() {
        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    public void pressCenter() {
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    public void pressBack() {
        sendKey(KeyEvent.KEYCODE_BACK);
    }

    public void pressHome() {
        sendKey(KeyEvent.KEYCODE_HOME);
    }

    public void volumeUp() {
        sendKey(KeyEvent.KEYCODE_VOLUME_UP);
    }

    public void volumeDown() {
        sendKey(KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    public void mute() {
        sendKey(KeyEvent.KEYCODE_VOLUME_MUTE);
    }

    public void playPause() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public void power() {
        sendKey(KeyEvent.KEYCODE_POWER);
    }

    /**
     * Disconnects the Remote v2 session.
     */
    public synchronized void disconnect() {

        running.set(false);

        connected.set(false);

        closeSocket();

        notifyStatus("Disconnect requested.");
    }

    private synchronized void closeSocket() {

        try {
            if (input != null) {
                input.close();
            }
        } catch (Exception ignored) {}

        try {
            if (output != null) {
                output.close();
            }
        } catch (Exception ignored) {}

        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {}

        input = null;
        output = null;
        socket = null;
    }

    public boolean isConnected() {
        return connected.get();
    }

    private String safeMessage(Exception e) {

        String message = e.getMessage();

        return message == null
                ? "No additional error information."
                : message;
    }

    private void notifyStatus(final String message) {

        android.util.Log.i(TAG, message);

        listener.onStatus(message);
    }

    private void notifyConnected() {

        listener.onConnected();
    }

    private void notifyDisconnected(final String reason) {

        listener.onDisconnected(reason);
    }

    private void notifyError(final String error) {

        android.util.Log.e(TAG, error);

        listener.onError(error);
    }
}