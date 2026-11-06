---
name: exotel-voice-sdks
description: Add in-app and browser calling with Exotel's Voice SDKs (WebRTC) - choose between the Client WebSDK, Android, iOS, Flutter SDKs and the CRM WebSDK, set up users/subscribers and tokens, and connect app (IP) calls with phone (PSTN) calls. Use when the user wants a softphone, agent desktop calling, VoIP in a mobile or web app, click-to-call from a CRM, or IP-PSTN intermix.
---

# Exotel Voice SDKs (WebRTC, IP ↔ PSTN)

These SDKs run in the user's app or browser; there is no CLI command that places SDK calls. Use the docs pages
below (`exotel docs show <url>` reads them) and build the token flow in the developer's backend.

## Choose the SDK

| Your users call from… | SDK | Who manages SIP users |
|-----------------------|-----|------------------------|
| A CRM or web portal, Exotel-managed users | **CRM WebSDK** (softphone widget, click-to-call, IP↔PSTN toggle, CRM SSO) | Exotel creates SIP ID/password when you create users (Customer → Application → Users) |
| Your own browser softphone / agent desktop | **Client WebSDK** (register, answer, hangup, mute, hold, DTMF, diagnostics) | You, via the Subscriber Management API + your backend |
| A native Android app | **Client Android SDK** (outbound, incoming via push, mute/hold) | You: subscriber + `subscriber_token` from your backend |
| A native iOS app | **Client iOS SDK** (PushKit/APNs) | Same as Android |
| One Flutter codebase | **Client Flutter SDK** | Same as Android |

Effort: CRM WebSDK lowest; Client Web medium; mobile medium–high (foreground service / FCM on Android,
PushKit on iOS, microphone and notification permissions).

## How it fits together

1. Your backend holds the Exotel API key/token. It creates users/subscribers and issues short-lived tokens.
2. The app logs the user in to **your** backend, gets a token, and initialises the SDK with it.
3. The SDK registers the device; outbound calls go to PSTN numbers or other users; inbound calls arrive via
   push (mobile) or the open session (web).
4. Call events and recordings: configure webhooks in App Settings (IP-PSTN intermix integrations API,
   `https://integrationscore.mum1.exotel.com/v2/integrations`: authentication, applications, app settings,
   users, device management, call notifications, customer management).

Never ship the Exotel API key/token inside the app or the browser: only the backend uses them.

## Docs

- Which SDK: https://docs.exotel.com/voice-apis/which-voice-sdk-should-i-use
- IP-PSTN intermix / WebRTC SDK: https://developer.exotel.com/docs/ip-pstn-webrtc/overview
- Web client SDK APIs: https://docs.exotel.com/voice-apis/web-client-sdk-apis
- Android: https://docs.exotel.com/voice-apis/android-sdk-integration
- Samples: https://docs.exotel.com/voice-apis/sdk-samples

For anything not covered here: `exotel ask "<question>"` and `exotel docs search sdk <topic>`.
