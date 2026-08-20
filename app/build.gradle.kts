plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// CI stamps the build number in; a local build stays at 1 so it never looks newer than a real
// release and trigger a pointless self-update.
val zephyrVersionCode = (System.getenv("ZEPHYR_VERSION_CODE") ?: "1").toInt()
val zephyrVersionName = System.getenv("ZEPHYR_VERSION_NAME") ?: "0.1.0-dev"

android {
    namespace = "app.zephyr.fitness"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.zephyr.fitness"
        minSdk = 26
        targetSdk = 35
        versionCode = zephyrVersionCode
        versionName = zephyrVersionName

        // Always the newest release, so the URL never has to change as versions roll forward.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/hershibronner/Zephyr/releases/latest/download/update.json\"",
        )

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // A fixed key, committed to the repo on purpose.
        //
        // Android refuses to upgrade an installed app in place if the new APK carries a different
        // signature, and the stock debug keystore is generated fresh on every machine — so a CI
        // runner would sign each build with a new key and every self-update would die on
        // "App not installed". A stable key is what makes updating without uninstalling possible.
        //
        // This is a throwaway testing key with a published password and no value to protect. The
        // Play release is signed with a real key held in repo secrets, never this one.
        create("dev") {
            storeFile = file("zephyr-dev.keystore")
            storePassword = "zephyrdev"
            keyAlias = "zephyrdev"
            keyPassword = "zephyrdev"
        }
    }

    buildTypes {
        debug {
            // Keeps a debug build installable alongside a release one from the Play Store.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("dev.zephyr:core:1.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.play.services.location)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
