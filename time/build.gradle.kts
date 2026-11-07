plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kover)
}

// Android(D8)·서버 양쪽에서 읽히도록 바이트코드는 Java 11 로 낸다. 빌드 JDK 는 무엇이든 된다
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
    // 가시성을 적지 않은 선언은 컴파일 오류 — 공개 API 는 의도한 것만 연다
    explicitApi()
}

dependencies {
    // 조회가 suspend 이고 디스패처를 받는다 — 공개 API 에 드러나는 유일한 외부 타입이라 api 로 연다
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

mavenPublishing {
    coordinates(artifactId = "time", version = property("VERSION_NAME").toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("time")
        description.set("기기 시계 조작에 흔들리지 않는 현재 시각 — NTP·HTTP Date 폴백, 단조 시계 기준 오프셋, 출처·오차 추정")
        inceptionYear.set("2026")
    }
}

// 라인 커버리지 90% 미만이면 check(=build) 가 실패한다
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
