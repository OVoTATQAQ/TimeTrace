package org.timetrace.app

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Android collector MVP:
 * - User manually grants Usage Access in Android Settings.
 * - Reads completed foreground-app intervals in the last 24 hours.
 * - Persists a JSONL snapshot privately and exports it via Android's file picker.
 * No Accessibility Service, VPN, screenshot, contacts or Internet permission.
 */
class MainActivity : Activity() {
    companion object {
        private const val CREATE_DOCUMENT_REQUEST = 42
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }

    private lateinit var status: TextView
    private lateinit var exportButton: Button
    private var snapshot: List<Segment> = emptyList()

    data class Segment(val appId: String, val start: Long, val end: Long) {
        init { require(start < end) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val title = TextView(this).apply {
            text = "TimeTrace Android  ·  采集原型"
            textSize = 22f
        }
        layout.addView(title)
        val notice = TextView(this).apply {
            text = "只读取应用使用事件，不记录屏幕内容或键盘输入。\n" +
                    "本版仅手动读取最近 24 小时，未实现自动后台采集。"
            textSize = 14f
            setPadding(0, 12, 0, 16)
        }
        layout.addView(notice)
        layout.addView(makeButton("1. 打开使用情况访问授权") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        layout.addView(makeButton("2. 读取最近 24 小时") { readUsage() })
        exportButton = makeButton("3. 导出活动记录 JSONL") { exportSnapshot() }.apply {
            isEnabled = false
        }
        layout.addView(exportButton)
        status = TextView(this).apply {
            text = "请先在系统设置中为 TimeTrace 开启『使用情况访问』，再返回这里读取。"
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
        layout.addView(status)
        val scroll = ScrollView(this)
        scroll.addView(layout)
        setContentView(scroll)
    }

    private fun makeButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.CENTER
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("device", MODE_PRIVATE)
        prefs.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        if (!prefs.edit().putString("id", id).commit()) {
            throw IllegalStateException("无法保存设备标识")
        }
        return id
    }

    private fun readUsage() {
        if (!hasUsageAccess()) {
            status.text = "未获得使用情况访问权限，请先前往系统设置授权。"
            return
        }
        status.text = "正在读取应用事件……"
        Thread {
            try {
                val end = System.currentTimeMillis()
                val segments = queryCompletedSegments(end - DAY_MS, end)
                val lines = toJsonl(segments)
                File(filesDir, "segments.jsonl").writeText(lines, Charsets.UTF_8)
                val group = segments.groupBy { it.appId }.mapValues { entry ->
                    entry.value.sumOf { it.end - it.start }
                }.toList().sortedByDescending { it.second }
                runOnUiThread {
                    snapshot = segments
                    exportButton.isEnabled = segments.isNotEmpty()
                    status.text = buildString {
                        append("完成：")
                        append(segments.size)
                        append(" 个已结束的使用区间。\n\n")
                        if (segments.isEmpty()) append("未发现完整区间；请实际切换应用并返回重试。\n")
                        for ((app, duration) in group.take(20)) {
                            append(app).append(" — ")
                                .append("%.1f".format(duration / 60_000.0))
                                .append(" 分钟\n")
                        }
                        append("\n说明：活跃但尚未结束的区间不会计入；")
                        append("历史起点之前已打开的应用可能缺少开始事件。")
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { status.text = "读取失败：${ex.message}" }
            }
        }.start()
    }

    private fun queryCompletedSegments(begin: Long, end: Long): List<Segment> {
        val manager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val events = manager.queryEvents(begin, end) ?: return emptyList()
        val item = UsageEvents.Event()
        val intervals = mutableListOf<Segment>()
        var app: String? = null
        var start = 0L
        fun close(at: Long) {
            val previous = app
            if (previous != null && start in 1 until at) {
                intervals.add(Segment("android:$previous", start, at))
            }
            app = null
            start = 0L
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(item)
            val at = item.timeStamp
            when (item.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val name = item.packageName ?: continue
                    if (app != name) {
                        close(at)
                        app = name
                        start = at
                    }
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (item.packageName == app) close(at)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN -> close(at)
            }
        }
        // Do not assume an ongoing activity ends at the query boundary.
        // Merge short same-app gaps introduced by lifecycle event transitions.
        val sorted = intervals.sortedWith(compareBy({ it.appId }, { it.start }))
        val merged = mutableListOf<Segment>()
        for (segment in sorted) {
            val last = merged.lastOrNull()
            if (last != null && last.appId == segment.appId && segment.start <= last.end + 1500L) {
                merged[merged.lastIndex] = Segment(last.appId, last.start, maxOf(last.end, segment.end))
            } else {
                merged.add(segment)
            }
        }
        return merged.sortedBy { it.start }
    }

    private fun stableId(device: String, segment: Segment): String {
        val material = "$device|${segment.appId}|${segment.start}|${segment.end}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun toJsonl(segments: List<Segment>): String {
        val device = deviceId()
        return segments.joinToString(separator = "\n", postfix = if (segments.isEmpty()) "" else "\n") {
            JSONObject().apply {
                put("segment_id", stableId(device, it))
                put("device_id", device)
                put("app_id", it.appId)
                put("start_ms", it.start)
                put("end_ms", it.end)
                put("source", "android_usage_events")
                put("confidence", "observed")
            }.toString()
        }
    }

    private fun exportSnapshot() {
        if (snapshot.isEmpty()) {
            Toast.makeText(this, "请先读取活动记录", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, "timetrace-android.jsonl")
        }
        startActivityForResult(intent, CREATE_DOCUMENT_REQUEST)
    }

    @Deprecated("Activity result API is sufficient for this proof-of-concept")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CREATE_DOCUMENT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val lines = toJsonl(snapshot)
            contentResolver.openOutputStream(uri, "w")?.use { stream ->
                stream.write(lines.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw IllegalStateException("无法打开导出位置")
            Toast.makeText(this, "导出成功", Toast.LENGTH_LONG).show()
        } catch (ex: Exception) {
            Toast.makeText(this, "导出失败：${ex.message}", Toast.LENGTH_LONG).show()
        }
    }
}
