```java
package com.tapingo.iremote.atv;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AtvPairingPanel extends LinearLayout {

    private static final int PAIRING_TIMEOUT_MS = 15000;

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private EditText hostInput;
    private EditText codeInput;
    private Button pairButton;
    private Button completeButton;
    private TextView statusText;

    private AtvPairingClient pairingClient;
    private AtvPairingClient.PairingSession pairingSession;

    private volatile boolean shutDown = false;
    private volatile boolean busy = false;
    private volatile boolean paired = false;

    public AtvPairingPanel(Context context) {
        super(context);

        setOrientation(VERTICAL);
        setPadding(24, 24, 24, 24);

        buildInterface();
    }

    private void buildInterface() {

        TextView heading = new TextView(getContext());
        heading.setText("Android TV Remote v2 Pairing");
        heading.setTextSize(20);
        heading.setTypeface(null, Typeface.BOLD);
        addView(heading);

        TextView hostLabel = new TextView(getContext());
        hostLabel.setText("TV IP Address");
        hostLabel.setPadding(0, 24, 0, 8);
        addView(hostLabel);

        hostInput = new EditText(getContext());
        hostInput.setSingleLine(true);
        hostInput.setHint("Example: 192.168.100.5");
        addView(hostInput);

        pairButton = new Button(getContext());
        pairButton.setText("PAIR WITH TV");
        addView(pairButton);

        TextView codeLabel = new TextView(getContext());
        codeLabel.setText(
                "Enter the 6-character pairing code shown on your TV"
        );
        codeLabel.setPadding(0, 20, 0, 8);
        addView(codeLabel);

        codeInput = new EditText(getContext());
        codeInput.setSingleLine(true);
        codeInput.setHint("ABC123");
        codeInput.setMaxLines(1);
        addView(codeInput);

        completeButton = new Button(getContext());
        completeButton.setText("COMPLETE PAIRING");
        completeButton.setEnabled(false);
        addView(completeButton);

        statusText = new TextView(getContext());
        statusText.setText("Status: Ready to pair");
        statusText.setTextSize(14);
        statusText.setPadding(0, 24, 0, 8);
        addView(statusText);

        pairButton.setOnClickListener(v -> beginPairing());

        completeButton.setOnClickListener(v -> completePairing());
    }

    private void beginPairing() {

        if (shutDown || busy || paired) {
            return;
        }

        String host = hostInput.getText().toString().trim();

        if (host.isEmpty()) {
            setStatus("Enter your TV's IP address first.");
            return;
        }

        busy = true;
        pairButton.setEnabled(false);
        completeButton.setEnabled(false);

        setStatus("Connecting to TV pairing service...");

        executor.execute(() -> {

            AtvPairingSessionResult result;

            try {
                ClientIdentityStore.Identity identity =
                        ClientIdentityStore.loadOrCreate(getContext());

                pairingClient = new AtvPairingClient(
                        getContext(),
                        identity,
                        PAIRING_TIMEOUT_MS
                );

                pairingSession = pairingClient.begin(host);

                result = new AtvPairingSessionResult(
                        true,
                        "TV pairing connection established. "
                                + "Check your TV for the pairing code."
                );

            } catch (Exception e) {

                result = new AtvPairingSessionResult(
                        false,
                        "Pairing connection failed: " + errorMessage(e)
                );
            }

            AtvPairingSessionResult finalResult = result;

            mainHandler.post(() -> {

                busy = false;

                if (shutDown) {
                    return;
                }

                pairButton.setEnabled(!finalResult.success);

                if (finalResult.success) {
                    completeButton.setEnabled(true);
                }

                setStatus(finalResult.message);
            });
        });
    }

    private void completePairing() {

        if (shutDown || busy || paired || pairingSession == null) {
            return;
        }

        String code = codeInput.getText().toString()
                .trim()
                .toUpperCase(java.util.Locale.US);

        if (!code.matches("[0-9A-F]{6}")) {
            setStatus(
                    "Enter the six-character hexadecimal pairing code."
            );
            return;
        }

        busy = true;
        pairButton.setEnabled(false);
        completeButton.setEnabled(false);

        setStatus(
                "Verifying pairing code and completing secure pairing..."
        );

        executor.execute(() -> {

            String resultMessage;
            boolean success = false;

            try {
                String fingerprint = pairingSession.finish(code);

                success = true;

                resultMessage =
                        "PAIRING COMPLETED SUCCESSFULLY.\n\n"
                        + "TV certificate fingerprint:\n"
                        + fingerprint;

            } catch (Exception e) {

                resultMessage =
                        "Pairing verification failed: " + errorMessage(e);

                closeSession();
            }

            boolean finalSuccess = success;
            String finalMessage = resultMessage;

            mainHandler.post(() -> {

                busy = false;

                if (shutDown) {
                    return;
                }

                if (finalSuccess) {
                    paired = true;
                    pairButton.setEnabled(false);
                    completeButton.setEnabled(false);
                } else {
                    pairButton.setEnabled(true);
                    completeButton.setEnabled(false);
                }

                setStatus(finalMessage);
            });
        });
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

    private void closeSession() {

        if (pairingSession != null) {
            try {
                pairingSession.close();
            } catch (Exception ignored) {
            }

            pairingSession = null;
        }
    }

    public void shutdown() {

        if (shutDown) {
            return;
        }

        shutDown = true;

        // Queue cleanup behind any active pairing operation.
        // This avoids racing session cleanup against finish().
        executor.execute(this::closeSession);

        // Allow the queued cleanup to execute.
        executor.shutdown();
    }

    private static class AtvPairingSessionResult {

        final boolean success;
        final String message;

        AtvPairingSessionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
```
