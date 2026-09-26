import java.security.MessageDigest

plugins { id("com.android.application") }
val relayIntegrationClasspath by configurations.creating
val passwordDistribution by configurations.creating { isTransitive = false }
val voiceDistribution by configurations.creating { isTransitive = false }
val voiceArtifact = files(rootProject.file("vendor/webrtc-150.7871.01-umbra.5.aar"))
val voiceArtifactSha256 = "25f2abebc99e2e109cff83a428080408843fda51a9cdadb5c081d694c92b7620"
val vaultLab = providers.gradleProperty("umbraVaultLab").orNull == "true"
val mediaLabReferences = layout.buildDirectory.file("generated/mediaLab/references.pro")
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
    if (name == "preConnectedDebugBuild" || name == "preConnectedReleaseBuild" || name == "preConnectedMediaLabBuild" || name == "preConnectedVaultLabBuild") {
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
        if (vaultLab) {
            create("vaultLab") {
                initWith(getByName("release"))
                applicationIdSuffix = ".vaultlab"
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += "release"
                proguardFiles("media-lab-rules.pro")
                isDebuggable = false
            }
        }
        if (providers.gradleProperty("umbraMediaLab").orNull == "true") {
            create("mediaLab") {
                initWith(getByName("release"))
                applicationIdSuffix = ".medialab"
                signingConfig = signingConfigs.getByName("debug")
                matchingFallbacks += "release"
                proguardFiles("media-lab-rules.pro")
                proguardFiles(mediaLabReferences)
                // Non-debuggable: R8 must use release optimization, not debug mode.
                isDebuggable = false
            }
        }
    }
    if (providers.gradleProperty("umbraMediaLab").orNull == "true") testBuildType = "mediaLab"
    if (vaultLab) testBuildType = "vaultLab"
    packaging {
        // Source-built WebRTC is already stripped (symbol_level=0). Preserve the
        // exact reviewed four-ABI bytes; APK policy verifies their pinned hashes.
        // This is specific to WebRTC, not a global symbol-processing exclusion.
        jniLibs.keepDebugSymbols += "**/libjingle_peerconnection_so.so"
        resources.excludes += setOf("libsignal_jni*.dylib", "signal_jni*.dll", "libsignal_jni*.so")
        // Upstream client-testing APIs are unused; retain production JNI for every ABI.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
}

