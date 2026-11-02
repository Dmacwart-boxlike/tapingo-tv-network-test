# Tapingo iRemote — Native Remote v2 status

## Stage 2 foundation added (not a complete pairing implementation)

This experiment now includes:

- `ProtoFrameCodec.java`: varint length-prefixed Protobuf frame reader/writer.
- `ClientIdentityStore.java`: generates an RSA-2048 self-signed X.509 client certificate on first use, stores it as PKCS#12 in app-private internal storage, and reuses the saved identity on later calls.
- `AtvTlsSocketFactory.java`: creates a TLS 1.2 socket presenting the saved client certificate, for use with ports 6467 (pairing) and 6466 (control).
- BouncyCastle dependencies required for self-signed X.509 certificate generation.

## Not yet implemented or verified

- Pairing protobuf message encoding/decoding and full pairing state machine on port 6467.
- Correct extraction and validation of the TV's pairing code, pairing-secret hash/check byte, and explicit pairing success.
- Saving/pinning the TV certificate after pairing.
- Port 6466 control-session handshake, ping/pong handling, key-injection messages, and command mapping.
- Integration with the existing WebSocket bridge.
- Full Android Gradle build and physical TV test of these new classes.

## Important security and compatibility notes

- The permissive server-certificate trust manager in `AtvTlsSocketFactory` is only a transport bootstrap for the self-signed service. It is not sufficient authentication on its own. Pairing must validate the TV's code-derived cryptographic secret and pin the TV certificate before the implementation can be called secure.
- The PKCS#12 password is currently an app-internal implementation constant, not hardware-backed secret storage. The file itself is in app-private internal storage. Before production, improve secret handling and validate compatibility with the actual TV.
- Do not replace or uninstall the baseline test APK based on this experiment. The existing working WebSocket bridge is preserved, and this stage has not been tested on the physical TV.
