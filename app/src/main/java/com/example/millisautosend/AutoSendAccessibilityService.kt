package com.example.millisautosend

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AutoSendAccessibilityService : AccessibilityService() {

    private val taskSequence = AtomicLong(0L)
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
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

                // 无障碍节点查询和点击统一在主线程执行，避免部分系统返回空节点。
                val result = triggerSendOnMainThread()
                val actualWallMs = result.actionWallMs
                val errorMs = actualWallMs - triggerWallMs
                val resultText = if (result.success) {
                    "发送动作成功：${result.method}"
                } else {
                    "发送失败：${result.method}"
                }
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
                else -> Thread.yield()
            }
        }
    }

    private fun triggerSendOnMainThread(): SendResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return triggerSend()

        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<SendResult>()
        mainHandler.post {
            try {
                resultRef.set(triggerSend())
            } catch (t: Throwable) {
                resultRef.set(
                    SendResult(
                        success = false,
                        method = "执行异常：${t.javaClass.simpleName}",
                        actionWallMs = System.currentTimeMillis()
                    )
                )
            } finally {
                latch.countDown()
            }
        }

        if (!latch.await(2, TimeUnit.SECONDS)) {
            return SendResult(false, "主线程响应超时", System.currentTimeMillis())
        }
        return resultRef.get()
            ?: SendResult(false, "未取得执行结果", System.currentTimeMillis())
    }

    private fun triggerSend(): SendResult {
        val roots = collectCandidateRoots()
        if (roots.isEmpty()) {
            return SendResult(false, "没有可读取的应用窗口", System.currentTimeMillis())
        }

        // 微信窗口优先；之后才尝试其他前台应用窗口。
        val orderedRoots = roots.sortedWith(
            compareBy<RootEntry> {
                when {
                    it.packageName == WECHAT_PACKAGE -> 0
                    it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION -> 1
                    else -> 2
                }
            }.thenBy { it.windowId }
        )

        // 1. 先尝试对聚焦输入框执行输入法“发送/回车”。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (entry in orderedRoots) {
                val focused = findFocusedEditable(entry.root) ?: continue
                val actionTime = System.currentTimeMillis()
                if (focused.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                ) {
                    return SendResult(true, "输入法发送", actionTime)
                }
            }
        }

        // 2. 扫描所有应用窗口，而不是只扫描 rootInActiveWindow。
        //    键盘弹出时，rootInActiveWindow 有时会指向输入法窗口。
        for (entry in orderedRoots) {
            val candidate = findBestLabeledSendNode(entry.root) ?: continue
            val result = clickNodeOrAncestor(candidate, "文字按钮")
            if (result != null) return result
        }

        // 3. 微信专用兜底：不依赖按钮文字，根据输入框右侧的可点击控件自动识别绿色“发送”。
        for (entry in orderedRoots.filter { it.packageName == WECHAT_PACKAGE }) {
            val candidate = findWeChatRelativeSendNode(entry.root) ?: continue
            val result = clickNodeOrAncestor(candidate, "微信发送按钮")
            if (result != null) return result
        }

        // 4. 华为/微信常见情况：微信窗口可读取，但输入框和绿色“发送”均不作为
        //    无障碍节点暴露。此时根据微信可见窗口底边与输入法窗口顶边自动计算
        //    绿色发送按钮中心，不需要用户选择坐标。
        for (entry in orderedRoots.filter { it.packageName == WECHAT_PACKAGE }) {
            val result = clickWeChatSendByGeometry(entry, orderedRoots)
            if (result != null) return result
        }

        return SendResult(false, buildFailureDetail(orderedRoots), System.currentTimeMillis())
    }

    private fun collectCandidateRoots(): List<RootEntry> {
        val entries = mutableListOf<RootEntry>()
        val seen = mutableSetOf<String>()

        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName == this.packageName) continue
            val key = "${window.id}|$packageName|${window.type}"
            if (seen.add(key)) {
                entries += RootEntry(root, packageName, window.type, window.id)
            }
        }

        rootInActiveWindow?.let { root ->
            val packageName = root.packageName?.toString().orEmpty()
            if (packageName != this.packageName) {
                val key = "${root.windowId}|$packageName|active"
                if (seen.add(key)) {
                    entries += RootEntry(
                        root = root,
                        packageName = packageName,
                        windowType = AccessibilityWindowInfo.TYPE_APPLICATION,
                        windowId = root.windowId
                    )
                }
            }
        }
        return entries
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable && it.isEnabled && it.isVisibleToUser) return it
        }

        return collectNodes(root).firstOrNull {
            it.isEditable && it.isEnabled && it.isVisibleToUser && it.isFocused
        }
    }

    private fun findBestLabeledSendNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        for (node in collectNodes(root)) {
            if (!node.isVisibleToUser || !node.isEnabled) continue
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val score = maxOf(scoreLabel(text), scoreLabel(description), scoreViewId(viewId)) +
                if (isActionable(node)) 20 else 0
            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best?.takeIf { bestScore >= 60 }
    }

    private fun findWeChatRelativeSendNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = collectNodes(root)
        val editable = findFocusedEditable(root)
            ?: nodes.firstOrNull {
                it.isEditable && it.isEnabled && it.isVisibleToUser
            }
            ?: return null

        val editBounds = Rect().also(editable::getBoundsInScreen)
        if (editBounds.isEmpty) return null

        val density = resources.displayMetrics.density
        val maxGap = (240 * density).toInt()
        val minWidth = (28 * density).toInt()
        val maxWidth = (220 * density).toInt()
        val minHeight = (26 * density).toInt()
        val maxHeight = (110 * density).toInt()

        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        for (node in nodes) {
            if (node === editable || !node.isVisibleToUser || !node.isEnabled || !isActionable(node)) {
                continue
            }

            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.isEmpty) continue
            if (bounds.width() !in minWidth..maxWidth || bounds.height() !in minHeight..maxHeight) {
                continue
            }

            val gap = bounds.left - editBounds.right
            if (gap < -(24 * density).toInt() || gap > maxGap) continue

            val overlap = min(bounds.bottom, editBounds.bottom) - max(bounds.top, editBounds.top)
            val centerDistance = abs(bounds.centerY() - editBounds.centerY())
            val verticallyAligned = overlap > 0 || centerDistance <= (70 * density).toInt()
            if (!verticallyAligned) continue

            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val className = node.className?.toString().orEmpty()

            var score = 500
            score -= max(0, gap) / max(1, density.toInt())
            score -= centerDistance / max(1, density.toInt())
            score += maxOf(scoreLabel(text), scoreLabel(description)).coerceAtLeast(0)
            score += scoreViewId(viewId).coerceAtLeast(0)
            if (className.contains("Button", ignoreCase = true)) score += 100
            if (bounds.centerX() > editBounds.centerX()) score += 80
            if (overlap > 0) score += 80

            if (score > bestScore) {
                bestScore = score
                best = node
            }
        }
        return best
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val result = ArrayList<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty() && result.size < MAX_NODE_COUNT) {
            val node = queue.removeFirst()
            result += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return result
    }

    private fun scoreLabel(value: String): Int {
        if (value.isBlank()) return Int.MIN_VALUE / 2
        return when {
            value == "发送" -> 160
            value.equals("send", ignoreCase = true) -> 160
            value == "提交" || value == "确认" -> 120
            value.contains("发送") -> 100
            value.contains("send", ignoreCase = true) -> 100
            value.contains("提交") || value.contains("确认") -> 80
            else -> Int.MIN_VALUE / 2
        }
    }

    private fun scoreViewId(viewId: String): Int {
        if (viewId.isBlank()) return Int.MIN_VALUE / 2
        return when {
            viewId.contains("send", ignoreCase = true) -> 110
            viewId.contains("submit", ignoreCase = true) -> 90
            else -> Int.MIN_VALUE / 2
        }
    }

    private fun isActionable(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun clickNodeOrAncestor(
        startNode: AccessibilityNodeInfo,
        methodPrefix: String
    ): SendResult? {
        var node: AccessibilityNodeInfo? = startNode
        repeat(7) {
            val current = node ?: return@repeat
            if (current.isEnabled && (isActionable(current) || current === startNode)) {
                val actionTime = System.currentTimeMillis()
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val label = current.text ?: current.contentDescription ?: "发送"
                    return SendResult(true, "$methodPrefix：$label", actionTime)
                }
            }
            node = current.parent
        }

        // 某些自绘控件能被定位但拒绝 ACTION_CLICK；此时自动点击节点中心。
        val bounds = Rect().also(startNode::getBoundsInScreen)
        if (!bounds.isEmpty) {
            val actionTime = System.currentTimeMillis()
            val path = Path().apply {
                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 1L))
                .build()
            if (dispatchGesture(gesture, null, null)) {
                return SendResult(true, "$methodPrefix：自动触点", actionTime)
            }
        }
        return null
    }

    private fun clickWeChatSendByGeometry(
        weChatEntry: RootEntry,
        allRoots: List<RootEntry>
    ): SendResult? {
        val appBounds = Rect().also(weChatEntry.root::getBoundsInScreen)
        if (appBounds.isEmpty || appBounds.width() <= 0 || appBounds.height() <= 0) return null

        val density = resources.displayMetrics.density.coerceAtLeast(1f)

        // 键盘弹出时，发送按钮位于输入法窗口上方。部分系统返回的微信根窗口
        // 仍覆盖整块屏幕，因此优先使用输入法窗口的顶边作为聊天输入栏底边。
        val keyboardTop = allRoots
            .asSequence()
            .filter { it.windowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { entry ->
                val bounds = Rect().also(entry.root::getBoundsInScreen)
                bounds.top.takeIf {
                    !bounds.isEmpty &&
                        it > appBounds.top + (160f * density).toInt() &&
                        it <= appBounds.bottom
                }
            }
            .minOrNull()

        val visibleBottom = keyboardTop ?: appBounds.bottom
        val minUsableHeight = (180f * density).toInt()
        if (visibleBottom - appBounds.top < minUsableHeight) return null

        // 微信绿色“发送”按钮的中心通常距右边约 30dp，距输入法顶边约 30dp。
        // 使用 dp 和窗口边界计算，适配不同分辨率，不保存固定屏幕坐标。
        val x = (appBounds.right - 30f * density)
            .coerceIn(appBounds.left + 1f, appBounds.right - 1f)
        val y = (visibleBottom - 30f * density)
            .coerceIn(appBounds.top + 1f, visibleBottom - 1f)

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 35L))
            .build()
        val actionTime = System.currentTimeMillis()

        return if (dispatchGesture(gesture, null, null)) {
            SendResult(true, "微信绿色发送按钮（自动定位）", actionTime)
        } else {
            null
        }
    }

    private fun buildFailureDetail(roots: List<RootEntry>): String {
        val packages = roots.map { it.packageName.ifBlank { "未知包名" } }.distinct()
        val weChatRoot = roots.firstOrNull { it.packageName == WECHAT_PACKAGE }
        return when {
            weChatRoot == null -> "未读取到微信窗口；当前窗口：${packages.joinToString()}"
            else -> "已读取微信窗口，但节点点击和自动定位手势均未能派发"
        }
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

    data class SendResult(
        val success: Boolean,
        val method: String,
        val actionWallMs: Long
    )

    data class RootEntry(
        val root: AccessibilityNodeInfo,
        val packageName: String,
        val windowType: Int,
        val windowId: Int
    )

    companion object {
        private const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val MAX_NODE_COUNT = 5_000

        @Volatile
        var instance: AutoSendAccessibilityService? = null
            private set
    }
}
