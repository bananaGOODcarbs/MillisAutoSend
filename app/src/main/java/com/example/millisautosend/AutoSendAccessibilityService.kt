
package com.example.millisautosend

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoSendAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoSend"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun sendWechatMessage() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.e(TAG, "微信窗口读取失败")
            return
        }

        // 先尝试节点点击
        val nodes = root.findAccessibilityNodeInfosByText("发送")
        for (node in nodes) {
            if (node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "发送节点点击成功")
                return
            }
        }

        // 微信部分版本不暴露发送按钮，使用屏幕尺寸自动估计
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        // 微信输入栏通常位于屏幕底部键盘上方，
        // 发送按钮在输入区域右侧
        val x = (width * 0.91).toInt()
        val y = (height * 0.74).toInt()

        Log.d(TAG, "屏幕=${width}x${height}, 手势点击=$x,$y")

        gestureClick(x, y)
    }

    private fun gestureClick(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    80
                )
            )
            .build()

        val result = dispatchGesture(
            gesture,
            null,
            null
        )

        Log.d(TAG, "dispatchGesture=$result")
    }
}
