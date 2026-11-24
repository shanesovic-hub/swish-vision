plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.houseofswish.swishvision"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.houseofswish.swishvision"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // The shot chart / progress script is shared with the Swish Quest website (web/public/js/svstats.js).
    sourceSets["main"].assets.srcDirs("src/main/assets", "../web/public/js")

    // One fixed key so every new build installs over the last one.
    // Personal sideload key (it's in the public repo), only for the install-link APK.
    signingConfigs {
        create("personal") {
            storeFile = rootProject.file("keystore/swishvision.jks")
            storePassword = "swishvision"
            keyAlias = "swishvision"
            keyPassword = "swishvision"
        }
        // Google Play upload key: kept out of the repo, handed in by the build machine
        // (GitHub secrets PLAY_UPLOAD_KEYSTORE / PLAY_UPLOAD_PASSWORD). Google re-signs for the store.
        System.getenv("PLAY_KEYSTORE_FILE")?.takeIf { File(it).exists() }?.let { path ->
            create("play") {
                storeFile = File(path)
                storePassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
            }
        }
    }

    // Build with -Pplay to sign with the Play upload key (for the .aab going to Google Play).
    val forPlay = project.hasProperty("play") && signingConfigs.findByName("play") != null

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (forPlay) "play" else "personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "tflite" // the model is memory-mapped straight from the APK
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.annotation:annotation-experimental:1.4.1")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // LiteRT is TensorFlow Lite's successor (same org.tensorflow.lite code); unlike TF Lite 2.16 its
    // native libraries support 16 KB memory pages, which Google Play requires.
    val litert = "1.4.0"
    implementation("com.google.ai.edge.litert:litert:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu:$litert")
    implementation("com.google.ai.edge.litert:litert-gpu-api:$litert")

    testImplementation("junit:junit:4.13.2")
}
