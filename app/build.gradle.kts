plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.moodle"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.moodle"
        // 28: guarantees the emoji set (incl. Emoji 11 "smiling face with hearts") renders
        // inside the notification, which cannot use EmojiCompat.
        minSdk = 28
        targetSdk = 35
        // Bump versionCode on every build you want to install over the previous one.
        versionCode = 5
        versionName = "1.4"
    }

    // The signing key lives in the repo on purpose: every rebuild must carry the SAME
    // signature, otherwise Android refuses the update and the only fix is uninstalling,
    // which would delete every recorded moment. This is a personal, sideloaded app.
    signingConfigs {
        create("project") {
            storeFile = rootProject.file("moodle-release.jks")
            storePassword = "moodle-local"
            keyAlias = "moodle"
            keyPassword = "moodle-local"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("project")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("project")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric needs merged resources/manifest to run the real activities.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
