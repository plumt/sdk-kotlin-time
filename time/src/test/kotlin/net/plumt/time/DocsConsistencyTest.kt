package net.plumt.time

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 값을 하나 더할 때 고치는 곳은 정의 한 곳이다. 그 정의에서 파생되는 문서 표(ERRORS.md·README·CHANGELOG)가
 * 어긋나면 여기서 실패한다.
 */
class DocsConsistencyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val errors = File(root, "ERRORS.md").readText()
    private val readme = File(root, "README.md").readText()
    private val changelog = File(root, "CHANGELOG.md").readText()
    private val version = File(root, "time/gradle.properties").readLines()
        .first { it.startsWith("VERSION_NAME=") }.substringAfter('=')

    /** ERRORS.md 의 `FailureReason` 표 첫 열에 적힌 이름. */
    private fun tableNames(): Set<String> {
        val section = errors.substringAfter("| `FailureReason` |").substringBefore("\n\n")
        return section.lines().filter { it.startsWith("| `") }
            .map { it.removePrefix("| `").substringBefore('`') }
            .filter { name -> name.isNotEmpty() && name.all { it in 'A'..'Z' || it == '_' } }
            .toSet()
    }

    @Test fun `ERRORS 의 FailureReason 표와 코드가 같다`() {
        val code = FailureReason.values.map { it.name }.toSet()
        val table = tableNames()
        assertTrue("ERRORS.md 에 없는 원인: ${code - table}", (code - table).isEmpty())
        assertTrue("코드에 없는 원인이 ERRORS.md 에 있다: ${table - code}", (table - code).isEmpty())
    }

    @Test fun `ERRORS 는 정책·출처 값을 모두 적는다`() {
        val missing = listOf("DeviceClock", "LastSyncOnly", "None").filterNot { errors.contains("FallbackPolicy.$it") } +
            TimeOrigin.values.map { it.name }.filterNot { errors.contains("`${it}`") }
        assertTrue("ERRORS.md 에 없는 값: $missing", missing.isEmpty())
    }

    @Test fun `README 설치 줄과 빌드 버전이 같다`() {
        val line = "net.plumt.time:time:$version"
        assertTrue("README 에 설치 줄이 없다: $line", readme.contains(line))
    }

    @Test fun `README 는 API 레벨 표와 알려진 한계를 둔다`() {
        assertTrue(readme.contains("## API 레벨별 동작"))
        assertTrue(readme.contains("## 알려진 한계"))
        assertTrue(readme.contains("| 23 |"))
        assertTrue(readme.contains("| 26 |"))
    }

    @Test fun `CHANGELOG 는 현재 버전을 적는다`() {
        assertTrue("CHANGELOG 에 $version 이 없다", changelog.contains("## [$version]"))
    }
}
