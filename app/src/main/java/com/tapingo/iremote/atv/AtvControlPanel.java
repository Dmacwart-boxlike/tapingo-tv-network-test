package com.tapingo.iremote.atv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AtvControlPanel extends LinearLayout {

    public static final int FILE_PICKER_REQUEST = 7466;

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private EditText hostInput;
    private EditText passwordInput;
    private TextView statusText;

    private Button importButton;
    private Button connectButton;
    private Button disconnectButton;

    private ClientIdentityStore.Identity identity;
    private RemoteV2Client remoteClient;

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
                "Import your temporary TV identity, then connect "
                + "to your Android TV."
        );
        instructions.setTextSize(14);
        instructions.setPadding(0, 12, 0, 20);
        addView(instructions);

        addLabel("TV IP Address");

        hostInput = new EditText(getContext());
        hostInput.setSingleLine(true);
        hostInput.setText("192.168.100.5");
        hostInput.setHint("Example: 192.168.100.5");
        addView(hostInput);

        addLabel("Temporary identity password");

        passwordInput = new EditText(getContext());
        passwordInput.setSingleLine(true);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        passwordInput.setHint("Enter your PKCS12 password");
        addView(passwordInput);

        importButton = makeButton("1. SELECT AND IMPORT IDENTITY");
        importButton.setOnClickListener(v -> selectIdentityFile());

        connectButton = makeButton("2. CONNECT TO TV");
        connectButton.setEnabled(false);
        connectButton.setOnClickListener(v -> connectToTv());

        disconnectButton = makeButton("DISCONNECT");
        disconnectButton.setEnabled(false);
        disconnectButton.setOnClickListener(v -> disconnect());

        addLabel("Remote controls");

        addControlRow(
                "UP", "LEFT", "OK", "RIGHT", "DOWN"
        );

        addControlRow(
                "HOME", "BACK"
        );

        addControlRow(
                "VOL +", "VOL -", "MUTE"
        );

        addControlRow(
                "PLAY / PAUSE", "POWER"
        );

        statusText = new TextView(getContext());
        statusText.setText("Status: Waiting for identity import");
        statusText.setTextSize(14);
        statusText.setPadding(0, 24, 0, 12);
        addView(statusText);

        addView(importButton);
        addView(connectButton);
        addView(disconnectButton);

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

    private void selectIdentityFile() {

        if (shutDown) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {

            ((Activity) getContext()).startActivityForResult(
                    intent,
                    FILE_PICKER_REQUEST
            );

            setStatus("Select tapingo_test_identity.p12");

        } catch (Exception e) {

            setStatus("Could not open file picker: " + errorMessage(e));
        }
    }

    public void handleFileResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        if (requestCode != FILE_PICKER_REQUEST) {
            return;
        }

        if (resultCode != Activity.RESULT_OK
                || data == null
                || data.getData() == null) {

            setStatus("Identity file selection cancelled.");
            return;
        }

        Uri selectedFile = data.getData();

        String password =
                passwordInput.getText().toString();

        if (password.isEmpty()) {
            setStatus("Enter your identity password first.");
            return;
        }

        importButton.setEnabled(false);
        connectButton.setEnabled(false);

        setStatus("Importing identity securely...");

        executor.execute(() -> {

            try {

                ClientIdentityStore.Identity imported =
                        TestIdentityLoader.importIdentity(
                                getContext(),
                                selectedFile,
                                password.toCharArray()
                        );

                identity = imported;

                mainHandler.post(() -> {

                    passwordInput.setText("");

                    importButton.setEnabled(false);
                    connectButton.setEnabled(true);

                    setStatus(
                            "Identity imported successfully. "
                            + "You can now connect."
                    );
                });

            } catch (Exception e) {

                String message = errorMessage(e);

                mainHandler.post(() -> {

                    importButton.setEnabled(true);

                    setStatus("Identity import failed: " + message);
                });
            }
        });
    }

    private void connectToTv() {

        if (shutDown || identity == null || connected) {
            return;
        }

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

                            setStatus(
                                    "CONNECTED — Remote v2 session active."
                            );
                        });
                    }

                    @Override
                    public void onDisconnected() {

                        mainHandler.post(() -> {

                            connected = false;

                            connectButton.setEnabled(identity != null);
                            disconnectButton.setEnabled(false);

                            setControlButtonsEnabled(false);

                            setStatus("Disconnected from TV.");
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

        if (!connected || remoteClient == null) {
            return;
        }

        switch (label) {

            case "UP":
                remoteClient.up();
                break;

            case "DOWN":
                remoteClient.down();
                break;

            case "LEFT":
                remoteClient.left();
                break;

            case "RIGHT":
                remoteClient.right();
                break;

            case "OK":
                remoteClient.center();
                break;

            case "HOME":
                remoteClient.home();
                break;

            case "BACK":
                remoteClient.back();
                break;

            case "VOL +":
                remoteClient.volumeUp();
                break;

            case "VOL -":
                remoteClient.volumeDown();
                break;

            case "MUTE":
                remoteClient.mute();
                break;

            case "PLAY / PAUSE":
                remoteClient.playPause();
                break;

            case "POWER":
                remoteClient.power();
                break;
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

            if (!(child instanceof LinearLayout)) {
                continue;
            }

            LinearLayout row = (LinearLayout) child;

            for (int j = 0; j < row.getChildCount(); j++) {

                View item = row.getChildAt(j);

                if (item instanceof Button) {
                    item.setEnabled(enabled);
                }
            }
        }
    }

    private void setStatus(String message) {

        if (statusText != null) {
            statusText.setText("Status: " + message);
        }
    }

    private String errorMessage(Exception e) {

        String message = e.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }

        return message;
    }

    public void shutdown() {

        if (shutDown) {
            return;
        }

        shutDown = true;

        if (remoteClient != null) {
            remoteClient.disconnect();
            remoteClient = null;
        }

        executor.shutdown();
    }
}