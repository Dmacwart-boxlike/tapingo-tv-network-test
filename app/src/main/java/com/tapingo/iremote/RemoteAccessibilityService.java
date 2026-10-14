package com.tapingo.iremote;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public class RemoteAccessibilityService extends AccessibilityService {

    private static RemoteAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No screen content needs to be read.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) {
            instance = null;
        }
        return super.onUnbind(intent);
    }

    public static String performRemoteAction(String action) {
        if (instance == null) {
            return "COMMAND_ERROR: ACCESSIBILITY_SERVICE_NOT_ENABLED";
        }

        if ("HOME".equals(action)) {
            boolean success = instance.performGlobalAction(
                GLOBAL_ACTION_HOME
            );

            return success
                ? "COMMAND_ACCEPTED: HOME"
                : "COMMAND_ERROR: HOME_FAILED";
        }

        if ("BACK".equals(action)) {
            boolean success = instance.performGlobalAction(
                GLOBAL_ACTION_BACK
            );

            return success
                ? "COMMAND_ACCEPTED: BACK"
                : "COMMAND_ERROR: BACK_FAILED";
        }

        return "COMMAND_ERROR: UNKNOWN_ACTION";
    }
}
