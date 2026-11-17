import org.gradle.api.tasks.testing.Test
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.paymentstraininghub.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paymentstraininghub.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Expected SHA-256 of the Play App Signing certificate (the
        // certificate Google re-signs the app with, from Play
        // Console Release > Setup > App signing). Fed by the
        // paymentstraininghub.signingCertSha256 Gradle property, set in
        // ~/.gradle/gradle.properties. Empty means unconfigured:
        // the signature check is skipped and reported as such.
        // Never commit a value here; the property file stays local.
        buildConfigField(
            "String",
            "EXPECTED_SIGNING_CERT_SHA256",
            "\"${providers.gradleProperty("paymentstraininghub.signingCertSha256").orElse("").get()}\""
        )
        // Test-unlock gate: false everywhere except the qa build
        // type below. Declared here so every variant compiles;
        // only qa overrides it to true. Release never has it.
        buildConfigField("Boolean", "ENABLE_TEST_UNLOCK", "false")
    }

    signingConfigs {
        // Sideload signing (15 Nov 2026): the persistent keystore
        // carries the same certificate as the delivered 1115-0207
        // QA APK, so QA builds install over it without an
        // uninstall. The VM-local debug keystore regenerates per
        // session and must never sign QA builds again. The
        // password is the well-known debug password already on
        // record. The build fails loudly if the file is missing.
        create("sideload") {
            val keystoreFile = rootDir.resolve("../persistent-sideload.keystore")
            require(keystoreFile.exists()) {
                "Missing persistent sideload keystore: $keystoreFile. " +
                    "QA builds must be signed with it so they install over the 1115-0207 APK."
            }
            storeFile = keystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release (upload) signing (15 Nov 2026): a dedicated
        // upload keystore, generated for the Play Console release
        // track. NEVER sign release builds with the sideload
        // keystore: the sideload certificate is a debug
        // certificate shared with QA builds. The password lives
        // ONLY in ../release-signing.properties (mode 600,
        // outside any git repo, never committed, never printed).
        // On machines without the properties file or the keystore
        // (CI runners), release signing stays unconfigured and only
        // release packaging fails; debug and QA builds are unaffected.
        create("release") {
            // CI debug/QA builds run on runners without release
            // credentials (17 Nov 2026): only configure release signing
            // when the properties file AND the upload keystore are both
            // present. When either is missing, the signing config stays
            // unpopulated: debug and QA tasks are unaffected, and a
            // release packaging task fails at execution with a clear
            // error. When both files exist, the strict checks below
            // behave exactly as before.
            val propsFile = rootDir.resolve("../release-signing.properties")
            val keystoreFile = rootDir.resolve("../upload-release.keystore")
            if (!propsFile.exists() || !keystoreFile.exists()) {
                logger.warn(
                    "Release signing credentials not found; release signing is unconfigured. " +
                        "Debug and QA builds are unaffected."
                )
            } else {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                val storePw = props.getProperty("storePassword")
                    ?: error("release-signing.properties must define storePassword.")
                val keyPw = props.getProperty("keyPassword")
                    ?: error("release-signing.properties must define keyPassword.")
                require(storePw.isNotBlank() && keyPw.isNotBlank()) {
                    "release-signing.properties has a blank storePassword or keyPassword."
                }
                storeFile = keystoreFile
                storePassword = storePw
                keyAlias = "upload"
                keyPassword = keyPw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // qa: a small (~5 MB) sideload test build. Non-debuggable
        // so R8 drops the debug info a debuggable variant retains
        // (the shrunk debug APK was 8.39 MB for that reason),
        // signed with the persistent sideload keystore so it
        // installs as an update over the 1115-0207 QA APK. The
        // test unlock is enabled via ENABLE_TEST_UNLOCK; Release
        // never gets it.
        create("qa") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("sideload")
            buildConfigField("Boolean", "ENABLE_TEST_UNLOCK", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BillingManager and EntitlementStore read BuildConfig.DEBUG
        // for debug-only unlock paths. Never remove this.
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    // The Robolectric render harness composes real screens, which
    // need the merged resources and manifest at unit-test time.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM first, then the libraries. Versions follow the
    // scaffold guide; the BOM pins the Compose artifacts.
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Audio playback (MediaSessionService + ExoPlayer).
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")

    // Google Play Billing only. No PayU/PayPal code, UI, links or
    // SDKs may ever be added here: store purchases only.
    implementation("com.android.billingclient:billing:8.0.0")

    // Play Integrity API: the premium access gate requests an
    // integrity token and has the verify-integrity Edge Function
    // decode it server-side. No verdict is ever computed on device.
    implementation("com.google.android.play:integrity:1.4.0")

    // Play services auth is included for a future Google sign-in
    // return; no code references it today and Google sign-in is
    // out of scope. R8 strips it from release builds.
    implementation("com.google.android.gms:play-services-auth:21.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // kotlinx-serialization 1.6.3: the last runtime compatible with
    // Kotlin 1.9.22. 1.7.x requires Kotlin 2.0+ and fails compilation
    // ("requires at least Kotlin 2.0.0-RC1"); the brief's 1.7.1 pin
    // is incompatible with the pinned Kotlin version.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    // Robolectric render harness: real screens composed with fake
    // content, failing the build on any composition crash. The
    // ui-test artifacts resolve from the Compose BOM above.
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-manifest must be a debugImplementation dependency:
    // its manifest declares ComponentActivity, which the Compose
    // test rule launches. As a testImplementation dependency its
    // manifest never reaches the manifest Robolectric reads, and
    // the rule fails with "Unable to resolve activity". It adds
    // no code and never reaches release builds.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Forked unit-test JVMs need the same IPv4 loopback fix as the
// Gradle daemon (see gradle.properties): the Gradle test executor
// talks back to Gradle over loopback TCP, which breaks on a
// dual-stack JVM in sandboxed networks (the executor dies before
// running any test). Harmless on normal machines.
tasks.withType<Test>().configureEach {
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    // The Robolectric render harness loads a full android-all jar
    // per SDK level plus Compose; the default heap is too small.
    maxHeapSize = "2048m"
    // Robolectric resolves its android-all runtime jars through its
    // own Maven resolver, which cannot reach the network on this
    // VM. Offline mode makes it use the pre-downloaded jars in
    // ~/.m2/repository only. Harmless for plain unit tests.
    systemProperty("robolectric.offline", "true")
    // Offline mode resolves android-all jars from this flat
    // directory (one jar per SDK level), never from Maven. The
    // path is absolute because the daemon's user.home is /root,
    // not the workspace owner's home (same reason the offline
    // init script hardcodes the Maven repo path).
    systemProperty(
        "robolectric.dependency.dir",
        "/home/hatch/workspace/robolectric-deps"
    )
    // Robolectric refuses SDK 36 on Java 17 ("Android SDK 36
    // requires Java 21"). The render harness gate passes
    // -Ppaymentstraininghub.testJava21=true, which runs the test JVMs
    // on the JDK 21 at ~/jdk/jdk-21-temurin. Compilation stays
    // on the daemon JDK 17, and runs without the property keep
    // the previous behavior. The Android project has no Java
    // toolchain service, so the executable is set directly.
    if (providers.gradleProperty("paymentstraininghub.testJava21").orElse("false").get() == "true") {
        val jdk21Java = file("/home/hatch/jdk/jdk-21-temurin/bin/java")
        require(jdk21Java.exists()) {
            "paymentstraininghub.testJava21=true needs JDK 21 at $jdk21Java: Robolectric SDK 36 requires Java 21."
        }
        executable = jdk21Java.absolutePath
    }
}
