TAPINGO iREMOTE — FIRST TV NETWORK TEST

IMPORTANT: This is only a network connectivity test APK, NOT the finished remote.
It does not implement power, volume, D-pad, Home/Back, pairing, or WebSocket controls.
It is intended to prove that an iPhone can reach a server running on the Android TV over the same Wi-Fi.

BUILDING THE APK (no Android tools to install on your PC):
1. Sign in to GitHub in your browser.
2. Create a NEW repository named tapingo-tv-network-test. Public is simplest for this test; do not upload passwords or private keys.
3. Upload the CONTENTS of this folder to the repository root (not the enclosing folder).
4. Open the repository's Actions tab and allow Actions if GitHub asks.
5. Select “Build Tapingo TV Network Test APK” and press “Run workflow” if it has not started automatically.
6. Wait for the green successful run. Open that run and download the artifact named “Tapingo-TV-Network-Test-APK”.
7. The downloaded artifact is a ZIP. Extract it to find app-debug.apk.

INSTALL/TEST (only after an APK has actually been built):
1. Copy app-debug.apk to a USB drive.
2. Connect USB to the TV and use the TV file manager to open the APK. Android may ask you to allow installation from that file manager.
3. Install and open “Tapingo Network Test”. Keep its screen open.
4. Ensure iPhone and TV are on the same Wi-Fi. On iPhone Safari, type the full address shown on the TV ending in /ping.
5. Success is a small JSON response containing "ok":true and "iPhone reached the TV".

If GitHub shows a red failed build, stop and share a screenshot of the failure. Do not try to install a missing APK.
