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
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keenzero.app"
        // Floor retained at 29: see docs/TARGET_OS_COMPATIBILITY.md.
        // Not lowered blindly — classic Mi Box S Pie (API 28) is out of install range
        // until a physical device model is confirmed and a deliberate floor change is approved.
        minSdk = 29
        targetSdk = 35
        versionCode = 116
        versionName = "0.1.96"

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
    // The MVP is intentionally v7a-first. This artifact contains the Java API and
    // libtorrent JNI for the measured 32-bit Mi Box target.
    implementation(libs.libtorrent4j.android.arm)

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
