package com.example.millisautosend

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.testButton.setOnClickListener {
            val service = AutoSendAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            service.scheduleAfter(delayMs = 5_000L, leadMs = 0L, label = "5秒测试")
            Toast.makeText(this, "已启动。请在5秒内切回目标页面并聚焦输入框", Toast.LENGTH_LONG).show()
        }

        binding.startButton.setOnClickListener {
            scheduleFromInput()
        }

        binding.stopButton.setOnClickListener {
            AutoSendAccessibilityService.instance?.cancelTask("用户停止")
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_TARGET_WALL).apply()
            refreshStatus()
        }

        binding.batteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
    }

    private fun scheduleFromInput() {
        val service = AutoSendAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

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

        service.scheduleAt(targetWallMs = targetWallMs, leadMs = leadMs, label = "正式任务")
        Toast.makeText(this, "任务已启动，请切回目标页面", Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val status = prefs.getString(KEY_STATUS, "未启动") ?: "未启动"
        val targetWall = prefs.getLong(KEY_TARGET_WALL, 0L)
        val targetText = if (targetWall > 0L) {
            val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(targetWall), ZoneId.systemDefault())
            "\n目标：${dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))}"
        } else ""
        binding.statusText.text = "状态：$status$targetText"
    }

    companion object {
        const val PREFS = "auto_send_prefs"
        const val KEY_STATUS = "status"
        const val KEY_TARGET_WALL = "target_wall"
        const val KEY_LEAD_MS = "lead_ms"
        const val KEY_LABEL = "label"
    }
}
