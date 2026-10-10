# swipe 连续路径

`swipe` 可选择直线起终点，或单指连续经过的多点路径。整条路径只按下一次、抬起一次，各段之间不会抬手。

```json
{
  "coordinate_space": "normalized",
  "path": [
    {"x": 200, "y": 300},
    {"x": 800, "y": 300},
    {"x": 800, "y": 700},
    {"x": 200, "y": 700}
  ],
  "duration_ms": 1200
}
```

- `path` 包含 2–64 个 `{x, y}` 整数坐标点，按给定顺序连接成折线，允许回到起点。
- `duration_ms` 是整条路径的总时长，默认 500ms，范围 100–2000ms；各段按长度分配时间。
- 所有点共用必填的 `coordinate_space`：`normalized` 为 0–999，`screen` 为当前屏幕像素，`screenshot` 为最近一次附图的原始像素。
- 原有 `x1/y1/x2/y2` 参数继续支持；它们与 `path` 互斥。
- 主屏使用无障碍连续手势；仅在无障碍未派发手势时才允许 Root 后备输入。Root 后备和虚拟屏输入均通过同一个持续触摸执行器连接各点，不用多个直线 swipe 拼接。
- 虚拟屏仍要求本轮的新观察，并校验全部路径点及显示尺寸、旋转。普通 `swipe` 返回操作后的观察；`ok=true` 仅表示输入完成，需据观察确认应用效果。超时或不确定结果不会重放。
- 主屏指示器和虚拟屏查看器显示实际折线路径。停滞保护会比较中间点，避免将起终点相同的不同路径误判为同一操作。

低层 `virtual_screen` 的 `action="swipe"` 也接受 `path`，使用虚拟屏像素坐标及 `durationMs`。会话通常应使用普通 `swipe`，以获得坐标转换和操作后的观察。

## 验证

- `testDebugUnitTest`：1787 项，0 失败、2 项环境相关跳过；覆盖路径解析、坐标越界、工具 Schema、权限、停滞预算和连续输入的取消行为。
- `assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease`、`lintDebug` 通过（Lint 无错误）。
- vivo V2419A / Android 16 / OriginOS 6 / Root 真机：`swipe_path` 模式 64 项检查通过，覆盖主屏无障碍、主屏 Root、虚拟屏 normalized 路径、低层像素路径及横屏路径；均核对一次 DOWN/UP、相同 downTime 和有序经过各点。
- Release APK 经 R8 优化后，直接在手机上运行保留的 Root 入口，核对输入结果与测试页面实际接收的触摸轨迹。
