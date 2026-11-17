# Payments Training Hub — Android

Native Android app (Kotlin + Jetpack Compose) for the Payments
Training Hub learning platform: 100 chapters, 746 lessons, 564
Payment Situations, 52 weekly cases, simulators, labs, the
Investigation Experience, and Google Play Billing for the 12
course products. The iOS app is the design reference; screens,
colors and behaviour mirror it.

Application ID: `com.paymentstraininghub.app`. Min SDK 26, target SDK
36, compile SDK 36. Version 1.0.0 (versionCode 1).

## Build

Prerequisites: JDK 17, Android SDK with platform 36 and
build-tools 34.0.0 (the AGP 8.2.2 default). Open the project in Android Studio (Ladybug
or newer) and let it sync, or build from the command line:

```sh
./gradlew assembleDebug
./gradlew assembleRelease   # unsigned; see Signing below
./gradlew bundleRelease     # unsigned AAB
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The Gradle wrapper (8.5) downloads itself on first run. No
proxy configuration is needed on a normal machine.

### Building on the sandbox VM

The VM blocks JVM TCP connections (sandbox policy), so the
Gradle daemon cannot download dependencies. The VM build uses a
local Maven repository populated with curl plus `--offline`:

```sh
python3 ../populate-maven-repo.py   # once; refresh when versions change
./../run-gradle-offline.sh ./gradlew assembleDebug
```

`gradle.properties` sets `-Djava.net.preferIPv4Stack=true`:
the daemon binds its loopback listener to 127.0.0.1, and on a
dual-stack JVM that becomes an IPv4-mapped IPv6 socket whose
`accept()` never fires in sandboxed networks (verified by
strace). The flag is harmless on normal machines.

## Signing (pending)

No signing credentials exist yet. `assembleRelease` produces an
unsigned APK and `bundleRelease` an unsigned AAB. Before a Play
release: create an upload keystore, add a `signingConfigs`
release block (keystore path and passwords from environment
variables or `local.properties`, never committed), and enroll in
Play App Signing.

## Backend configuration

All backend access uses the publishable Supabase key shipped in
`SupabaseClient` plus the signed-in user's token. No
service-role key or secret API key may ever be added to the app.

Before the first production build, the following must exist
(see `server/README.md` for the function and migration):

1. The `verify-play-purchase` Edge Function deployed, with the
   Play service-account secrets set (`PLAY_SERVICE_ACCOUNT_EMAIL`,
   `PLAY_SERVICE_ACCOUNT_PRIVATE_KEY`). Until then, Play
   purchases record on-device only and server sync stays queued.
2. The `verify-integrity` Edge Function deployed (same two
   secrets, JWT verification OFF). Until then, the premium gate
   runs in degraded mode: installer, signature and root checks
   still enforce, and full enforcement starts automatically
   once the function answers.
3. The `user_purchases` store check extended with `'play'`.
4. The 12 products created in Play Console with the IDs in
   `ProductCatalogue` (`com.paymentstraininghub.app.full`, and the 11
   bundle IDs).
5. The Play App Signing certificate SHA-256 set as the
   `paymentstraininghub.signingCertSha256` Gradle property (in
   `~/.gradle/gradle.properties`, never committed) so the
   signature check enforces. Until it is set, the check is
   skipped and reported as unconfigured.
6. Play App Signing enrolled, and the Play Integrity API
   enabled in the linked Google Cloud project.
7. Supabase redirect URLs include
   `com.paymentstraininghub.app://reset-password` (password recovery)
   and `com.paymentstraininghub.app://auth-callback`. Custom SMTP must
   stay configured so confirmation and reset emails send.

Content (search index, lessons, situations, weekly cases) is
fetched live from `https://www.paymentstraininghub.com`.
Lesson audio comes from the `paymentstraininghub-audio` Pages hosts,
with the backup host as fallback.

## Architecture

Single-activity Compose app. `TrainingHubApp` (the Application
class) initialises storage, sync, auth and the catalogue
warm-up; `MainActivity` owns deep-link intake and renders
`AppNavGraph`. Packages:

- `auth`: Supabase auth client, session manager, account-scoped
  storage, case ID, referrals.
- `billing`: Play Billing client, entitlement store, server
  sync through `verify-play-purchase`.
- `data`: content repository (website fetch + disk cache),
  product catalogue, situation gating.
- `features`: investigation, simulator, lab, studio, ISO
  explorer, chat assistant, habits, growth, notifications,
  analytics.
- `media`: ExoPlayer playback service, downloads.
- `security`: premium access gate (Play Integrity, installer
  and signature checks, root detection).
- `sync`: Supabase progress/preference sync with a durable
  pending queue.
- `ui`: screens by tab, navigation, theme (`PfTheme`).

## Anti-piracy

Premium content unlocks only when an entitlement AND the
premium access gate both allow it (`security/PremiumAccessGate`).
The gate checks, in release builds: the device is not rooted,
the app was installed from Google Play, the signing certificate
matches the Play App Signing hash (once configured, see item 5
above), and a Play Integrity verdict verified server-side by
the `verify-integrity` function (a pass is cached 24 hours;
only passes are cached). Debug builds skip the gate. Release
builds use R8 shrinking + obfuscation
(`proguard-android-optimize.txt`); never add `-dontobfuscate`.

Honest residual risk: the on-device checks stop casual mod-APK
redistribution (re-signed copies, sideloaded copies, rooted
devices, faked BillingClient responses), but a modified build
can remove or fake any client-side check. The real enforcement
is server-side: entitlements come only from `user_purchases`
rows the `verify-play-purchase` function wrote after Google
confirmed the purchase, and integrity verdicts come only from
`verify-integrity`. Content URLs are public today, so a
determined attacker can fetch lesson content directly;
authenticated content delivery is the follow-up that closes
that hole, not this gate.

Hard boundaries, enforced by code review and by grep in CI if
added: zero OTP/SMS/mobile-verification code anywhere (signup
is email + password + email confirmation; password recovery is
email-link only); no PayU/PayPal code, UI, links or SDKs in the
app (Google Play Billing only); user-facing text never says
"TrainingHub" (the brand is "Payments Training Hub").

## Tests

Unit tests under `app/src/test` cover pure JVM-testable logic:
the case-ID scheme, deep-link parsing and routing, the weekly
cycle rule, investigation scoring, the auth error catalog,
chapter-number parsing, paragraph splitting, the daily
challenge picker and scheme detection, course-certificate
rules, the situation explain parser and stacked-value
formatting, the test-catalogue parser, supplemental lessons,
and the premium-access gate, root and tamper checks. Device/
emulator testing, Play Billing sandbox testing, background
playback on a device, and visual layout checks were NOT
possible on the build VM and remain open; see the delivery
report for the full untested list.
