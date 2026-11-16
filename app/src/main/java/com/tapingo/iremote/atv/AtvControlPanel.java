package com.tapingo.iremote.atv;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AtvControlPanel extends LinearLayout {

    private static final int TRANSFER_PORT = 8082;

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private EditText hostInput;
    private EditText passwordInput;
    private TextView statusText;
    private TextView transferInfoText;

    private Button startTransferButton;
    private Button importButton;
    private Button connectButton;
    private Button disconnectButton;

    private ClientIdentityStore.Identity identity;
    private RemoteV2Client remoteClient;
    private IdentityTransferServer transferServer;
    private File receivedIdentityFile;

    private boolean connected = false;
    private boolean shutDown = false;

    public AtvControlPanel(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(24, 32, 24, 32);
        buildInterface();
    }

    private void buildInterface() {
        TextView heading = new TextView(getContext());
        heading.setText("Tapingo iRemote v2 Control Test");
        heading.setTextSize(22);
        heading.setTypeface(null, Typeface.BOLD);
        addView(heading);

        TextView instructions = new TextView(getContext());
        instructions.setText(
                "Transfer the existing paired identity from your Windows laptop, "
                        + "import it on this TV, then connect."
        );
        instructions.setTextSize(14);
        instructions.setPadding(0, 12, 0, 20);
        addView(instructions);

        addLabel("TV IP Address");
        hostInput = new EditText(getContext());
        hostInput.setSingleLine(true);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setText("192.168.100.5");
        hostInput.setHint("Example: 192.168.100.5");
        addView(hostInput);

        startTransferButton = makeButton("1. START LAPTOP IDENTITY TRANSFER");
        startTransferButton.setOnClickListener(v -> startIdentityTransfer());
        addView(startTransferButton);

        transferInfoText = new TextView(getContext());
        transferInfoText.setText(
                "When transfer starts, open the displayed address on your Windows laptop."
        );
        transferInfoText.setTextSize(14);
        transferInfoText.setPadding(0, 10, 0, 10);
        addView(transferInfoText);

        addLabel("PKCS12 identity password (enter on TV only)");
        passwordInput = new EditText(getContext());
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        passwordInput.setHint("Enter the password used when creating the new .p12 file");
        addView(passwordInput);

        importButton = makeButton("2. IMPORT RECEIVED IDENTITY");
        importButton.setEnabled(false);
        importButton.setOnClickListener(v -> importReceivedIdentity());
        addView(importButton);

        connectButton = makeButton("3. CONNECT TO TV");
        connectButton.setEnabled(false);
        connectButton.setOnClickListener(v -> connectToTv());
        addView(connectButton);

        disconnectButton = makeButton("DISCONNECT");
        disconnectButton.setEnabled(false);
        disconnectButton.setOnClickListener(v -> disconnect());
        addView(disconnectButton);

        addLabel("Remote controls");
        addControlRow("UP", "LEFT", "OK", "RIGHT", "DOWN");
        addControlRow("HOME", "BACK");
        addControlRow("VOL +", "VOL -", "MUTE");
        addControlRow("PLAY / PAUSE", "POWER");

        statusText = new TextView(getContext());
        statusText.setText("Status: Waiting to transfer identity");
        statusText.setTextSize(14);
        statusText.setPadding(0, 24, 0, 12);
        addView(statusText);

        setControlButtonsEnabled(false);
    }

    private void addLabel(String text) {
        TextView label = new TextView(getContext());
        label.setText(text);
        label.setTextSize(15);
        label.setTypeface(null, Typeface.BOLD);
        label.setPadding(0, 20, 0, 8);
        addView(label);
    }

    private Button makeButton(String text) {
        Button button = new Button(getContext());
        button.setText(text);
        LayoutParams params = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 6, 0, 6);
        button.setLayoutParams(params);
        return button;
    }

    private void addControlRow(String... labels) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        for (String label : labels) {
            Button button = new Button(getContext());
            button.setText(label);
            button.setTextSize(11);
            LayoutParams params = new LayoutParams(
                    0,
                    LayoutParams.WRAP_CONTENT,
                    1
            );
            params.setMargins(3, 4, 3, 4);
            row.addView(button, params);
            button.setOnClickListener(v -> sendControl(label));
            button.setEnabled(false);
        }
        addView(row);
    }

    private void startIdentityTransfer() {
        if (shutDown) return;

        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            setStatus("Enter the TV's current IP address first.");
            return;
        }

        stopIdentityTransferServer();
        String token = createOneTimeToken();

        transferServer = new IdentityTransferServer(
                getContext(),
                TRANSFER_PORT,
                token,
                new IdentityTransferServer.Listener() {
                    @Override
                    public void onIdentityUploaded(File file) {
                        mainHandler.post(() -> {
                            receivedIdentityFile = file;
                            importButton.setEnabled(true);
                            startTransferButton.setEnabled(true);
                            transferInfoText.setText(
                                    "Identity received successfully. Enter its password below "
                                            + "and tap IMPORT RECEIVED IDENTITY."
                            );
                            setStatus("Identity file received. It has not been imported yet.");
                            stopIdentityTransferServer();
                        });
                    }

                    @Override
                    public void onTransferError(String message) {
                        mainHandler.post(() -> setStatus("Transfer error: " + message));
                    }
                }
        );

        try {
            transferServer.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            String address = "http://" + host + ":" + TRANSFER_PORT + "/?token=" + token;
            transferInfoText.setText(
                    "ON YOUR WINDOWS LAPTOP, open this address in Chrome:\n\n"
                            + address
                            + "\n\nSelect tapingo_identity_new.p12 and send it. "
                            + "The password is NOT entered on the laptop."
            );
            setStatus("Temporary transfer server running on port " + TRANSFER_PORT + ".");
        } catch (Exception e) {
            stopIdentityTransferServer();
            setStatus("Could not start transfer server: " + errorMessage(e));
        }
    }

    private String createOneTimeToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private void stopIdentityTransferServer() {
        if (transferServer != null) {
            try {
                transferServer.stop();
            } catch (Exception ignored) {
            }
            transferServer = null;
        }
    }

    private void importReceivedIdentity() {
        if (shutDown) return;
        if (receivedIdentityFile == null || !receivedIdentityFile.isFile()) {
            setStatus("No identity file has been received yet. Start the laptop transfer first.");
            return;
        }

        String password = passwordInput.getText().toString();
        if (password.isEmpty()) {
            setStatus("Enter the .p12 password on the TV first.");
            return;
        }

        importButton.setEnabled(false);
        connectButton.setEnabled(false);
        setStatus("Validating identity and importing it into TV storage...");

        executor.execute(() -> {
            try {
                ClientIdentityStore.Identity imported = TestIdentityLoader.importIdentity(
                        getContext(),
                        receivedIdentityFile,
                        password.toCharArray()
                );

                identity = imported;
                File importedFile = receivedIdentityFile;
                receivedIdentityFile = null;
                if (importedFile.exists() && !importedFile.delete()) {
                    // The private app copy has already been created; report cleanup separately.
                }

                mainHandler.post(() -> {
                    passwordInput.setText("");
                    importButton.setEnabled(false);
                    connectButton.setEnabled(true);
                    transferInfoText.setText("Identity is now stored securely inside this app.");
                    setStatus("Identity imported successfully. You can now connect to the TV.");
                });
            } catch (Exception e) {
                String message = errorMessage(e);
                mainHandler.post(() -> {
                    importButton.setEnabled(receivedIdentityFile != null
                            && receivedIdentityFile.isFile());
                    setStatus("Identity import failed: " + message);
                });
            }
        });
    }

    /** Retained because MainActivity still forwards the old file-picker callback. */
    public void handleFileResult(int requestCode, int resultCode, android.content.Intent data) {
        // Android TV has no usable document picker on this device; transfer is via laptop browser.
    }

    private void connectToTv() {
        if (shutDown || identity == null || connected) return;

        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            setStatus("Enter your TV IP address.");
            return;
        }

        connectButton.setEnabled(false);
        setStatus("Connecting to TV on port 6466...");

        remoteClient = new RemoteV2Client(
                host,
                identity,
                new RemoteV2Client.Listener() {
                    @Override
                    public void onStatus(String message) {
                        mainHandler.post(() -> setStatus(message));
                    }

                    @Override
                    public void onConnected() {
                        mainHandler.post(() -> {
                            connected = true;
                            connectButton.setEnabled(false);
                            disconnectButton.setEnabled(true);
                            setControlButtonsEnabled(true);
                            setStatus("CONNECTED — Remote v2 session active.");
                        });
                    }

                    @Override
                    public void onDisconnected(String reason) {
                        mainHandler.post(() -> {
                            connected = false;
                            connectButton.setEnabled(identity != null);
                            disconnectButton.setEnabled(false);
                            setControlButtonsEnabled(false);
                            setStatus("Disconnected from TV: " + reason);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        mainHandler.post(() -> {
                            connected = false;
                            connectButton.setEnabled(identity != null);
                            disconnectButton.setEnabled(false);
                            setControlButtonsEnabled(false);
                            setStatus("Connection error: " + message);
                        });
                    }
                }
        );
        remoteClient.connect();
    }

    private void sendControl(String label) {
        if (!connected || remoteClient == null) return;

        switch (label) {
            case "UP": remoteClient.pressUp(); break;
            case "DOWN": remoteClient.pressDown(); break;
            case "LEFT": remoteClient.pressLeft(); break;
            case "RIGHT": remoteClient.pressRight(); break;
            case "OK": remoteClient.pressCenter(); break;
            case "HOME": remoteClient.pressHome(); break;
            case "BACK": remoteClient.pressBack(); break;
            case "VOL +": remoteClient.volumeUp(); break;
            case "VOL -": remoteClient.volumeDown(); break;
            case "MUTE": remoteClient.mute(); break;
            case "PLAY / PAUSE": remoteClient.playPause(); break;
            case "POWER": remoteClient.power(); break;
        }
    }

    private void disconnect() {
        if (remoteClient != null) {
            remoteClient.disconnect();
            remoteClient = null;
        }
        connected = false;
        connectButton.setEnabled(identity != null);
        disconnectButton.setEnabled(false);
        setControlButtonsEnabled(false);
        setStatus("Disconnect requested.");
    }

    private void setControlButtonsEnabled(boolean enabled) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) child;
            for (int j = 0; j < row.getChildCount(); j++) {
                View item = row.getChildAt(j);
                if (item instanceof Button) item.setEnabled(enabled);
            }
        }
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText("Status: " + message);
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();
        return (message == null || message.trim().isEmpty())
                ? e.getClass().getSimpleName()
                : message;
    }

    public void shutdown() {
        if (shutDown) return;
        shutDown = true;
        stopIdentityTransferServer();
        if (remoteClient != null) {
            remoteClient.disconnect();
            remoteClient = null;
        }
        executor.shutdown();
    }
}
