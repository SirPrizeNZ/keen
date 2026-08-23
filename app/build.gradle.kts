import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    // Stable private signing lives outside the repo (never commit passwords).
    val external = File(System.getProperty("user.home"), ".keen-zero/signing/keen-release.properties")
    if (external.exists()) external.inputStream().use { load(it) }
}

android {
    namespace = "com.keenzero.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.keenzero.app"
        // Floor retained at 29: see docs/TARGET_OS_COMPATIBILITY.md.
        // Not lowered blindly — classic Mi Box S Pie (API 28) is out of install range
        // until a physical device model is confirmed and a deliberate floor change is approved.
        minSdk = 29
        targetSdk = 35
        versionCode = 256
        versionName = "0.2.22"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_ID", "\"${buildId()}\"")
        buildConfigField("String", "GIT_SHA", "\"${gitSha()}\"")
        buildConfigField("String", "CORPUS_VERSION", "\"0.1.0\"")
        buildConfigField("boolean", "PHASE0_LAB", "true")

        // First-class 32-bit Android TV target. No native libs yet; when JNI
        // arrives it must ship armeabi-v7a (and optional arm64-v8a later).
        // Pure Java/Kotlin APKs install on any ABI including armeabi-v7a.
        // Acceptance evidence for this phase must come from 32-bit execution.
    }

    // Explicit product flavor so CI/device lab can force a 32-bit-labelled APK
    // without excluding future arm64. Pure Kotlin/Java — ABI tag is metadata.
    flavorDimensions += "abiPolicy"
    productFlavors {
        create("universal") {
            dimension = "abiPolicy"
            isDefault = true
            buildConfigField("String", "ABI_POLICY", "\"universal-with-armeabi-v7a-jni\"")
            buildConfigField("String", "PRIMARY_ABI", "\"armeabi-v7a\"")
        }
        create("armeabiV7a") {
            dimension = "abiPolicy"
            applicationIdSuffix = ".v7a"
            versionNameSuffix = "-v7a"
            buildConfigField("String", "ABI_POLICY", "\"armeabi-v7a-first\"")
            buildConfigField("String", "PRIMARY_ABI", "\"armeabi-v7a\"")
            ndk { abiFilters += listOf("armeabi-v7a") }
        }
        // 64-bit build. Deliberately a separate artifact rather than a replacement:
        // almost every mainstream TV box still ships a 32-bit userspace on 64-bit
        // silicon (the Google TV Streamer and onn 4K Pro both report armeabi-v7a only,
        // as does our own Android 14 test box), so a device that can run this one is
        // the exception — Shield TV, 2nd-gen Fire TV Cube, and TV-shaped tablets.
        // Installing the wrong one fails at install time, hence the distinct
        // applicationId suffix: both can sit side by side while we work out which
        // a given box takes.
        create("arm64V8a") {
            dimension = "abiPolicy"
            applicationIdSuffix = ".v8a"
            versionNameSuffix = "-v8a"
            buildConfigField("String", "ABI_POLICY", "\"arm64-v8a-only\"")
            buildConfigField("String", "PRIMARY_ABI", "\"arm64-v8a\"")
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // 16 KB page-size readiness for future native deps.
            useLegacyPackaging = false
        }
    }

    signingConfigs {
        getByName("debug")
        // Stable private update key (~/.keen-zero/signing/). Not Android Debug.
        create("releaseStable") {
            val store = localProps.getProperty("keen.release.storeFile")
            if (store != null) {
                storeFile = file(store)
                storePassword = localProps.getProperty("keen.release.storePassword")
                keyAlias = localProps.getProperty("keen.release.keyAlias")
                keyPassword = localProps.getProperty("keen.release.keyPassword")
                    ?: localProps.getProperty("keen.release.storePassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val releaseStable = signingConfigs.getByName("releaseStable")
            require(releaseStable.storeFile != null && releaseStable.storeFile!!.exists()) {
                "Stable release signing missing. Create ~/.keen-zero/signing/keen-release.jks " +
                    "and keen-release.properties (see docs/SIGNING_IDENTITY.md). " +
                    "Debug keystore is not allowed for release Mi Box candidates."
            }
            signingConfig = releaseStable
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    // Leanback presence is for TV device filtering / future leanback surfaces only.
    implementation(libs.androidx.leanback)
    implementation(libs.nanohttpd)
    // Native torrent playback: WebView <video> cannot decode E-AC-3/DTS audio;
    // ExoPlayer reaches the platform (Amlogic) MediaCodec audio decoders.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // libtorrent4j ships one artifact per ABI, each carrying the same Java API plus its
    // own libtorrent4j.so. Wired per flavour so a single-ABI APK never carries the other
    // architecture's 15 MB payload; abiFilters alone would not drop it, since these are
    // separate jars rather than one fat AAR. Both are pinned to the same libtorrent4j
    // version — a mismatch would put two copies of the Java API on the classpath.
    "universalImplementation"(libs.libtorrent4j.android.arm)
    "universalImplementation"(libs.libtorrent4j.android.arm64)
    "armeabiV7aImplementation"(libs.libtorrent4j.android.arm)
    "arm64V8aImplementation"(libs.libtorrent4j.android.arm64)

    testImplementation(libs.junit)
    // Real org.json for unit tests (Android stubs throw "not mocked").
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

fun buildId(): String {
    val ts = System.getenv("KEEN_BUILD_ID")
        ?: "local-${System.currentTimeMillis()}"
    return ts.replace("\"", "")
}

fun gitSha(): String {
    return try {
        val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}
