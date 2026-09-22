import java.io.FileOutputStream
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Gradle 데몬은 gradlew 를 부른 쉘의 환경 변수를 그대로 물려받지 않는다.
 * System.getenv 로 읽으면 데몬이 처음 뜬 시점의 환경이 잡혀서, 서명 키가 있는데도
 * debug 키로 서명되는 일이 생긴다. providers.environmentVariable 은 호출하는 쪽의
 * 환경을 제대로 읽으므로 반드시 이쪽을 쓴다.
 */

fun env(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val buildNumber: Int = env("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

/**
 * CI 는 KEYSTORE_BASE64 시크릿을 파일로 풀어 릴리스 APK 를 서명한다.
 * 시크릿이 없는 로컬 빌드에서는 null 을 돌려주고 debug 키로 서명한다.
 */
val releaseKeystore: File? = env("KEYSTORE_BASE64")
    ?.let { encoded ->
        val target = File(layout.buildDirectory.get().asFile, "pairplay-release.jks")
        target.parentFile.mkdirs()
        FileOutputStream(target).use { out ->
            out.write(Base64.getDecoder().decode(encoded.trim()))
        }
        target
    }

android {
    namespace = "com.pairplay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pairplay.app"
        minSdk = 26
        targetSdk = 35

        // 빌드마다 증가시켜 기존 앱 위에 덮어 설치할 수 있게 한다.
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("KEYSTORE_PASSWORD")
                keyAlias = env("KEY_ALIAS")
                keyPassword = env("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
