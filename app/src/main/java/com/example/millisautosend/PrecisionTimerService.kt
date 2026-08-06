package com.example.millisautosend

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

class PrecisionTimerService : Service() {

    private val taskSequence = AtomicLong(0L)
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val logFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelInternal("用户停止")
                return START_NOT_STICKY
            }

            ACTION_SCHEDULE -> {
                val targetWallMs = intent.getLongExtra(EXTRA_TARGET_WALL, 0L)
                val leadMs = intent.getLongExtra(EXTRA_LEAD_MS, 0L)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "定时任务"
                if (targetWallMs > 0L) {
                    scheduleInternal(targetWallMs, leadMs, label)
                } else {
                    stopSelf()
                }
            }

            else -> restoreTaskIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        taskSequence.incrementAndGet()
        worker?.interrupt()
        worker = null
        releaseWakeLock()
        super.onDestroy()
    }

    @Synchronized
    private fun scheduleInternal(targetWallMs: Long, leadMs: Long, label: String) {
        taskSequence.incrementAndGet()
        worker?.interrupt()
        releaseWakeLock()

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        prefs.edit()
            .putLong(MainActivity.KEY_TARGET_WALL, targetWallMs)
            .putLong(MainActivity.KEY_LEAD_MS, leadMs)
            .putString(MainActivity.KEY_LABEL, label)
            .putString(MainActivity.KEY_STATUS, "$label 已启动，等待触发")
            .apply()

        beginForeground(targetWallMs, label)

        val triggerWallMs = targetWallMs - leadMs
        val waitingMs = max(0L, triggerWallMs - System.currentTimeMillis())
        val deadlineNs = SystemClock.elapsedRealtimeNanos() + waitingMs * 1_000_000L
        val taskId = taskSequence.incrementAndGet()
        val taskWakeLock = acquireWakeLock(waitingMs + 60_000L)

        worker = Thread({
            try {
                preciseWaitUntil(deadlineNs, taskId)
                if (taskSequence.get() != taskId) return@Thread

                val result = ShizukuBridge.injectEnter()
                val errorMs = result.actionWallMs - triggerWallMs
                val resultText = if (result.success) {
                    "发送动作成功：${result.detail}"
                } else {
                    "发送失败：${result.detail}"
                }

                setStatus(
                    "$resultText\n" +
                        "预定派发：${logFormatter.format(Instant.ofEpochMilli(triggerWallMs))}\n" +
                        "实际调用：${logFormatter.format(Instant.ofEpochMilli(result.actionWallMs))}\n" +
                        "计时偏差：${if (errorMs >= 0) "+" else ""}${errorMs} ms"
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (throwable: Throwable) {
                setStatus("执行异常：${throwable.javaClass.simpleName}: ${throwable.message ?: "未知错误"}")
            } finally {
                if (taskSequence.get() == taskId) {
                    clearSavedTask()
                    worker = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                releaseWakeLock(taskWakeLock)
            }
        }, "MillisAutoSendTimer").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    private fun restoreTaskIfNeeded() {
        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val target = prefs.getLong(MainActivity.KEY_TARGET_WALL, 0L)
        val lead = prefs.getLong(MainActivity.KEY_LEAD_MS, 0L)
        val label = prefs.getString(MainActivity.KEY_LABEL, "恢复任务") ?: "恢复任务"
        if (target - lead > System.currentTimeMillis()) {
            scheduleInternal(target, lead, label)
        } else {
            clearSavedTask()
            stopSelf()
        }
    }

    @Synchronized
    private fun cancelInternal(reason: String) {
        taskSequence.incrementAndGet()
        worker?.interrupt()
        worker = null
        releaseWakeLock()
        clearSavedTask()
        setStatus(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun beginForeground(targetWallMs: Long, label: String) {
        val cancelIntent = Intent(this, PrecisionTimerService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val targetText = logFormatter.format(Instant.ofEpochMilli(targetWallMs))
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("毫秒定时发送正在等待")
            .setContentText("$label：$targetText")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", cancelPendingIntent)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定时任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持毫秒定时任务在后台运行"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock(timeoutMs: Long): PowerManager.WakeLock {
        releaseWakeLock()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:precisionTimer"
        ).apply {
            setReferenceCounted(false)
            acquire(max(60_000L, timeoutMs))
            wakeLock = this
        }
    }

    private fun releaseWakeLock(lock: PowerManager.WakeLock? = wakeLock) {
        lock?.let {
            if (it.isHeld) it.release()
        }
        if (wakeLock === lock) wakeLock = null
    }

    private fun clearSavedTask() {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .edit()
            .remove(MainActivity.KEY_TARGET_WALL)
            .apply()
    }

    private fun setStatus(message: String) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .edit()
            .putString(MainActivity.KEY_STATUS, message)
            .apply()
    }

    companion object {
        private const val CHANNEL_ID = "precision_timer"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_SCHEDULE = "com.example.millisautosend.SCHEDULE"
        private const val ACTION_CANCEL = "com.example.millisautosend.CANCEL"
        private const val EXTRA_TARGET_WALL = "target_wall"
        private const val EXTRA_LEAD_MS = "lead_ms"
        private const val EXTRA_LABEL = "label"

        fun schedule(
            context: Context,
            targetWallMs: Long,
            leadMs: Long,
            label: String
        ) {
            val intent = Intent(context, PrecisionTimerService::class.java).apply {
                action = ACTION_SCHEDULE
                putExtra(EXTRA_TARGET_WALL, targetWallMs)
                putExtra(EXTRA_LEAD_MS, leadMs)
                putExtra(EXTRA_LABEL, label)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, PrecisionTimerService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
