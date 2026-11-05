# CipherShield

**Offline, password-based encryption for text on Android.**

English · [فارسی](README.fa.md)

CipherShield turns private text into an authenticated, password-protected payload that you can copy, save as a file, or display as a QR code. The recipient needs compatible software and the exact password to recover the original text. Encryption and decryption happen on the device; no account, server, API key, or cloud service is required by the app.

It is useful for confidential notes, messages, credentials and text VPN/proxy configurations. It is **not a VPN client, SMS service, password manager, file vault, or messaging service**. It does not connect to VPN servers, read the SMS inbox, or send messages for you.

## Features

- Encrypt and decrypt valid UTF-8 text, including Persian, emoji and multiline content.
- Type or paste text, import a text file, scan a camera QR code, or read a QR image.
- Automatically recognize structurally valid CipherShield envelopes and route them to decryption.
- Generate a random 24-character password with uppercase, lowercase, digits and symbols.
- Save encrypted text as a file; explicitly confirm before exporting unencrypted plaintext.
- Display a temporary decrypted result with a 30-second countdown and manual clearing.
- Clear UI inputs/results on backgrounding and invalidate stale asynchronous results.
- Use a native adaptive icon, monochrome launcher variant, and Material 3 Compose interface.
- Keep encryption compatible with CSv3 and retain bounded CSv2 legacy decryption.

## Supported content and limits

| Content | Support |
| --- | --- |
| Plain text, notes and messages | Yes, including Unicode and preserved whitespace |
| SMS text or text exports | Yes, when manually pasted or imported; no SMS permission |
| JSON, YAML, XML, INI and CSV | Yes, as text; no parsing or validation of the configuration |
| WireGuard, OpenVPN, VLESS, VMess, Shadowsocks and Trojan | Yes, when represented as UTF-8 text or a URI |
| PDF, Office documents, images and arbitrary binary files | No text extraction or general binary encryption; QR images are decoded separately |

Plaintext is limited to **5 MiB (5,242,880 UTF-8 bytes)**. This is a byte limit, so the number of characters depends on their encoding. Malformed Unicode/UTF-8 is rejected rather than silently replaced.

Encrypted-file import allows the larger Base64 envelope. QR capacity is much smaller than the text limit; Android's clipboard can also reject large content. Use file export for larger payloads. Output previews show only a limited portion for performance; copy and save operate on the complete output.

The app reads selected files through Android's document picker without broad storage access. Choose a local provider if the content must stay offline: an external cloud document provider may use its own network connection.

## How to use

### Encrypt

1. Open **Encrypt**, then type, paste, scan or import the text.
2. Enter a unique password of at least 16 characters rated Strong or Excellent, or use **Generate Key**.
3. Select **Encrypt text**.
4. Keep the password safe and transfer it through a separate trusted channel.
5. Copy the encrypted output, display its QR code if it fits, or select **Save encrypted file**.

**Save or copy a generated password before leaving the app to choose an export destination.** Backgrounding clears the displayed password. Passwords are never automatically included in encrypted files.

### Decrypt

1. Open **Decrypt** and paste, scan or import the complete CipherShield payload.
2. Enter the exact password, including spaces and letter case.
3. Select **Decrypt text**.
4. Read or copy the result before the 30-second countdown finishes, or explicitly confirm **Save decrypted text…**.

Exported plaintext is an ordinary, unencrypted file. It does not expire and is not removed by clearing the app. During an explicit export, the selected output is held in memory until the picker returns and writing completes. This is an exception to background clearing.

There is no password reset, recovery key or backdoor. Losing the password can make the encrypted content unrecoverable.

## Encryption design

These details describe the implementation in [CipherShieldEngine.kt](app/src/main/java/com/example/CipherShieldEngine.kt), not an independent security certification.

| Component | Current implementation |
| --- | --- |
| New payload format | CSv3 |
| Randomness | Java/Android `SecureRandom` |
| Salt | Fresh 16-byte / 128-bit random salt per encryption |
| Password derivation | PBKDF2-HMAC-SHA512, 600,000 iterations |
| Derived material | 64 bytes: first 32 bytes for AES, remaining 32 bytes for HMAC |
| Encryption | AES-256-GCM with a fresh 12-byte / 96-bit IV |
| GCM authentication tag | 16 bytes / 128 bits |
| Envelope authentication | HMAC-SHA512 over the header and ciphertext including the GCM tag |
| Text representation | Standard Base64, emitted without line breaks |

The salt and IV are public metadata, not secret keys. Fresh random values make repeated encryption of the same text with the same password produce different payloads. PBKDF2 increases the cost of password guessing; it cannot make a predictable password safe.

The additional HMAC authenticates the CSv3 header and encrypted body with a separate portion of the derived key. Its value is compared with `MessageDigest.isEqual` before GCM decryption. A successful check demonstrates integrity under the shared password; it is **not a digital signature or proof of the sender's identity**.

### CSv3 binary envelope

Before Base64 encoding, the layout is:

| Offset | Bytes | Meaning |
| --- | --- | --- |
| 0 | 4 | ASCII magic `CSv3` |
| 4 | 1 | Version `0x03` |
| 5 | 1 | KDF identifier `0x01` |
| 6 | 4 | PBKDF2 iteration count, big-endian |
| 10 | 16 | Salt |
| 26 | 12 | GCM IV |
| 38 | Variable | Encrypted UTF-8 bytes followed by the 16-byte GCM tag |
| End − 64 | 64 | HMAC-SHA512 of all preceding bytes |

Base64 is only a transport encoding; AES provides encryption. Structural format detection does not authenticate a payload. Decoding permits ASCII spaces, tabs and line breaks within the overall input limit, but rejects invalid padding and nonzero unused padding bits.

