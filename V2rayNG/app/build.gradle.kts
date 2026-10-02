plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.filternet.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 773
        versionName = "4.0.0"

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // FILTERNET: a fixed signing key that ships with the repo, so every build
    // (local or GitHub Actions) is signed identically and can be installed as an
    // update on top of a previously installed FILTERNET APK.
    signingConfigs {
        create("filternet") {
            storeFile = file("filternet.jks")
            storePassword = "filternet"
            keyAlias = "filternet"
            keyPassword = "filternet"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("filternet")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("filternet")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
            resValue("string", "app_package_id", "com.filternet.app.fdroid")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
            resValue("string", "app_package_id", "com.filternet.app")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "v2rayNG_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "v2rayNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // FILTERNET: Psiphon, used as a fallback transport when the CDN path
    // itself is filtered and no amount of address scanning can help.
    //
    // This library is published for armeabi-v7a only. Because this project
    // splits its APKs per ABI, the arm64 APK will contain the Java classes
    // but no native library - PsiphonEngine.isAvailable detects exactly that
    // and disables the feature instead of crashing. Install the armeabi-v7a
    // APK to use Psiphon; it runs correctly on 64-bit devices.

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Reorderable list
    implementation(libs.reorderable)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

/* ══════════════════════════ FILTERNET: data bundling ══════════════════════════
 *
 * Runs tools/filternet-bundle.py before anything is compiled, so the APK that
 * comes out of this build carries the pool, the IRCF addresses, the Cloudflare
 * ranges and the sealed Worker configs as they stand *right now* rather than
 * whatever the app can download later from a network it may not have.
 *
 * Because the task runs on every build, bumping any of those counts is exactly
 * as much work as editing the file and pressing build - there is no second
 * place to remember.
 *
 * Deliberately forgiving: with no Python on PATH it leaves the committed assets
 * alone and warns, so cloning the repo into Android Studio and pressing Run
 * still produces a working APK. It only fails the build when the script is
 * there, runs, and reports an error - a real problem worth stopping for.
 *
 *   ./gradlew assembleDebug                      bundle what is on disk
 *   ./gradlew assembleDebug -PfnRefresh=true     re-resolve IRCF and Cloudflare first
 *
 * The private configs are read from $FN_INTERNAL_CONFIGS and sealed with
 * $FN_INTERNAL_PASSWORD; both are GitHub secrets in CI and simply absent on a
 * normal developer machine, where the committed internal.bin is reused.
 */

val filternetRepoRoot: File = rootProject.projectDir.parentFile
val filternetScript: File = File(filternetRepoRoot, "tools/filternet-bundle.py")
val filternetManifest: File = file("src/main/assets/fn-manifest.json")
val filternetRefresh: Boolean =
    (providers.gradleProperty("fnRefresh").orNull ?: System.getenv("FN_REFRESH") ?: "false")
        .toBoolean()

val filternetBundle = tasks.register("filternetBundle") {
    group = "filternet"
    description = "Bakes the server pool, IRCF addresses, Cloudflare ranges and " +
        "the sealed Worker configs into src/main/assets."

    // The whole point is to pick up data that changed outside Gradle's view.
    outputs.upToDateWhen { false }

    val script = filternetScript
    val workingDir = filternetRepoRoot
    val manifest = filternetManifest
    val refresh = filternetRefresh

    doLast {
        if (!script.isFile) {
            logger.warn("FILTERNET: ${script.path} not found - keeping the committed assets")
            return@doLast
        }

        val python = listOf("python3", "python").firstOrNull { exe ->
            runCatching {
                ProcessBuilder(exe, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .also { it.inputStream.readBytes() }
                    .waitFor() == 0
            }.getOrDefault(false)
        }
        if (python == null) {
            logger.warn("FILTERNET: no Python on PATH - keeping the committed assets")
            return@doLast
        }

        val cmd = buildList {
            add(python)
            add(script.absolutePath)
            if (refresh) add("--refresh")
        }
        logger.lifecycle("FILTERNET: ${cmd.joinToString(" ")}")

        val process = ProcessBuilder(cmd)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }

        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("FILTERNET: data bundling failed (exit $exit)")
        }
        if (!manifest.isFile) {
            throw GradleException("FILTERNET: ${manifest.name} was not produced")
        }
    }
}

tasks.named("preBuild") { dependsOn(filternetBundle) }
