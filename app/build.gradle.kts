plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The release workflow passes -PversionName=1.2.3 and -PversionCode=10203 (code = major*10000 + minor*100 + patch).
// Local builds use 0.0.1 / 1.
val appVersionName: String = (findProperty("versionName") as String?) ?: "0.0.1"
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.feerlessleadr.dockge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.feerlessleadr.dockge"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Shown in the app so a tester can tell which build is installed.
        val gitSha = try {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            "unknown"
        }
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        // The release key comes from the environment (the release workflow). Nothing is read from files in the repo.
        create("release") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// A release APK signed with the debug key must never be published. Building one needs an explicit opt-in;
// the release workflow has the real key (SIGNING_KEYSTORE_PATH).
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any {
        it.path.startsWith(":app:") && it.name.contains("Release") &&
            (it.name.startsWith("assemble") || it.name.startsWith("bundle") || it.name.startsWith("package"))
    }
    if (buildsRelease && System.getenv("SIGNING_KEYSTORE_PATH") == null && findProperty("allowDebugSigning") == null) {
        throw GradleException("A release build needs the release signing key (SIGNING_KEYSTORE_PATH). For a local test build that must not be published, pass -PallowDebugSigning.")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.socketio.client) {
        // Android ships org.json; the artifact would duplicate it.
        exclude(group = "org.json", module = "json")
    }
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
