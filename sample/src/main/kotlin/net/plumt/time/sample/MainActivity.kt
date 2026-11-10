package net.plumt.time.sample

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.plumt.time.TimeCallHandle
import net.plumt.time.TimeResult
import net.plumt.time.TrustedClock
import java.time.DateTimeException

/** 공개 메서드를 버튼 하나씩으로 불러 결과를 화면에 쌓는다. */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pending: TimeCallHandle? = null
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SampleApp
        val clock = app.clock
        log = TextView(this).apply { setText(R.string.ready) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(button(R.string.now) { pending = clock.now { show("now", it) } })
        column.addView(button(R.string.sync) { pending = clock.sync { show("sync", it) } })
        column.addView(button(R.string.now_suspend) { scope.launch { show("now suspend", clock.now()) } })
        column.addView(button(R.string.peek) { append("peek: ${clock.peek()}") })
        column.addView(button(R.string.java_clock) { javaClock(clock) })
        column.addView(button(R.string.invalidate) { clock.invalidate(); append("invalidate") })
        column.addView(button(R.string.strict) { pending = app.strictClock.sync { show("strict sync", it) } })
        column.addView(button(R.string.http_fallback) { pending = app.httpFallbackClock.sync { show("http fallback sync", it) } })
        column.addView(button(R.string.cancel) { pending?.cancel(); append("cancel: active=${pending?.isActive}") })
        column.addView(log)
        // target 35 이상은 edge-to-edge 가 강제라 시스템 바 높이만큼 비켜 둔다(안 하면 첫 버튼이 상태 표시줄 밑에 깔린다)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
    }

    override fun onDestroy() {
        pending?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun javaClock(clock: TrustedClock) {
        // java.time 은 API 26 부터 있다(이 샘플은 desugaring 을 켜지 않았다)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) javaClockApi26(clock) else append("asJavaClock: API 26 미만은 desugaring 필요")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun javaClockApi26(clock: TrustedClock) {
        try {
            append("asJavaClock: ${clock.asJavaClock().millis()}")
        } catch (e: DateTimeException) {
            append("asJavaClock: ${e.message}")
        }
    }

    private fun show(label: String, result: TimeResult) {
        val text = when (result) {
            is TimeResult.Success -> "${result.reading} 기기 시계 차이=${result.reading.deviceClockOffsetMillis} ms"
            is TimeResult.Failure -> "실패 ${result.failure.reason}: ${result.failure.message}"
        }
        append("$label: $text")
    }

    private fun append(line: String) {
        log.append("\n" + line)
    }

    private fun button(label: Int, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }
}
