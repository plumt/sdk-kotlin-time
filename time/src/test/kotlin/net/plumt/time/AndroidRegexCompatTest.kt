package net.plumt.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 레포 안 모든 `Regex(...)` 리터럴이 Android 정규식 엔진(ICU)에서도 같은 뜻으로 컴파일되는지 검사한다.
 *
 * JVM 유닛 테스트·Robolectric 은 JDK 정규식으로 돌기 때문에 ICU 만 거부하는 패턴(짝 없는 `}` 등)을
 * 잡지 못한다. 실제로 `\{([^{}/]*)}` 가 JVM 에서는 통과하고 기기에서 `PatternSyntaxException` 으로
 * 죽었다. 그래서 두 엔진이 **같은 뜻으로 받는 좁은 문법**만 허용하고, 그 밖은 실패로 막는다.
 *
 * 허용하지 않는 것과 이유:
 * - 이스케이프 안 한 `}`·`]`, 수량자가 아닌 `{` — ICU 는 문법 오류, JDK 는 리터럴
 * - 문자 클래스 안의 이스케이프 안 한 `[`·`{`·`}`·`&&`, 맨 앞 `]` — 중첩·교집합·문자열 원소 해석이 엔진마다 다르다
 * - `\d \s \w \b`(대문자 포함) — Android 는 유니코드 숫자·공백·글자까지 맞춘다(JDK 는 ASCII)
 * - 이름 그룹 `(?<name>…)`·`\k<…>`·인라인 플래그 `(?i)`·역참조 `\1`·`\p{…}`·`\Q…\E` 등 — minSdk 23 미지원이거나 의미 차이
 * - `RegexOption` — `COMMENTS`·`CANON_EQ` 등 Android 미지원 플래그가 섞인다
 * - 리터럴이 아닌 인자·문자열 템플릿 — 검사할 수 없으므로 막는다
 */
class AndroidRegexCompatTest {

    private companion object {
        /** 이 레포의 현재 리터럴 수. 정규식을 지우면 낮추고, 0 이면 검사할 것이 없어 그대로 통과한다 */
        const val MIN_LITERALS = 3
    }

    @Test
    fun `레포의 모든 Regex 리터럴이 Android 호환 문법이다`() {
        val root = repoRoot()
        val sources = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}" in it.path }
            .filter { it.name != "AndroidRegexCompatTest.kt" }
            .toList()
        assertTrue("소스를 찾지 못했다: $root", sources.isNotEmpty())

