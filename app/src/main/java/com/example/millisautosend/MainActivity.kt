package com.example.millisautosend

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.millisautosend.databinding.ActivityMainBinding
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val targetFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.currentTimeText.text = "当前时间：${LocalDateTime.now().format(clockFormatter)}"
            refreshStatus()
            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.timeInput.setText(LocalDateTime.now().plusMinutes(1).format(targetFormatter))
        requestNotificationPermissionIfNeeded()

        binding.openShizukuButton.setOnClickListener {
            openShizuku()
        }

        binding.shizukuButton.setOnClickListener {
            Toast.makeText(
                this,
                ShizukuBridge.requestPermissionOrConnect(),
                Toast.LENGTH_LONG
            ).show()
        }

        binding.testButton.setOnClickListener {
            if (!requireShizukuReady()) return@setOnClickListener
            PrecisionTimerService.schedule(
                context = this,
                targetWallMs = System.currentTimeMillis() + 5_000L,
                leadMs = 0L,
                label = "5秒测试"
            )
            Toast.makeText(
                this,
                "请在5秒内切回微信，让输入框保持焦点；请先确认微信已开启“回车键发送消息”",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.startButton.setOnClickListener {
            scheduleFromInput()
        }

        binding.stopButton.setOnClickListener {
            PrecisionTimerService.cancel(this)
            refreshStatus()
        }

        binding.batteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.connectIfPermitted()
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
    }

    private fun scheduleFromInput() {
        if (!requireShizukuReady()) return

        val timeText = binding.timeInput.text.toString().trim()
        val leadMs = binding.leadInput.text.toString().trim().toLongOrNull()
        if (leadMs == null || leadMs !in -60_000L..60_000L) {
            binding.leadInput.error = "请输入 -60000 到 60000"
            return
        }

        val parsedTime = try {
            java.time.LocalTime.parse(timeText, targetFormatter)
        } catch (_: DateTimeParseException) {
            binding.timeInput.error = "格式应为 HH:mm:ss.SSS，例如 14:00:00.350"
            return
        }

        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(parsedTime)
        if (!target.isAfter(now.plusSeconds(1))) {
            target = target.plusDays(1)
        }
        val targetWallMs = target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        PrecisionTimerService.schedule(
            context = this,
            targetWallMs = targetWallMs,
            leadMs = leadMs,
            label = "正式任务"
        )
        Toast.makeText(
            this,
            "任务已启动。请切回微信，保持屏幕解锁、输入框聚焦，并确认回车键用于发送",
            Toast.LENGTH_LONG
        ).show()
        refreshStatus()
    }

    private fun requireShizukuReady(): Boolean {
        if (ShizukuBridge.isReady()) return true
        Toast.makeText(
            this,
            "Shizuku 尚未连接。请先启动 Shizuku，再点击“授权并连接 Shizuku”",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        binding.shizukuStatusText.text = "Shizuku：${ShizukuBridge.statusText()}"

        val status = prefs.getString(KEY_STATUS, "未启动") ?: "未启动"
        val targetWall = prefs.getLong(KEY_TARGET_WALL, 0L)
        val targetText = if (targetWall > 0L) {
            val dt = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(targetWall),
                ZoneId.systemDefault()
            )
            "\n目标：${dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))}"
        } else {
            ""
        }
        binding.statusText.text = "状态：$status$targetText"
    }

    private fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/")
                )
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                2001
            )
        }
    }

    companion object {
        const val PREFS = "auto_send_prefs"
        const val KEY_STATUS = "status"
        const val KEY_SHIZUKU_STATUS = "shizuku_status"
        const val KEY_TARGET_WALL = "target_wall"
        const val KEY_LEAD_MS = "lead_ms"
        const val KEY_LABEL = "label"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
