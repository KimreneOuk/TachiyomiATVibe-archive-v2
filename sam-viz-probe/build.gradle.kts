plugins {
    id("mihon.android.application")
}

android {
    namespace = "eu.kanade.translation.samvizprobe"

    packaging {
        jniLibs {
            // QNN HTP needs the skel as a real file on disk (nativeLibraryDir)
            // for dsp_skel_dir discovery — keep extractNativeLibs effective.
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "eu.kanade.translation.samvizprobe"
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}

dependencies {
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.qualcomm)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

// Match the app workaround for LiteRT metadata built with Kotlin 2.4.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}
