plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.luxury.mobile.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.luxury.mobile.launcher"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // BuildConfig fields make the launcher easy to retarget without editing code.
        // The fallback ZIP URL matches the existing HavanaRP launcher so this project
        // is drop-in compatible with the current backend.
        buildConfigField(
            "String",
            "MANIFEST_URL",
            "\"https://havanarpapo.zya.me/launcher_manifest.json\""
        )
        buildConfigField(
            "String",
            "FALLBACK_ZIP_URL",
            "\"https://www.dropbox.com/scl/fi/o0pfmhdjb9cire57l6ign/luxury.zip?rlkey=39w2xrir3833jhhbgtnxfd40v&st=r129oxug&dl=1\""
        )
        buildConfigField("String", "DATA_DIR_NAME", "\"LuxuryMobile\"")
        buildConfigField("String", "GAME_PACKAGE", "\"com.luxury.mobile\"")
        buildConfigField("String", "GAME_ACTIVITY", "\"com.luxury.mobile.core.GTASA\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isDebuggable = true
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Allow JVM unit tests to call Android framework stubs (e.g. android.util.Log)
            // without crashing. Stub methods return defaults (0 / null / false) which is
            // perfect for the deterministic core-logic tests we run on the JVM.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // org.json is in the Android framework jar, which on unit tests is just
    // stubs that return null. Pull a real implementation for JVM tests so
    // [DownloadMeta] round-trip tests exercise the real parser.
    testImplementation("org.json:json:20240303")
}