CSv2 is supported for legacy decryption using its 256-bit derived AES key and GCM tag, without the extra CSv3 HMAC. New encryption always writes CSv3. Both formats accept declared PBKDF2 work factors only from 100,000 through 2,000,000 iterations to bound hostile computation. App release numbers and payload format versions are separate concepts.

## Privacy, permissions and limitations

- The source manifest declares no `INTERNET` permission and explicitly removes inherited `ACCESS_NETWORK_STATE`.
- Camera access is optional and used for local QR scanning. Encryption works without it.
- Android 12+ overlay protection uses `HIDE_OVERLAY_WINDOWS`; system overlays and compromised devices remain outside this protection.
- `FLAG_SECURE` requests screen-capture protection. App backup and device-transfer rules exclude app data.
- Sensitive clipboard entries are marked sensitive. After 60 seconds, the app attempts to clear the matching entry without deleting a newer different clipboard value.
- Clipboard clearing is best-effort: background restrictions, process termination and copies made by other apps can defeat the expiry.
- Mutable buffers are cleared where practical. JVM strings, OS copies and third-party keyboards cannot be reliably erased by this app.
- The password-strength meter is a heuristic, not a measured cracking-time guarantee.
- A rooted/compromised device, unsafe keyboard, weak password, or exposed password-sharing channel can defeat the protection.
- Encryption does not hide payload length, transmission timing, or the fact that CipherShield is being used.
- The app has no built-in history or recovery service. Explicitly exported files remain wherever you saved them.

See [SECURITY.md](SECURITY.md) for the threat model and operational details. Do not describe the project as unbreakable or independently audited without a separate, published assessment.

## Project and build requirements

| Setting | Repository value |
| --- | --- |
| App ID | `com.sonix21.ciphershield` |
| App version | `1.0` (version code `1`), as currently configured |
| Minimum Android | Android 8.0 / API 26 |
| Compile SDK | Android API 36, minor API level 1 |
| Target SDK | 36 |
| Gradle wrapper | 9.7.1 |
| Android Gradle Plugin | 9.3.2 |
| Kotlin Compose plugin | 2.4.10 |
| Gradle daemon JVM | Java 21 via the checked-in toolchain criteria |

Version values come from [app/build.gradle.kts](app/build.gradle.kts); dependency versions are in [libs.versions.toml](gradle/libs.versions.toml). Android API 26 is required for the platform PBKDF2-HMAC-SHA512 provider. Java 11 source/target compatibility in the app module is distinct from the Java 21 Gradle daemon requirement.

Use Android Studio compatible with the pinned toolchain, install the required Android SDK, and open the repository root. Android Studio can generate the machine-specific `local.properties`; do not commit it. For command-line use, configure your SDK location and a suitable JDK.

The **installed app** is offline. Initial development setup may download Gradle, a JDK, Android SDK components and dependencies. Gradle `--offline` works only when everything needed is already cached.

### Checks (no APK packaging)

Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

macOS / Linux:

```sh
sh ./gradlew testDebugUnitTest lintDebug
```

Tests cover text round-trips, fresh randomness, incorrect passwords, tampering, hostile work factors, parser and size limits, password generation, stale-operation invalidation, file import, gallery QR import, and an independently generated CSv2 fixture.

The repository preparation step does not run builds or certify the current tests as passing. Run them locally before publishing a release.

### Build when you are ready

```powershell
# Windows
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

```sh
# macOS / Linux
sh ./gradlew assembleDebug
sh ./gradlew assembleRelease
```

Release builds enable R8 optimization/obfuscation and resource shrinking. Generated artifacts go under `app/build/outputs/` and are excluded from version control.

### Release signing

Provide all four environment variables through a trusted local environment or CI secret store:

| Variable | Value |
| --- | --- |
| `KEYSTORE_PATH` | Prefer an absolute path to your private signing keystore outside the repository |
| `STORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Signing key password |

With no signing variables, release output is intentionally unsigned. With all four, Gradle configures release signing. Partial configuration fails explicitly. Do not put real passwords in README examples, shell history, committed properties or workflow files. Keep your signing key backed up securely and use the same signing identity for app updates. Check the version code before publishing an update.

## Repository layout

```text
app/src/main/                 Android app, encryption engine, UI and resources
app/src/test/                 Unit and Robolectric regression tests
app/build.gradle.kts          App version, Android targets and signing configuration
gradle/libs.versions.toml     Dependency versions
gradle/wrapper/               Gradle wrapper bootstrap (keep the JAR)
gradle/gradle-daemon-jvm.properties  Shared Java toolchain criteria
README.md                    English documentation
README.fa.md                 Persian documentation
SECURITY.md                  Threat model and security guidance
```

## Publishing and contributing

This source directory excludes build outputs, old APKs, local SDK paths and IDE caches. `.gitignore` also excludes common signing-key and environment-secret files; it does not replace a manual review.

Before uploading, review the files you will publish, keep real VPN configs and credentials out of examples, and run the checks above. Put downloadable binaries in GitHub Releases if you choose to distribute them. No repository URL, release badge or test-success badge is assumed here.

For a reproducible bug report, include Android version, app version, expected/actual behavior and a minimal synthetic example. Never post real passwords, private keys, plaintext or sensitive encrypted payloads. Discuss suspected vulnerabilities privately with the maintainer, or use GitHub's private vulnerability reporting if enabled.

No project `LICENSE` file is currently supplied. The repository owner should choose the intended license before presenting the project as open-source. Third-party components retain their respective licenses.

Developed by **SONIX**.
