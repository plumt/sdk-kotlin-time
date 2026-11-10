plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 발행하지 않는 확인용 앱. 소비 앱과 같은 조건(R8 전체 모드 + 리소스 축소, 앱 규칙은 기본 파일뿐)으로 release 를 만들어
// time 의 consumer-rules.pro 만으로 충분한지 본다. `./gradlew build` 가 assembleRelease 로 R8 을 돌린다.
// 실기기에서는 버튼으로 공개 메서드를 불러 본다(docs: 실기기 확인 목록)
android {
    namespace = "net.plumt.time.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.plumt.time.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // 확인용이라 debug 키로 서명한다(CI 는 AGP 가 debug 키를 만든다)
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":time"))
    implementation(libs.androidx.annotation)
}

// R8 이 release 를 실제로 만들었는지(난독화 매핑이 나왔는지) check 에서 확인한다
val verifyReleaseMinified = tasks.register("verifyReleaseMinified") {
    dependsOn("assembleRelease")
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    inputs.file(mapping)
    doLast {
        val text = mapping.get().asFile.readText()
        check(text.contains("net.plumt.time.internal.DefaultTrustedClock")) { "release 매핑에 time 내부 클래스가 없다 — R8 이 돌지 않았다" }
    }
}
tasks.named("check") { dependsOn(verifyReleaseMinified) }
