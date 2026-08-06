package com.example.millisautosend;

import android.content.Context;
import android.system.Os;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 此类由 Shizuku 以 shell/root 身份启动，不是普通 Android Service。
 */
public class ShellUserService extends IShizukuShellService.Stub {

    public ShellUserService() {
    }

    @Keep
    public ShellUserService(Context context) {
    }

    @Override
    public int injectEnter() {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/input",
                    "keyevent",
                    "66"
            ).redirectErrorStream(true).start();

            // input 命令一般没有输出；仍然读完，避免管道阻塞。
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // 丢弃输出。
                }
            }
            return process.waitFor();
        } catch (Throwable throwable) {
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public int getServiceUid() {
        return Os.getuid();
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
