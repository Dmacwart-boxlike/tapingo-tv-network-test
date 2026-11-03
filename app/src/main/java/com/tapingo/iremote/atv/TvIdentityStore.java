package com.tapingo.iremote.atv;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Locale;

/**
 * Stores the TV certificate fingerprint only after the pairing secret
 * has been acknowledged.
 *
 * An existing TV identity cannot be silently replaced.
 * Explicit repair must clear the previous identity first.
 */
public final class TvIdentityStore {

    private static final String PREFS = "tapingo_tv_identity_v1";
    private static final String KEY_FINGERPRINT = "paired_tv_sha256";

    private TvIdentityStore() {}

    /**
     * Calculates the SHA-256 fingerprint of the complete encoded certificate.
     */
    public static String fingerprint(Certificate certificate) throws Exception {

        if (certificate == null) {
            throw new IllegalArgumentException("TV certificate cannot be null.");
        }

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.getEncoded());

        StringBuilder out = new StringBuilder(digest.length * 2);

        for (byte b : digest) {
            out.append(String.format(Locale.US, "%02X", b & 0xff));
        }

        return out.toString();
    }

    /**
     * Saves the TV identity after successful pairing.
     *
     * Refuses to silently replace a previously paired TV certificate.
     * Throws an exception if persistence fails.
     */
    public static void saveAfterPairing(
            Context context,
            Certificate certificate
    ) throws Exception {

        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null.");
        }

        String value = fingerprint(certificate);

        SharedPreferences preferences = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );

        String existing = preferences.getString(KEY_FINGERPRINT, null);

        if (existing != null && !existing.equals(value)) {
            throw new IllegalStateException(
                    "A different TV identity is already saved. " +
                    "Clear the existing pairing explicitly before pairing another TV."
            );
        }

        boolean saved = preferences.edit()
                .putString(KEY_FINGERPRINT, value)
                .commit();

        if (!saved) {
            throw new IllegalStateException(
                    "Could not persist the paired TV certificate fingerprint."
            );
        }
    }

    /**
     * Returns the saved TV certificate fingerprint, or null if not paired.
     */
    public static String getPinnedFingerprint(Context context) {

        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null.");
        }

        return context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        ).getString(KEY_FINGERPRINT, null);
    }

    /**
     * Returns true only when a previously paired TV presents
     * the exact saved certificate.
     */
    public static boolean matchesPinned(
            Context context,
            Certificate certificate
    ) throws Exception {

        String pinned = getPinnedFingerprint(context);

        return pinned != null
                && pinned.equals(fingerprint(certificate));
    }

    /**
     * Clears the saved TV identity only during an explicit repair/re-pair.
     */
    public static void clearForExplicitRepair(Context context) {

        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null.");
        }

        boolean cleared = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        ).edit()
                .remove(KEY_FINGERPRINT)
                .commit();

        if (!cleared) {
            throw new IllegalStateException(
                    "Could not clear the saved TV identity."
            );
        }
    }
}
