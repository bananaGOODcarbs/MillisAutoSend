package com.example.millisautosend

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

class AutoSendAccessibilityService : AccessibilityService() {

    private val taskSequence = AtomicLong(0L)
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val logFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        setStatus("无障碍服务已开启")

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val savedTarget = prefs.getLong(MainActivity.KEY_TARGET_WALL, 0L)
        val savedLead = prefs.getLong(MainActivity.KEY_LEAD_MS, 0L)
        val label = prefs.getString(MainActivity.KEY_LABEL, "恢复任务") ?: "恢复任务"
        if (savedTarget - savedLead > System.currentTimeMillis()) {
            scheduleAt(savedTarget, savedLead, label)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        cancelTask("无障碍服务被中断")
    }

    override fun onDestroy() {
        cancelTask("无障碍服务已关闭")
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun scheduleAfter(delayMs: Long, leadMs: Long, label: String) {
        scheduleAt(System.currentTimeMillis() + delayMs, leadMs, label)
    }

    @Synchronized
    fun scheduleAt(targetWallMs: Long, leadMs: Long, label: String) {
        cancelTask(null)
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        prefs.edit()
            .putLong(MainActivity.KEY_TARGET_WALL, targetWallMs)
            .putLong(MainActivity.KEY_LEAD_MS, leadMs)
            .putString(MainActivity.KEY_LABEL, label)
            .apply()

        val triggerWallMs = targetWallMs - leadMs
        val waitingMs = max(0L, triggerWallMs - System.currentTimeMillis())
        val deadlineNs = SystemClock.elapsedRealtimeNanos() + waitingMs * 1_000_000L
        val taskId = taskSequence.incrementAndGet()
        val taskWakeLock = acquireWakeLock(waitingMs + 60_000L)
        setStatus("$label 已启动，等待触发")

        worker = Thread({
            try {
                preciseWaitUntil(deadlineNs, taskId)
                if (taskSequence.get() != taskId) return@Thread

                val actualWallMs = System.currentTimeMillis()
                val result = triggerSend()
                val errorMs = actualWallMs - triggerWallMs
                val resultText = if (result.success) "发送动作成功：${result.method}" else "发送失败：未找到可操作的输入框或发送按钮"
                setStatus(
                    "$resultText\n" +
                        "预定派发：${logFormatter.format(Instant.ofEpochMilli(triggerWallMs))}\n" +
                        "实际派发：${logFormatter.format(Instant.ofEpochMilli(actualWallMs))}\n" +
                        "计时偏差：${if (errorMs >= 0) "+" else ""}${errorMs} ms"
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                setStatus("执行异常：${t.javaClass.simpleName}: ${t.message ?: "未知错误"}")
            } finally {
                if (taskSequence.get() == taskId) {
                    getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                        .edit().remove(MainActivity.KEY_TARGET_WALL).apply()
                    worker = null
                }
                releaseWakeLock(taskWakeLock)
            }
        }, "MillisAutoSendTimer").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @Synchronized
    fun cancelTask(reason: String?) {
        taskSequence.incrementAndGet()
        worker?.interrupt()
        worker = null
        releaseWakeLock()
        if (reason != null) setStatus(reason)
    }

    private fun preciseWaitUntil(deadlineNs: Long, taskId: Long) {
        while (taskSequence.get() == taskId) {
            val remainingNs = deadlineNs - SystemClock.elapsedRealtimeNanos()
            if (remainingNs <= 0L) return

            when {
                remainingNs > 250_000_000L -> {
                    val sleepMs = min(1_000L, (remainingNs - 120_000_000L) / 1_000_000L)
                    Thread.sleep(max(1L, sleepMs))
                }
                remainingNs > 20_000_000L -> {
                    LockSupport.parkNanos(min(5_000_000L, remainingNs - 5_000_000L))
                    if (Thread.interrupted()) throw InterruptedException()
                }
                else -> {
                    // 最后约 20 ms 保持高频检查。Thread.yield() 可兼容较旧 Android。
                    Thread.yield()
                }
            }
        }
    }

    private fun triggerSend(): SendResult {
        val root = rootInActiveWindow ?: return SendResult(false, "无活动窗口")

        // 优先对当前聚焦、可编辑的输入框执行输入法“发送/回车”。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable && focused.isEnabled) {
                val supportsImeEnter = focused.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                }
                if (supportsImeEnter && focused.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                ) {
                    return SendResult(true, "输入法发送")
                }
            }
        }

        // 输入法动作不可用时，自动查找“发送 / Send / 提交”按钮。
        val candidate = findBestSendNode(root)
        if (candidate != null) {
            val clickable = findClickableSelfOrParent(candidate)
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return SendResult(true, "点击“${candidate.text ?: candidate.contentDescription ?: "发送"}”")
            }
        }

        return SendResult(false, "未找到")
    }

    private fun findBestSendNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.isEnabled) {
                val text = node.text?.toString()?.trim().orEmpty()
                val description = node.contentDescription?.toString()?.trim().orEmpty()
                val score = maxOf(scoreLabel(text), scoreLabel(description)) +
                    if (node.isClickable) 20 else 0
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return best?.takeIf { bestScore >= 60 }
    }

    private fun scoreLabel(value: String): Int {
        if (value.isBlank()) return Int.MIN_VALUE / 2
        return when {
            value == "发送" -> 120
            value.equals("send", ignoreCase = true) -> 120
            value == "提交" -> 100
            value.contains("发送") -> 80
            value.contains("send", ignoreCase = true) -> 80
            value.contains("提交") -> 70
            else -> Int.MIN_VALUE / 2
        }
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true && current?.isEnabled == true) return current
            current = current?.parent
        }
        return null
    }

    private fun acquireWakeLock(timeoutMs: Long): PowerManager.WakeLock {
        releaseWakeLock()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val newWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:precisionTimer"
        ).apply {
            setReferenceCounted(false)
            acquire(max(60_000L, timeoutMs))
        }
        wakeLock = newWakeLock
        return newWakeLock
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock? = wakeLock) {
        lock?.let {
            if (it.isHeld) it.release()
        }
        if (wakeLock === lock) wakeLock = null
    }

    private fun setStatus(message: String) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .edit().putString(MainActivity.KEY_STATUS, message).apply()
    }

    data class SendResult(val success: Boolean, val method: String)

    companion object {
        @Volatile
        var instance: AutoSendAccessibilityService? = null
            private set
    }
}
