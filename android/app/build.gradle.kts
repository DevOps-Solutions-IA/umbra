import java.security.MessageDigest

plugins { id("com.android.application") }
val relayIntegrationClasspath by configurations.creating
val voiceDistribution by configurations.creating { isTransitive = false }
val voiceArtifact = files(rootProject.file("vendor/webrtc-150.7871.01-umbra.1.aar"))
val voiceArtifactSha256 = "bbc5675f91b31f901e1a482b00991a36ac2b3d912d2782b80e1cc1b756b1c413"
val verifyVoiceDistribution by tasks.registering {
    inputs.files(voiceDistribution)
    doLast {
        val artifact = voiceDistribution.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { stream ->
            val block = ByteArray(65536)
            while (true) {
                val count = stream.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == voiceArtifactSha256) {
            "WebRTC distribution integrity failure"
        }
    }
}
tasks.configureEach {
    if (name == "preConnectedDebugBuild" || name == "preConnectedReleaseBuild") {
        dependsOn(verifyVoiceDistribution)
    }
}
android {
    namespace = "app.umbra"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.umbra.privatechat"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "transport"
    productFlavors {
        create("connected") {
            dimension = "transport"
            buildConfigField("boolean", "ALLOW_RELAY", "true")
            buildConfigField("String", "VOICE_NATIVE_SHA256", "\"$voiceArtifactSha256\"")
        }
        create("offline") {
            dimension = "transport"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            buildConfigField("boolean", "ALLOW_RELAY", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Required by libsignal-android's AAR metadata.
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".dev" }
    }
    packaging {
        resources.excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so")
        // Upstream client-testing APIs are unused; retain production JNI for every ABI.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
}
dependencies {
    add(voiceDistribution.name, voiceArtifact)
    add("connectedImplementation", voiceArtifact)
    add(relayIntegrationClasspath.name, "org.signal:libsignal-client:0.102.3")
    add(relayIntegrationClasspath.name, "org.json:json:20250517")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    implementation("org.signal:libsignal-android:0.102.3")
    implementation("org.signal:libsignal-client:0.102.3")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

tasks.register("writeRelayIntegrationClasspath") {
    val destination = layout.buildDirectory.file("integration/classpath.txt")
    outputs.file(destination)
    doLast {
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(relayIntegrationClasspath.asPath)
        }
    }
}
