package com.example.millisautosend

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuBridge {

    private const val REQUEST_CODE = 1001

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remoteService: IShizukuShellService? = null

    private val initialized = AtomicBoolean(false)
    private val binding = AtomicBoolean(false)

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                ShellUserService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            binding.set(false)
            remoteService = IShizukuShellService.Stub.asInterface(binder)
            val uid = runCatching { remoteService?.serviceUid }.getOrNull()
            writeStatus(
                when (uid) {
                    0 -> "Shizuku 已连接（root 权限）"
                    2000 -> "Shizuku 已连接（ADB shell 权限）"
                    null -> "Shizuku 用户服务已连接"
                    else -> "Shizuku 已连接（UID=$uid）"
                }
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binding.set(false)
            remoteService = null
            writeStatus("Shizuku 用户服务已断开")
        }

        override fun onBindingDied(name: ComponentName) {
            binding.set(false)
            remoteService = null
            writeStatus("Shizuku 用户服务已失效，请重新连接")
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        writeStatus("已检测到运行中的 Shizuku")
        connectIfPermitted()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binding.set(false)
        remoteService = null
        writeStatus("Shizuku 已停止；重启 Shizuku 后重新连接")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    writeStatus("Shizuku 授权成功，正在连接用户服务")
                    bindUserService()
                } else {
                    writeStatus("Shizuku 授权被拒绝")
                }
            }
        }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun requestPermissionOrConnect(): String {
        return try {
            if (Shizuku.isPreV11()) {
                val message = "当前 Shizuku API 版本过旧，不受支持"
                writeStatus(message)
                message
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService()
                "已授权，正在连接 Shizuku 用户服务"
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                val message = "此前已拒绝授权，请在 Shizuku 的应用授权管理中允许本应用"
                writeStatus(message)
                message
            } else {
                Shizuku.requestPermission(REQUEST_CODE)
                "请在弹出的 Shizuku 授权窗口中选择允许"
            }
        } catch (throwable: Throwable) {
            val message = "未检测到运行中的 Shizuku，请先打开并启动 Shizuku"
            writeStatus(message)
            message
        }
    }

    fun connectIfPermitted() {
        try {
            if (!Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                bindUserService()
            }
        } catch (_: Throwable) {
            // Shizuku 尚未运行。
        }
    }

    private fun bindUserService() {
        if (isReady() || !binding.compareAndSet(false, true)) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            writeStatus("正在连接 Shizuku 用户服务")
        } catch (throwable: Throwable) {
            binding.set(false)
            remoteService = null
            writeStatus("连接 Shizuku 失败：${throwable.javaClass.simpleName}")
        }
    }

    fun isReady(): Boolean {
        val service = remoteService ?: return false
        return try {
            service.asBinder().pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun statusText(): String {
        if (isReady()) return readStatus("Shizuku 已连接")
        return readStatus("Shizuku 未连接")
    }

    fun injectEnter(): InjectResult {
        val service = remoteService
            ?: return InjectResult(false, "Shizuku 用户服务未连接", System.currentTimeMillis())

        val actionWallMs = System.currentTimeMillis()
        return try {
            val exitCode = service.injectEnter()
            if (exitCode == 0) {
                InjectResult(true, "Shizuku 注入 Enter（keyevent 66）", actionWallMs)
            } else {
                InjectResult(false, "input 命令失败，退出码 $exitCode", actionWallMs)
            }
        } catch (remoteException: RemoteException) {
            remoteService = null
            InjectResult(false, "Shizuku 远程服务断开", actionWallMs)
        } catch (throwable: Throwable) {
            InjectResult(false, "执行异常：${throwable.javaClass.simpleName}", actionWallMs)
        }
    }

    private fun writeStatus(message: String) {
        appContext?.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(MainActivity.KEY_SHIZUKU_STATUS, message)
            ?.apply()
    }

    private fun readStatus(defaultValue: String): String {
        return appContext?.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            ?.getString(MainActivity.KEY_SHIZUKU_STATUS, defaultValue)
            ?: defaultValue
    }

    data class InjectResult(
        val success: Boolean,
        val detail: String,
        val actionWallMs: Long
    )
}
