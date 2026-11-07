plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    // 공개 API 를 모듈별 .api 파일로 고정한다. 호환이 깨지는 변경은 apiCheck 가 막는다
    alias(libs.plugins.binary.compatibility.validator)
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // 위반이 있으면 check(=build) 가 실패한다 — baseline 으로 덮어 두지 않는다
        ignoreFailures = false
        // 시험 코드는 빼고 발행되는 코드만 본다
        source.setFrom("src/main/kotlin")
    }
}
