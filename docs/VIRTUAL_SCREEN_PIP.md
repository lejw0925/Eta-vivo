# 虚拟屏预览的系统画中画选项

## 使用方式

在「虚拟屏」设置中打开「虚拟屏预览」，再将「预览样式」选为「系统画中画」。也可选择原有「悬浮窗」；旧版设置迁移后默认保持悬浮窗。英语、简体中文、繁体中文均提供相同选项。

画中画只显示虚拟屏画面，系统负责圆角、拖动、缩放及菜单。没有 Eta 自绘标题和关闭按钮；系统菜单自己的关闭、放大按钮保留。放大进入现有虚拟屏控制页，返回后恢复任务预览。用户关闭画中画后，同一轮任务不再自动弹出；重新开启预览或切换样式可再次显示。

![预览方式设置](images/virtual-screen-preview/settings-2026-10-11.png)

![原生画中画中的构造页面](images/virtual-screen-preview/native-pip-2026-10-11.png)

第二张图中顶部文字属于测试应用自身的应用栏。图片仅包含测试窗口，不包含用户应用画面。

## 实现与边界

专用 `VirtualScreenPipPreviewActivity` 使用独立任务和 `supportsPictureInPicture`，复用 `VirtualScreenSurfaceView`、旋转适配及现有帧流。目标应用继续运行在原有虚拟 display。预览始终使用 `FLAG_SECURE`；测试截图仅临时取消构造窗口的保护，随后恢复。

执行服务先显示已获授权的悬浮预览，再启动专用 Activity；Activity 恢复后仅请求一次 `enterPictureInPictureMode()`，画中画就绪后移除交接窗口。这条路径继续要求悬浮窗权限，并遵守后台启动限制。权限拒绝、不支持画中画或启动超时会保留悬浮窗，不循环启动失败的 Activity。画中画权限关闭时，设置页提供系统授权入口。参见 [Android PiP 指南](https://developer.android.com/develop/ui/views/picture-in-picture)与[后台 Activity 启动限制](https://developer.android.com/guide/components/activities/background-starts)。

从服务直接使用 `ActivityOptions.makeLaunchIntoPip()` 的实测路径没有可返回的宿主任务：这台 ROM 最初没有自动进入 PiP，手动进入后系统放大又移除了预览。最终实现使用普通独立任务进入 PiP，已验证系统放大可恢复 Activity 并转入控制页。参见[接口定义](https://developer.android.com/reference/android/app/ActivityOptions#makeLaunchIntoPip(android.app.PictureInPictureParams))。

帧流最多 10fps。PiP 可见时以预览租约抓帧，不能用 `RESUMED` 作为唯一条件，因为画中画通常处于暂停状态。贴边收起保留最后一帧并释放租约；恢复显示后继续抓帧。关闭预览、全屏查看、锁屏或任务结束时停止 PiP 抓帧，继续遵守原有虚拟 display 保留规则。`onPictureInPictureUiStateChanged()` 的 `isStashed` 是首选依据；OriginOS 实测会把窗口移出屏幕但不报告收起，因此也检查窗口横向可见范围，每 250ms 更新一次，收起时不请求新图像。参见[收起状态接口](https://developer.android.com/reference/android/app/PictureInPictureUiState#isStashed())。

宽高比限制在 1:2.39 至 2.39:1。手机竖屏时，横屏虚拟内容沿用顺时针 90 度的适配规则；手机横屏时按原有方向显示。参见 [PiP 参数接口](https://developer.android.com/reference/android/app/PictureInPictureParams.Builder)。

启动请求携带唯一标识，并与当前 run 和 display session 对照。设置关闭、任务结束或 session 更换后，迟到的 Activity 回调不能重新获取预览租约或关闭新窗口。同一 run 的用户关闭状态会保留到预览设置主动变更或新的 run 开始。

## 验证

2026-10-11：vivo V2419A，Android 16 / OriginOS 6，KernelSU + LSPosed，USB 调试。设备报告 `android.software.picture_in_picture=true`。

- Debug 与 Android 测试 APK 构建通过；全量 JVM 测试 1,813 项，无失败或错误，2 项跳过。
- Lint：0 错误，405 个警告，1 个提示；新增 PiP 文件没有 Lint 警告。
- `pip` 真机验证 61 项通过，覆盖其他应用前台时启动、独立任务、实时帧与 10fps 上限、贴边停帧和恢复、系统放大与关闭按钮、查看页返回、样式切换、开关关闭、用户关闭后不重弹、任务结束保留 display，以及通过实际设置菜单选择两种样式。
- `floating` 真机回归 34 项通过，覆盖拖动贴边、折叠停帧、10fps 上限、查看页返回、预览开关与任务结束回收。系统敏感设置页会主动隐藏悬浮窗，测试先回到桌面，尊重系统窗口保护策略。
- JVM 回归覆盖默认值与设置持久化、设备能力和画中画权限、熄屏和锁屏判断、迟到回调、单次启动、超时回退及 run 内关闭状态。锁屏判断已作单元验证，本次画中画真机测试在解锁状态进行。

运行原生预览验证：

```sh
adb -s <device> shell am instrument -w -e mode pip io.github.mangi.eta.test/io.github.mangi.eta.validation.DeviceValidationRunner
```

测试使用独立构造应用，不调用付费模型；设置备份包含预览样式，正常结束或恢复中断测试时均还原原有选择。原有悬浮窗测试先回到桌面，避免系统敏感设置页主动隐藏悬浮窗导致误判。
