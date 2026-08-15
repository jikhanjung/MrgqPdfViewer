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
        versionCode = 13
        versionName = "0.1.13"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Load signing properties
            val signingPropsFile = rootProject.file("signing.properties")
            if (signingPropsFile.exists()) {
                val signingProps = Properties()
                signingPropsFile.inputStream().use { signingProps.load(it) }
                
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
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
        // CI 는 lintDebug 를 게이트로 돌린다 (release 빌드에서 중복 실행 방지)
        checkReleaseBuilds = false
        // 오류가 있으면 빌드를 실패시킨다 (기본값이지만 의도를 명시)
        abortOnError = true

        // 의도적 예외 — 사유를 남긴다. 이유 없는 disable 은 만들지 않는다.
        disable += setOf(
            // targetSdk 30. Play 스토어에 배포하지 않고 사이드로딩만 하므로 Play 의
            // target API 요구사항이 적용되지 않는다. 올릴 경우 Android 11+ 의
            // 스토리지/패키지 가시성 변경을 재검증해야 하므로 별도 작업으로 다룬다.
            "ExpiredTargetSdkVersion"
        )
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

// Room 스키마를 app/schemas 에 JSON 으로 내보낸다 (이 프로젝트는 ksp 가 아니라 kapt 를 쓴다).
//
// 꺼져 있으면 두 가지를 잃는다:
//  1. **마이그레이션 테스트를 쓸 수 없다** — MigrationTestHelper 는 과거 버전 스키마 JSON 이
//     있어야 그 시점 DB 를 만들 수 있다.
//  2. **스키마 드리프트 감지가 없다** — 엔티티를 고치고 version 을 안 올려도 아무도 막지
//     않다가, 사용자 기기에서 "Room cannot verify the data integrity" 로 죽는다.
//     내보낸 JSON 을 커밋해 두면 CI 가 git diff 로 잡는다.
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
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