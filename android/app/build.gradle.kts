plugins { id("com.android.application") }
android {
    namespace = "app.umbra"
    compileSdk = 36
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
        }
        create("offline") {
            dimension = "transport"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
            buildConfigField("boolean", "ALLOW_RELAY", "false")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
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
    }
}
dependencies {
    implementation("org.signal:libsignal-android:0.102.3")
    implementation("org.signal:libsignal-client:0.102.3")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