        val problems = ArrayList<String>()
        var checked = 0
        for (file in sources) {
            val text = file.readText()
            val where = { offset: Int -> "${file.relativeTo(root)}:${text.substring(0, offset).count { it == '\n' } + 1}" }
            if ("RegexOption" in text) problems += "${file.relativeTo(root)}: RegexOption 은 쓰지 않는다"
            for (call in findRegexCalls(text)) {
                when (val lit = call.literal) {
                    null -> problems += "${where(call.offset)}: 리터럴이 아닌 Regex 인자는 검사할 수 없다"
                    else -> {
                        checked++
                        runCatching { Regex(lit) }.onFailure { problems += "${where(call.offset)}: JDK 도 거부 — ${it.message}" }
                        androidRegexViolation(lit)?.let { problems += "${where(call.offset)}: $it — 패턴 `$lit`" }
                    }
                }
            }
        }
        assertTrue("Android 비호환 정규식:\n" + problems.joinToString("\n"), problems.isEmpty())
        // 스캐너가 망가져 0건을 '통과'로 보지 않도록 — 지금 레포의 리터럴 수 이상이어야 한다
        assertTrue("검사한 리터럴이 너무 적다: $checked", checked >= MIN_LITERALS)
    }

    @Test
    fun `검사기는 기기에서 죽었던 패턴을 거부하고 고친 패턴은 받는다`() {
        assertTrue(androidRegexViolation("\\{([^{}/]*)}") != null)
        assertEquals(null, androidRegexViolation("\\{([^\\{\\}/]*)\\}"))
    }

    @Test
    fun `검사기 규칙 — 거부`() {
        listOf(
            "a}", "a]", "{", "a{", "a{1", "a{x}", "a{2,1}", "{2}",
            "[{]", "[}]", "[[a]]", "[a&&b]", "[]a]", "[a", "(a", "a)",
            "\\d", "\\D", "\\s", "\\S", "\\w", "\\W", "\\b", "\\B",
            "(?<n>a)", "\\k<n>", "(?i)a", "(?i:a)", "(a)\\1", "\\p{L}", "\\Qa\\E", "\\x41", "\\cA",
            "a\\", "*a", "a**", "a{1}{2}", "(?)", "(?#c)",
        ).forEach { assertTrue("거부돼야 한다: $it", androidRegexViolation(it) != null) }
    }

    @Test
    fun `검사기 규칙 — 허용`() {
        listOf(
            "[A-Za-z0-9_.-]{1,64}", "0|-?[1-9][0-9]*", "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?",
            "[0-9]+|0x[0-9a-f]*", "a{2,}", "a{3}", "a+?", "a*+", "(?:a|b)", "(?=a)", "(?!a)", "(?<=a)", "(?<!a)",
            "(?>a)", "^a$", "\\.", "\\{\\}", "\\[\\]", "[\\]\\[]", "[^\\{\\}/]", "\\t\\n\\r", "\\u00e9", "", "a|",
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+", "[-a]", "[a-]",
        ).forEach { assertEquals("허용돼야 한다: $it", null, androidRegexViolation(it)) }
    }

    @Test
    fun `스캐너는 일반·원시 문자열과 비 리터럴 인자를 구분한다`() {
        val calls = findRegexCalls(
            """
            val a = Regex("x\\{y\"")
            val b = Regex(${"\"\"\""}a\d${"\"\"\""})
            val c = Regex(name)
            val d = Regex("a${'$'}b")
            val e = "q+".toRegex()
            private const val ID = ${"\"\"\""}[0-9]+${"\"\"\""}
            val f = Regex(${"\"\"\""}^${'$'}ID(\.${'$'}{ID})*${'$'}${"\"\"\""})
            val g = Regex("${'$'}MISSING")
            """.trimIndent(),
        )
        assertEquals(
            listOf("x\\{y\"", "a\\d", null, null, "^[0-9]+(\\.[0-9]+)*$", null, "q+"),
            calls.map { it.literal },
        )
    }

    private fun repoRoot(): File {
        System.getProperty("repoRoot")?.let { return File(it) }
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "settings.gradle.kts 를 찾지 못했다" }
    }

    private class RegexCall(val offset: Int, val literal: String?)

    /** `Regex(<리터럴>` 과 `<리터럴>.toRegex(` 를 찾는다. 템플릿·비 리터럴은 literal = null. */
    private fun findRegexCalls(text: String): List<RegexCall> {
        val out = ArrayList<RegexCall>()
        var i = text.indexOf("Regex(")
        while (i >= 0) {
            val prev = if (i > 0) text[i - 1] else ' '
            if (!prev.isLetterOrDigit() && prev != '_' && prev != '.') {
                var j = i + "Regex(".length
                while (j < text.length && text[j].isWhitespace()) j++
                out += RegexCall(i, readLiteral(text, j)?.first)
            }
            i = text.indexOf("Regex(", i + 1)
        }
        var k = text.indexOf("\".toRegex(")
        while (k >= 0) {
            // 앞쪽으로 같은 줄에서 여는 따옴표를 찾는다(일반 문자열만)
            val lineStart = text.lastIndexOf('\n', k) + 1
            val open = (lineStart until k).firstOrNull { p -> text[p] == '"' && readLiteral(text, p)?.second == k + 1 }
            out += RegexCall(k, open?.let { readLiteral(text, it)?.first })
            k = text.indexOf("\".toRegex(", k + 1)
        }
        return out
    }

    /**
     * [start] 에서 시작하는 Kotlin 문자열 리터럴의 값과 끝(다음 위치).
     * 템플릿 `${'$'}NAME`·`${'$'}{NAME}` 은 같은 파일의 `const val NAME = <리터럴>` 로 풀고, 풀 수 없으면 값은 null.
     */
    private fun readLiteral(text: String, start: Int, depth: Int = 0): Pair<String?, Int>? {
        val raw = text.startsWith("\"\"\"", start)
        if (!raw && (start >= text.length || text[start] != '"')) return null
        val sb = StringBuilder()
        var unresolved = false
        var p = start + if (raw) 3 else 1
        while (p < text.length) {
            val c = text[p]
            when {
                raw && text.startsWith("\"\"\"", p) -> return (if (unresolved) null else sb.toString()) to p + 3
                !raw && c == '"' -> return (if (unresolved) null else sb.toString()) to p + 1
                !raw && c == '\n' -> return null
                !raw && c == '\\' && p + 1 < text.length -> {
                    when (val e = text[p + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'u' -> { sb.append(text.substring(p + 2, p + 6).toInt(16).toChar()); p += 4 }
                        else -> sb.append(e)
                    }
                    p += 2
                    continue
                }
                c == '$' && p + 1 < text.length && (text[p + 1] == '{' || text[p + 1].isLetter() || text[p + 1] == '_') -> {
                    val braced = text[p + 1] == '{'
                    val nameStart = p + if (braced) 2 else 1
                    var q = nameStart
                    while (q < text.length && (text[q].isLetterOrDigit() || text[q] == '_')) q++
                    val name = text.substring(nameStart, q)
                    val value = if (braced && (q >= text.length || text[q] != '}')) null else resolveConst(text, name, depth)
                    if (value == null) unresolved = true else sb.append(value)
                    p = if (braced) minOf(text.length, text.indexOf('}', q).let { if (it < 0) text.length else it + 1 }) else q
                    continue
                }
            }
            sb.append(c)
            p++
        }
        return null
    }

    /** 같은 파일의 `const val [name] = <리터럴>` 값. 없거나 너무 깊으면 null. */
    private fun resolveConst(text: String, name: String, depth: Int): String? {
        if (name.isEmpty() || depth > 4) return null
        val m = Regex("const val " + name + " *= *").find(text) ?: return null
        return readLiteral(text, m.range.last + 1, depth + 1)?.first
    }

    /**
     * 두 엔진이 같은 뜻으로 받는 좁은 문법을 벗어나면 이유를, 아니면 null 을 돌려준다.
     */
    private fun androidRegexViolation(pattern: String): String? {
        var i = 0
        var depth = 0
        var canQuantify = false // 바로 앞이 수량자를 붙일 수 있는 원자인가
        var lastWasQuantifier = false
        val n = pattern.length

        fun escapeOutside(c: Char): String? = when (c) {
            in "dDsSwWbB" -> "\\$c 는 Android 에서 유니코드까지 맞춘다 — [0-9] 처럼 명시"
            in "tnrf" -> null
            'u' -> null
            in "\\.^$|?*+()[]{}/-" -> null
            else -> "\\$c 는 엔진마다 다르거나 지원하지 않는다"
        }

        while (i < n) {
            val c = pattern[i]
            when (c) {
                '\\' -> {
                    if (i + 1 >= n) return "끝의 역슬래시"
                    val e = pattern[i + 1]
                    escapeOutside(e)?.let { return it }
                    if (e == 'u') {
                        if (i + 6 > n || !pattern.substring(i + 2, i + 6).all { it in "0123456789abcdefABCDEF" }) return "잘못된 \\u"
                        i += 6
                    } else {
                        i += 2
                    }
                    canQuantify = true; lastWasQuantifier = false
                }
                '[' -> {
                    i++
                    if (i < n && pattern[i] == '^') i++
                    if (i < n && pattern[i] == ']') return "문자 클래스 맨 앞의 ] 는 엔진마다 해석이 다르다 — \\] 로"
                    var closed = false
                    while (i < n) {
                        val d = pattern[i]
                        when {
                            d == ']' -> { closed = true; i++; break }
                            d == '\\' -> {
                                if (i + 1 >= n) return "끝의 역슬래시"
                                val e = pattern[i + 1]
                                if (e in "dDsSwWbB") return "\\$e 는 Android 에서 유니코드까지 맞춘다"
                                if (!(e in "tnrf\\.^$|?*+()[]{}/-" || e == 'u')) return "문자 클래스 안 \\$e 는 쓰지 않는다"
                                i += if (e == 'u') 6 else 2
                            }
                            d == '[' -> return "문자 클래스 안의 [ 는 ICU 에서 중첩 집합이다 — \\[ 로"
                            d == '{' || d == '}' -> return "문자 클래스 안의 $d 는 이스케이프한다"
                            d == '&' && i + 1 < n && pattern[i + 1] == '&' -> return "&& 교집합은 쓰지 않는다"
                            else -> i++
                        }
                    }
                    if (!closed) return "닫히지 않은 문자 클래스"
                    canQuantify = true; lastWasQuantifier = false
                }
                ']' -> return "이스케이프 안 한 ] — ICU 는 문법 오류"
                '}' -> return "이스케이프 안 한 } — ICU 는 문법 오류"
                '{' -> {
                    val close = pattern.indexOf('}', i)
                    if (close < 0) return "수량자가 아닌 { — ICU 는 문법 오류"
                    val body = pattern.substring(i + 1, close)
                    val m = Regex("([0-9]+)(,([0-9]*))?").matchEntire(body) ?: return "수량자가 아닌 {$body} — 이스케이프한다"
                    if (!canQuantify || lastWasQuantifier) return "수량자를 붙일 원자가 없다"
                    val lo = m.groupValues[1].toInt()
                    val hi = m.groupValues[3]
                    if (hi.isNotEmpty() && hi.toInt() < lo) return "수량자 범위가 거꾸로다"
                    i = close + 1
                    if (i < n && (pattern[i] == '?' || pattern[i] == '+')) i++
                    canQuantify = false; lastWasQuantifier = true
                }
                '*', '+', '?' -> {
                    if (!canQuantify || lastWasQuantifier) return "수량자를 붙일 원자가 없다"
                    i++
                    if (i < n && (pattern[i] == '?' || pattern[i] == '+')) i++
                    canQuantify = false; lastWasQuantifier = true
                }
                '(' -> {
                    i++
                    if (i < n && pattern[i] == '?') {
                        val rest = pattern.substring(i)
                        val ok = listOf("?:", "?=", "?!", "?<=", "?<!", "?>").firstOrNull { rest.startsWith(it) }
                            ?: return "그룹 구문 (${rest.take(3)} 는 쓰지 않는다 — 이름 그룹·인라인 플래그는 엔진마다 다르다"
                        i += ok.length
                    }
                    depth++
                    canQuantify = false; lastWasQuantifier = false
                }
                ')' -> {
                    if (depth == 0) return "짝 없는 )"
                    depth--; i++
                    canQuantify = true; lastWasQuantifier = false
                }
                '|' -> { i++; canQuantify = false; lastWasQuantifier = false }
                '^', '$' -> { i++; canQuantify = false; lastWasQuantifier = false }
                else -> { i++; canQuantify = true; lastWasQuantifier = false }
            }
        }
        if (depth != 0) return "닫히지 않은 그룹"
        return null
    }
}
