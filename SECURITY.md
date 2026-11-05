# CipherShield security model

## What CipherShield protects

CipherShield protects the confidentiality and integrity of a text payload after encryption, assuming the password has high entropy and remains secret. An attacker who only obtains the CSv3 payload should be limited to offline password guessing.

The random salt prevents precomputed attacks across payloads. The random GCM IV makes repeated encryption of identical input produce different ciphertext. The authenticated envelope detects modification of headers and ciphertext.

## What it does not protect

- A compromised, rooted, debugged, or malicious device, keyboard, accessibility service, or clipboard manager
- System-level overlays and platform vulnerabilities; Android 12+ overlay blocking reduces but cannot eliminate this risk
- Weak or reused passwords, shoulder surfing, or sending password and payload through the same compromised channel
- Plaintext copied elsewhere, notifications or logs created by other apps, or screenshots taken before CipherShield receives the text
- Traffic analysis, message size, timing, or the fact that a payload uses the public CipherShield format
- Perfect RAM erasure: Android/JVM strings are immutable and garbage-collected, so clearing references and byte arrays is best-effort
- Guaranteed clipboard deletion: Android background restrictions, process termination and other apps can prevent or outlive the expiry attempt
- User-selected document providers: CipherShield has no network permission, but another app's cloud provider can upload files you choose to export
- Availability against an attacker who can continuously force the app or device to process data

## Operational guidance

Prefer the built-in random key generator or a unique passphrase of at least 16 characters. The on-device strength meter is deliberately conservative but remains only a heuristic. Transfer the password separately from the ciphertext. Avoid system clipboard use for the password when a safer channel is available. Keep Android patched and use a trusted keyboard.

Decrypted results clear after 30 seconds. Backgrounding clears UI inputs and results and invalidates outstanding cryptographic results, even if the app resumes before computation finishes. Changing inputs, clearing output, or switching tabs also invalidates old operations. This prevents a completed background task from restoring a cleared secret.

Explicit file export is an exception to background clearing: the selected output remains in memory until the document picker returns and writing finishes. Exporting decrypted text requires confirmation and leaves a persistent, unencrypted file outside CipherShield. Copy generated passwords before leaving the app to save ciphertext; the displayed key clears on backgrounding. No password is automatically included in an encrypted export.

## Format and compatibility

New encryption uses CSv3 with PBKDF2-HMAC-SHA512 at 600,000 iterations, AES-256-GCM, and HMAC-SHA512. The decoder accepts CSv2 and older CSv3 payloads only when their declared PBKDF2 work factor is between 100,000 and 2,000,000 iterations. This bound prevents attacker-controlled metadata from requesting effectively unlimited work.

The minimum supported Android version is API 26, matching the platform PBKDF2-HMAC-SHA512 provider. CSv2 does not authenticate metadata with the additional CSv3 HMAC; its AES-GCM authentication is retained for legacy decryption. New encryption always writes CSv3.
