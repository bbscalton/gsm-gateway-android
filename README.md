# Neuereatec GSM Gateway (Android)

The app answers calls to the phone's SIM. It then sends a SIP INVITE to a PBX extension (default **105**) and joins the audio of the two calls.

Download page: https://bbscalton.github.io/gsm-gateway-android/

## Features
- SIP over UDP: REGISTER with digest auth, INVITE, CANCEL and BYE. G.711 PCMU/PCMA audio and RFC 2833 events. The SIP and RTP code was written for this app: SIPDesk's Linphone stack controls the audio device itself and cannot take call-audio sources.
- Server setting: default `100.64.0.1:5060` over Headscale. `neuereatec.org` is a choice too, but is not reachable yet. LAN addresses also work. Setup links `gsmgw://setup?server=&port=&ext=&pw=&target=` (also `sipdesk://setup?...`), entered as a QR code or pasted.
- Auto-answer uses `TelecomManager.acceptRingingCall()` (ANSWER_PHONE_CALLS). Hang-up on both sides uses `endCall()` and BYE/CANCEL.
- Caller ID goes in the From display name, `P-Asserted-Identity` and `X-GSM-Caller`.
- Audio modes (chosen automatically, shown on the status screen):
  - `INTERCEPTION`: Android 13+ privileged app with `CALL_AUDIO_INTERCEPTION`. Uses `getCallDownlinkExtractionAudioRecord` and `getCallUplinkInjectionAudioTrack`, called through reflection.
  - `VOICE_CALL`: privileged app with `CAPTURE_AUDIO_OUTPUT`. Records from `AudioRecord(VOICE_DOWNLINK/VOICE_CALL)`; the PBX side goes out by speakerphone.
  - `ACOUSTIC`: speakerphone plus mic. Lower quality.
- Foreground service (phoneCall/microphone) that starts at boot.
- Release asset `gsm-gateway-magisk.zip` installs the app to `/system/priv-app` with an allow-list in `privapp-permissions`.

## Build
```
# JDK 17
gradlew assembleRelease
```
Release signing reads `signing.properties` (not committed): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
