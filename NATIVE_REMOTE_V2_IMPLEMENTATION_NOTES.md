# Tapingo iRemote — Native Android TV Remote v2 implementation notes

## Status: research confirmed; implementation not yet integrated

This repository currently proves the iPhone-to-TV local WebSocket path and handles volume, mute, Settings, Home, and Back. It does **not** yet implement Android TV Remote v2 pairing or control.

## Confirmed from the supplied source

- HTTP test server: TCP 8080.
- WebSocket test server: TCP 8081, endpoint `/ws`.
- The WebSocket accepts plain-text command names such as `COMMAND:VOLUME_UP` and returns a text response.
- Current handlers: `VOLUME_UP`, `VOLUME_DOWN`, `MUTE_TOGGLE`, `OPEN_SETTINGS`, `HOME`, `BACK`.
- `HOME` and `BACK` use `RemoteAccessibilityService`; this service currently has no D-pad/key injection implementation.
- The current app is the baseline test app. Do not replace it until an experimental build is tested on the physical TV.

## Native Remote v2 route

Public implementations/documentation describe Android TV Remote v2 as two mutually authenticated TLS connections:

- TCP 6467: pairing handshake.
- TCP 6466: remote-control session after pairing.
- Protocol messages use Protocol Buffers with length-prefix framing.
- The client creates and persists a certificate/private key. Pairing requires a code displayed by the TV, and subsequent control sessions must reuse the same client identity.

The fact that these ports accept TCP connections is encouraging, but does not prove the TV accepts the pairing protocol. A normal HTTPS `curl` request is not a valid protocol test for this service.

## Integration requirements before calling it complete

1. Add a persistent per-install client certificate/key and a TLS socket factory that presents that identity.
2. Implement and test the pairing state machine on port 6467, including the on-screen code and explicit pairing success/failure.
3. Persist paired identity securely and reconnect on port 6466 using that same identity.
4. Implement protobuf framing and parse control-session handshake/ping messages; answer TV pings to keep the socket alive.
5. Map PWA commands to native key press/release messages (D-pad directions, OK/Enter, Back, Home, Play/Pause, volume, mute, and power where supported).
6. Return truthful acknowledgements: do not return success just because a command was received. Distinguish `received`, `sent to TV`, and `failed` states where possible.
7. Keep the existing HTTP/WebSocket bridge available, but build the experiment with a distinct application ID or on a separate branch. Only one copy can bind to ports 8080/8081 at a time.
8. Build via GitHub Actions, install on the physical Union/TCL Android TV, pair it, and test each command. Only after this passes should the PWA UI be connected to the new native transport.

## Reference implementations

- `dakopopelman/AndroidTVRemoteControl` (MIT): Kotlin implementation with certificate management, pairing, control channel, and key codes.
- `Gatuque/tv-remote`: Android TV Remote v2 implementation and troubleshooting notes.
- `kud/androidtv-remote`: TypeScript implementation useful for protocol cross-checking.

These references support the feasibility of the protocol, not a claim that this project has already implemented or tested it.
