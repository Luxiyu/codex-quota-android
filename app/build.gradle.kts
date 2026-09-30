import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 可将生成文件放到非同步目录，避免同步冲突副本参与 DEX 打包。
providers.gradleProperty("codexBuildDirectory").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "cn.luxy.codexquota"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "cn.luxy.codexquota"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libcodex.so"
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofSeconds(60))
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
