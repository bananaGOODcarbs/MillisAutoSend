# 不安装 Android Studio，在线生成 APK

## 需要什么

- 一台可以打开网页的电脑
- 一个免费的 GitHub 账号
- 不需要安装 Android Studio、Java 或 Android SDK

## 操作步骤

1. 登录 GitHub，点击右上角 `+`，选择 `New repository`。
2. 仓库名称可以填写 `MillisAutoSend`，选择 `Private` 或 `Public`，然后创建。
3. 在新仓库页面点击 `uploading an existing file`。
4. 解压本压缩包，将 `MillisAutoSend` 文件夹内的全部文件和文件夹拖入上传区域。必须包含隐藏目录 `.github`。
5. 点击页面下方的 `Commit changes`。
6. 打开仓库顶部的 `Actions`。
7. 左侧选择 `Build APK`，点击 `Run workflow`，再点击绿色的 `Run workflow`。
8. 等待任务显示绿色对勾，点击该次运行记录。
9. 页面底部找到 `Artifacts`，下载 `MillisAutoSend-debug-apk`。
10. 解压下载文件，里面的 `app-debug.apk` 就是手机安装包。

## 手机安装

将 `app-debug.apk` 传到华为手机，打开并允许“安装未知应用”。首次使用还需要开启：

- 毫秒定时发送的无障碍服务
- 允许后台活动
- 忽略电池优化

先使用应用中的“5 秒后测试发送”，确认目标应用支持自动发送。