if (providers.gradleProperty("umbraMediaLab").orNull == "true") {
    // A separate test APK is outside R8's program graph. Trace only this media
    // fixture's API references, not every application class or every Android test.
    val fixtureJar = tasks.register<Jar>("mediaLabEntryPoints") {
        dependsOn("compileConnectedMediaLabAndroidTestJavaWithJavac")
        archiveFileName.set("media-fixture-references.jar")
        destinationDirectory.set(layout.buildDirectory.dir("generated/mediaLab"))
        from(layout.buildDirectory.dir("intermediates/javac/connectedMediaLabAndroidTest/compileConnectedMediaLabAndroidTestJavaWithJavac/classes")) {
            include("app/umbra/media/ExpiredDeliveryAssertion.class", "app/umbra/media/VideoSurfaceLifecycleTest*.class", "app/umbra/media/CameraProviderFixtureListener*.class", "app/umbra/media/SyntheticVideoCapturer*.class", "app/umbra/media/VoiceEngineFixtureListener*.class", "app/umbra/media/VoiceRestartFixtureListener*.class",
                "app/umbra/lab/SqliteDeviceRecords*.class", "app/umbra/DeviceSignalTest*.class", "app/umbra/DeviceMemoryRecords*.class")
        }
    }
    val trace = tasks.register<JavaExec>("traceMediaLabApi") {
        dependsOn(fixtureJar)
        inputs.file(fixtureJar.flatMap { it.archiveFile })
        inputs.file(layout.buildDirectory.file("intermediates/compile_app_classes_jar/connectedMediaLab/bundleConnectedMediaLabClassesToCompileJar/classes.jar"))
        inputs.files(provider { (tasks.getByName("compileConnectedMediaLabAndroidTestJavaWithJavac") as JavaCompile).classpath })
        outputs.file(mediaLabReferences)
        mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
        classpath = files(com.android.tools.r8.R8::class.java.protectionDomain.codeSource.location)
        doFirst {
            val target = layout.buildDirectory.file("intermediates/compile_app_classes_jar/connectedMediaLab/bundleConnectedMediaLabClassesToCompileJar/classes.jar").get().asFile
            val compile = tasks.getByName("compileConnectedMediaLabAndroidTestJavaWithJavac") as JavaCompile
            val arguments = mutableListOf("--keep-rules", "--allowobfuscation", "--source", fixtureJar.get().archiveFile.get().asFile.path,
                "--target", target.path, "--output", mediaLabReferences.get().asFile.path)
            val libraries = compile.classpath.files + (compile.options.bootstrapClasspath?.files ?: emptySet())
            for (library in libraries.filter { it.canonicalFile != target.canonicalFile }) {
                arguments.addAll(listOf("--lib", library.path))
            }
            setArgs(arguments)
        }
        doLast {
            val rules = mediaLabReferences.get().asFile
            // Retain referenced signatures while still optimizing bodies and names.
            rules.writeText(rules.readText()
                .replace("-keep,allowobfuscation", "-keep,allowoptimization,allowobfuscation")
                .replace("-keep ", "-keep,allowoptimization,allowobfuscation "))
        }
    }
    tasks.configureEach {
        if (name == "minifyConnectedMediaLabWithR8" ||
            (name.contains("ConnectedMediaLab") && name.contains("lint", ignoreCase = true))) dependsOn(trace)
    }
}
if (vaultLab) {
    for (flavor in listOf("connected", "offline")) {
        val variant = "${flavor}VaultLab"
        val capital = variant.replaceFirstChar { it.uppercase() }
        val rules = layout.buildDirectory.file("generated/vaultLab/$flavor.pro")
        // AGP combines flavor/build-type rules; flavor-specific inputs avoid cross-flavor API roots.
        android.productFlavors.getByName(flavor).proguardFile(rules)
        val fixture = tasks.register<Jar>("${variant}FixtureReferences") {
            dependsOn("compile${capital}AndroidTestJavaWithJavac")
            archiveFileName.set("$variant-fixture.jar")
            destinationDirectory.set(layout.buildDirectory.dir("generated/vaultLab"))
            from(layout.buildDirectory.dir("intermediates/javac/${variant}AndroidTest/compile${capital}AndroidTestJavaWithJavac/classes")) {
                include("app/umbra/DeviceVaultPasswordTest*.class", "app/umbra/PasswordRestartFixtureListener*.class",
                    "app/umbra/DeviceSignalTest*.class", "app/umbra/DeviceMemoryRecords*.class")
            }
        }
        val trace = tasks.register<JavaExec>("trace${capital}Api") {
            dependsOn(fixture)
            inputs.file(fixture.flatMap { it.archiveFile })
            outputs.file(rules)
            mainClass.set("com.android.tools.r8.tracereferences.TraceReferences")
            classpath = files(com.android.tools.r8.R8::class.java.protectionDomain.codeSource.location)
            doFirst {
                val target = layout.buildDirectory.file("intermediates/compile_app_classes_jar/$variant/bundle${capital}ClassesToCompileJar/classes.jar").get().asFile
                val compile = tasks.getByName("compile${capital}AndroidTestJavaWithJavac") as JavaCompile
                val arguments = mutableListOf("--keep-rules", "--allowobfuscation", "--source", fixture.get().archiveFile.get().asFile.path,
                    "--target", target.path, "--output", rules.get().asFile.path)
                val libraries = compile.classpath.files + (compile.options.bootstrapClasspath?.files ?: emptySet())
                for (library in libraries.filter { it.canonicalFile != target.canonicalFile }) arguments.addAll(listOf("--lib", library.path))
                setArgs(arguments)
            }
            doLast {
                val file = rules.get().asFile
                file.writeText(file.readText().replace("-keep,allowobfuscation", "-keep,allowoptimization,allowobfuscation")
                    .replace("-keep ", "-keep,allowoptimization,allowobfuscation "))
            }
        }
        tasks.configureEach {
            if (name == "minify${capital}WithR8" || (name.contains(capital) && name.contains("lint", ignoreCase = true))) dependsOn(trace)
        }
    }
}

val verifyPasswordDistribution by tasks.registering {
    inputs.files(passwordDistribution)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256").digest(passwordDistribution.singleFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == "fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81") {
            "Argon2 distribution integrity failure"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyPasswordDistribution) }
dependencies {
    add(passwordDistribution.name, "org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
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
    // AndroidX test references these annotations; supply them for its R8 pass.
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.28.0")
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
