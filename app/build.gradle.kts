import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.mrgq.pdfviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mrgq.pdfviewer"
        minSdk = 21
        targetSdk = 30  // Android TV OS 11
        versionCode = 10
        versionName = "0.1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Load signing properties
            val signingPropsFile = rootProject.file("signing.properties")
            if (signingPropsFile.exists()) {
                val signingProps = Properties()
                signingPropsFile.inputStream().use { signingProps.load(it) }
                
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            } else {
                // Fallback to debug keystore if signing.properties doesn't exist
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "KEYSTORE_PASSWORD", "\"mrgqpdfviewerpass\"")
        }
        debug {
            buildConfigField("String", "KEYSTORE_PASSWORD", "\"mrgqpdfviewerpass\"")
        }
    }
    
    applicationVariants.all {
        outputs.all {
            if (this is com.android.build.gradle.internal.api.BaseVariantOutputImpl) {
                val fileName = when (buildType.name) {
                    "release" -> "MrgqPdfViewer-v${versionName}-release.apk"
                    "debug" -> "MrgqPdfViewer-v${versionName}-debug.apk"
                    else -> "MrgqPdfViewer-${buildType.name}.apk"
                }
                outputFileName = fileName
            }
        }
    }
    
    lint {
        checkReleaseBuilds = false
        // 또는 특정 오류만 무시하려면:
        // disable.add("ExpiredTargetSdkVersion")
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    
    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // HTTP Server for file upload
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // WebSocket for collaboration
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON processing for collaboration messages
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Network state handling
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}