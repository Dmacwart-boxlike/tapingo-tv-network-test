package com.tapingo.iremote.atv;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Locale;

/** Stores the TV certificate fingerprint only after the pairing secret is acknowledged. */
public final class TvIdentityStore {
    private static final String PREFS = "tapingo_tv_identity_v1";
    private static final String KEY_FINGERPRINT = "paired_tv_sha256";
    private TvIdentityStore() {}

    public static String fingerprint(Certificate certificate) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder out=new StringBuilder(digest.length*2);
        for(byte b:digest) out.append(String.format(Locale.US,"%02X",b&0xff));
        return out.toString();
    }

    public static void saveAfterPairing(Context context, Certificate certificate) throws Exception {
        String value=fingerprint(certificate);
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY_FINGERPRINT,value).commit();
    }

    public static String getPinnedFingerprint(Context context) {
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_FINGERPRINT,null);
    }

    /** Returns true only when a previously paired TV presents the exact saved certificate. */
    public static boolean matchesPinned(Context context, Certificate certificate) throws Exception {
        String pinned=getPinnedFingerprint(context);
        return pinned!=null && pinned.equals(fingerprint(certificate));
    }

    public static void clearForExplicitRepair(Context context) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY_FINGERPRINT).commit();
    }
}
