import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "1.9.24"
}

configure<ApplicationExtension> {
    namespace = "com.xenonware.notes"
    compileSdk = 37

    androidResources {
        noCompress += listOf("tflite", "f16", "f32")
    }

    defaultConfig {
        applicationId = "com.ultrapg.notes"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "XENON_COMMONS_VERSION", "\"${libs.versions.xenonCommons.get()}\"")
        buildConfigField("String", "XENON_UI_VERSION", "\"${libs.versions.xenonUi.get()}\"")

        ndk {
            val includeX86 = project.findProperty("includeX86")?.toString()?.toBoolean() == true
            if (includeX86) {
                abiFilters += listOf("arm64-v8a", "x86_64")
            } else {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val candidatePropsFiles = listOf(
                file("${System.getProperty("user.home")}/.android/notes-keys/keystore.properties"),
                rootProject.file("keystore.properties")
            )
            candidatePropsFiles.firstOrNull { it.exists() }?.inputStream()?.use { props.load(it) }

            val resolvedStorePath = System.getenv("NOTES_KEYSTORE_PATH")
                ?: props.getProperty("storeFile")
            val resolvedStorePassword = System.getenv("NOTES_KEYSTORE_PASSWORD")
                ?: props.getProperty("storePassword")
            val resolvedKeyAlias = System.getenv("NOTES_KEY_ALIAS")
                ?: props.getProperty("keyAlias")
            val resolvedKeyPassword = System.getenv("NOTES_KEY_PASSWORD")
                ?: props.getProperty("keyPassword")

            if (!resolvedStorePath.isNullOrBlank() &&
                !resolvedStorePassword.isNullOrBlank() &&
                !resolvedKeyAlias.isNullOrBlank() &&
                !resolvedKeyPassword.isNullOrBlank()
            ) {
                val ksFile = file(resolvedStorePath)
                if (ksFile.exists()) {
                    storeFile = ksFile
                    storePassword = resolvedStorePassword
                    keyAlias = resolvedKeyAlias
                    keyPassword = resolvedKeyPassword
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                }
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-d"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "XENON_COMMONS_VERSION", "\"${libs.versions.xenonCommons.get()}\"")
            buildConfigField("String", "XENON_UI_VERSION", "\"${libs.versions.xenonUi.get()}\"")
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("String", "XENON_COMMONS_VERSION", "\"${libs.versions.xenonCommons.get()}\"")
            buildConfigField("String", "XENON_UI_VERSION", "\"${libs.versions.xenonUi.get()}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "**/libLiteRt.so",
                "**/libLiteRtClGlAccelerator.so",
                "**/libLiteRtDispatch_*.so",
                "**/libLiteRtCompilerPlugin_*.so",
                "**/libc++_shared.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.xenon.commons)
    implementation(libs.reorderable)
    implementation(libs.androidx.material3.window.size.class1.android)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.firebase.database)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.androidx.compose.ui.graphics)

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.animation.graphics)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.accompanist.systemuicontroller)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.mathparser.org.mxparser)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material.icons.extended)

    // Unified LiteRT 2.3.2 Core, GPU Accelerator & NPU Runtimes (declared before desertant-voz so
    // pickFirsts packages LiteRT 2.3.2's libLiteRt.so & libLiteRtClGlAccelerator.so)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.qualcomm)
    implementation(libs.litert.npu.mediatek)
    implementation(libs.litert.npu.google.tensor)

    // Desert Ant Labs Voz (Parakeet TDT 0.6B v3 multilingual on-device STT)
    implementation(libs.desertant.voz)

    implementation(libs.firebase.auth.ktx)
    implementation(libs.play.services.auth)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.compose.material3.window.size.class1)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.okhttp)
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
