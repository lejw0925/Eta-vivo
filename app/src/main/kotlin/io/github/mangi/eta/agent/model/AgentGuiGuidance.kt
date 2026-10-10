package io.github.mangi.eta.agent.model

/** 系统提示词、工具目录和动作结果共同使用的 GUI 规则；权限仍由执行器校验。 */
internal object AgentGuiGuidance {
    const val TEXT_INPUT =
        "输入文本优先用 type_text：指定 index 可直接写入输入框，不必先点击；要搜索或发送时设 submit=true。" +
            "中文、emoji 和长文本直接传入；工具内部可使用 Root 粘贴，这不要求模型先调用剪贴板工具。" +
            "用户明确要求复制或设置剪贴板时使用 set_clipboard；输入工具明确要求粘贴且当前能力支持时才准备剪贴板并粘贴。" +
            "输入失败或提交效果未知时先重新观察，不能直接粘贴或重复输入；submitted=false 表示已写入但未提交，核对后点击发送按钮。"

    const val CLIPBOARD_DESCRIPTION =
        "把文本写入系统剪贴板，用于用户明确要求复制或设置剪贴板的任务。一般输入优先 type_text；" +
            "只有输入工具明确要求粘贴且当前能力支持时才用于准备粘贴，不能绕过输入失败或结果未知的核验。"

    const val UNCHANGED_AFTER_ACTION =
        "采样 UI 节点与应用窗口未变化，不能据此判定动作失败或应用卡死；先核对或有界等待，" +
            "确认未命中的普通点击才可有限补试，发送、发布、支付等提交结果不明确时只读核对。"

    const val UNKNOWN_AFTER_ACTION =
        "缺少可比较的 UI 节点，界面变化未知；根据新的节点或已有的新截图核对效果，不足时重新观察，不直接重放动作。"

    const val AFTER_ACTION =
        "先读 after 核对实际效果；screen_changed 只比较应用、窗口与采样 UI 节点，true 不证明业务完成，false 不代表动作失败，null 表示证据不足。" +
            UNCHANGED_AFTER_ACTION +
            "正在加载时按目标有界等待；节点工具不可用时不能调用 wait_for_text，改为短暂等待后观察截图。" +
            "确认未命中且不会重复提交的普通点击，可用新观察最多补试一次；受工具的重试预算约束，progress.warning=true 时停止原样重试，重新定位或换路径。" +
            "发送、发布、支付等效果未知时不得补试提交；不要循环等待、观察或微调坐标规避保护。"

    fun accessibility(virtualScreen: Boolean): String =
        if (virtualScreen) {
            "虚拟屏的节点操作依赖无障碍，坐标和焦点文本可使用独立 Root 后端。" +
                "节点工具返回 ACCESSIBILITY_UNAVAILABLE 或 VIRTUAL_UI_TREE_UNAVAILABLE 且没有保护性暂停时，" +
                "先用 observe_screen 获取同一虚拟屏的新截图；仅在 Root 授权仍有效且工具已公开时，改用虚拟屏坐标或不带 index 的 type_text。" +
                "ACCESSIBILITY_PROTECTION_UNAVAILABLE、ACCESSIBILITY_REPAIR_TIMEOUT 或其他保护性拒绝时停止动作，不绕过保护；" +
                "权限撤销、保护性暂停或动作结果未知时，按对应恢复与核验规则处理，不能借助 Shell 重放或操作主屏。"
        } else {
            "主屏观察与 GUI 操作前会确认 Eta 无障碍服务；只有系统保护后端可用时才会请求有限重绑。" +
                "若主屏工具返回 ACCESSIBILITY_UNAVAILABLE、ACCESSIBILITY_PROTECTION_UNAVAILABLE 或 ACCESSIBILITY_REPAIR_TIMEOUT，" +
                "说明动作未执行，不要改用坐标或 Shell 重放 GUI 动作。"
        }
}
