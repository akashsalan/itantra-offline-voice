import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.itantra.app"
    compileSdk = 37
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "org.itantra.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "0.9.1-emergency-alert"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                targets += "itantra_espeak"
                targets += "itantra_tts"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "models"
    productFlavors {
        create("base") { dimension = "models"; buildConfigField("boolean", "PRELOADED", "false") }
        create("demoPreloaded") { dimension = "models"; buildConfigField("boolean", "PRELOADED", "true") }
    }
    androidResources { noCompress += listOf("itpack", "onnx") }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].assets.directories.add("build/generated/assets")
    sourceSets["demoPreloaded"].assets.directories.add("build/generated/preloaded")
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencyLocking { lockAllConfigurations() }

val prepareAssets by tasks.registering(Sync::class) {
    from(rootProject.file(".tools/espeak-host/espeak-ng-data")) {
        into("espeak-ng-data")
        include("intonations", "phondata", "phonindex", "phontab", "lang/**", "voices/**")
        include(listOf("bn", "en", "gu", "hi", "kn", "ml", "mr", "or", "ta", "te").map { "${it}_dict" })
    }
    from(rootProject.file("models/models.lock.json"))
    from(rootProject.file("models/source/vad/silero_vad.int8.onnx")) { into("vad") }
    from(rootProject.file("licenses")) { into("licenses") }
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/assets"))
    doFirst {
        val moonshine = rootProject.file("third_party/moonshine/moonshine-voice-0.1.5-isolated-arm64.aar")
        check(moonshine.isFile && MessageDigest.getInstance("SHA-256").digest(moonshine.readBytes())
            .joinToString("") { "%02x".format(it) } == "cf948def454984963a9168e7e737d25ef3ab4b9f86e6d0c60b67d3e0fe41f893") {
            "Prepare pinned, isolated Moonshine runtime: python scripts/prepare-moonshine-small.py"
        }
        val vad = rootProject.file("models/source/vad/silero_vad.int8.onnx")
        check(vad.length() == 212860L && MessageDigest.getInstance("SHA-256")
            .digest(vad.readBytes()).joinToString("") { "%02x".format(it) } ==
            "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20") {
            "Missing or changed pinned Silero VAD model"
        }
        check(rootProject.file(".tools/espeak-host/espeak-ng-data/hi_dict").isFile) {
            "Build eSpeak host voice data first: powershell -File scripts/build-espeak-data.ps1"
        }
    }
}
tasks.named("preBuild") { dependsOn(prepareAssets) }
// Preloaded bundles the English and Hindi neural voices beside their recognisers.
// Every other language speaks with the embedded eSpeak voice until a user imports
// a voice pack, and the base flavour bundles no ASR or TTS weights at all.
val preparePreloadedVoices by tasks.registering(Sync::class) {
    from(rootProject.file("models/packs/en-voice.itpack"))
    from(rootProject.file("models/packs/hi-voice.itpack"))
    into(layout.buildDirectory.dir("generated/preloaded/starter-voices"))
    doFirst {
        for (code in listOf("en", "hi")) {
            val pack = rootProject.file("models/packs/$code-voice.itpack")
            check(pack.isFile) {
                "Build the $code neural voice pack first: python scripts/build-voice-packs.py $code"
            }
            ZipFile(pack).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader().use { it.readText() }
                check(manifest.contains("\"kind\": \"tts\"")) { "$code-voice.itpack is not a voice pack" }
                check(manifest.contains("tts.indictts.$code.fastpitch.hifigan.v1")) {
                    "$code-voice.itpack has an unexpected pack id"
                }
            }
        }
    }
}
val preparePreloaded by tasks.registering(Sync::class) {
    from(rootProject.file("models/packs/en.itpack"))
    from(rootProject.file("models/packs/en-low-end.itpack"))
    from(rootProject.file("models/packs/hi.itpack"))
    into(layout.buildDirectory.dir("generated/preloaded/starter-packs"))
    doFirst {
        check(rootProject.file("models/packs/en.itpack").isFile && rootProject.file("models/packs/hi.itpack").isFile) {
            "Build the English/Hindi packs before the demoPreloaded variant"
        }
        ZipFile(rootProject.file("models/packs/en.itpack")).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
            check(manifest.contains("asr.moonshine.en.small.streaming.2026-08-21.quantized")) {
                "Preloaded English is stale: build the Moonshine Small Streaming pack first"
            }
        }
        check(rootProject.file("models/packs/en-low-end.itpack").isFile) { "Build the Tiny Streaming English pack" }
        ZipFile(rootProject.file("models/packs/en-low-end.itpack")).use { zip ->
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
            check(manifest.contains("asr.moonshine.en.tiny.streaming.2026-08-21.quantized")) { "Wrong low-end English pack" }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("mergeDemoPreloaded") && name.endsWith("Assets")) {
        dependsOn(preparePreloaded, preparePreloadedVoices)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.compose.material:material-icons-core")
    implementation(files(rootProject.file("third_party/sherpa-onnx/sherpa-onnx-1.13.8.aar")))
    implementation(files(rootProject.file("third_party/moonshine/moonshine-voice-0.1.5-isolated-arm64.aar")))
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
