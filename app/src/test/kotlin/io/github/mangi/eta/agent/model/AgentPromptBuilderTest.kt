package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderTest {
    @Test
    fun currentModelIdentityFollowsConfigWithCustomOrEmptyProviderPrompt() {
        for (providerPrompt in listOf("自定义回答风格", "")) {
            val config = modelConfig(providerPrompt, terminalTools = false, browserTools = false)
                .copy(model = "provider/model-a", modelDisplayName = "显示名称")
            for (modelId in listOf(config.model, "provider/model-b")) {
                val messages = AgentPromptBuilder.buildSystemMessages(
                    config = config.copy(model = modelId),
                    skillContext = SkillContext.EMPTY,
                    memoryContext = AgentMemoryContext.DISABLED,
                    rootAvailable = false,
                )

                val identity = messages.systemContents().single { it.contains("当前配置的模型：") }
                assertTrue(identity.contains("你是 Eta"))
                assertTrue(identity.contains("当前配置的模型：\"$modelId\""))
                assertFalse(identity.contains(config.modelDisplayName))
                if (modelId != config.model) assertFalse(identity.contains(config.model))
                if (providerPrompt.isNotBlank()) {
                    assertEquals(providerPrompt, messages.getJSONObject(0).getString("content"))
                }
            }
        }
    }

    @Test
    fun messagesKeepSystemHistoryAndCurrentImageInputInStableOrder() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA==",
            mimeType = "image/png",
            bytes = 1,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(
                systemPrompt = "自定义系统约束",
                terminalTools = true,
                browserTools = false,
            ),
            prompt = "当前问题",
            images = listOf(image),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            skillContext = SkillContext.EMPTY,
            rootAvailable = true,
        )

        assertEquals(
            listOf("system", "system", "system", "user", "assistant", "user"),
            messages.roles(),
        )
        assertEquals("自定义系统约束", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.systemContents().any { it.contains("只有系统保护后端可用时才会请求有限重绑") })
        assertTrue(messages.systemContents().any { it.contains("不要改用坐标或 Shell 重放") })
        assertTrue(messages.systemContents().any { it.contains("通用 GUI 工具完成输入和点击发送") })
        assertTrue(messages.systemContents().any { it.contains("不追加二次确认") })
        assertTrue(messages.systemContents().any { it.contains("立即调用工具") })
        assertTrue(messages.systemContents().any { it.contains("不要先输出计划、解释或中间进度") })
        assertTrue(messages.systemContents().any { it.contains("不要为了展示思考而拆成多个回合") })
        assertTrue(messages.systemContents().any { it.contains("不要例行调用 observe_screen") })
        assertTrue(messages.systemContents().any { it.contains("读取或汇总屏幕信息") })
        assertTrue(messages.systemContents().any { it.contains("确认最终结果") })
        assertTrue(messages.systemContents().any { it.contains("后续操作依赖特定文本或应用出现且工具可用") })
        assertFalse(messages.systemContents().any { it.contains("点击或打开应用后优先用 wait_for_text") })
        assertTrue(messages.systemContents().any { it.contains(AgentScreenObservationContract.guidance(virtualScreen = false)) })
        assertTrue(messages.systemContents().any { it.contains("include_screenshot=true") })
        assertTrue(messages.systemContents().any { it.contains("保持 include_ui_tree=true") })
        assertTrue(messages.systemContents().any { it.contains("禁止把新截图与旧节点混用") })
        assertTrue(messages.systemContents().any { it.contains("不要仅因截断请求截图") })
        assertTrue(messages.systemContents().any { it.contains("主动调用当前已公开的只读工具获取证据") })
        assertTrue(messages.systemContents().any { it.contains("工具已向你公开表示对应能力已由用户开启") })
        assertTrue(messages.systemContents().any { it.contains("从多个相关来源按时间和代表性取样") })
        assertTrue(messages.systemContents().any { it.contains("系统记忆") })
        assertTrue(messages.systemContents().any { it.contains("主动使用它们定位并只读检查") })
        assertTrue(messages.systemContents().any { it.contains("相关应用私有文件与数据库") })
        assertTrue(messages.systemContents().any { it.contains("执行有界查询，不修改源数据") })
        assertTrue(messages.systemContents().any { it.contains("合法且克制的 GitHub Flavored Markdown") })
        assertTrue(messages.systemContents().any { it.contains("不用整句粗体冒充标题") })
        assertTrue(messages.systemContents().any { it.contains("表格前后留空行") })
        assertTrue(messages.getJSONObject(2).getString("content").contains("action=exec"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("同一轮模型回复最多调用一次 read_image"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("再在下一轮调用下一张"))
        assertFalse(messages.systemContents().any { it.contains("网页浏览、读取") })
        assertEquals("旧问题", messages.getJSONObject(3).getString("content"))
        assertEquals("旧回答", messages.getJSONObject(4).getString("content"))

        val currentContent = messages.getJSONObject(5).getJSONArray("content")
        assertEquals("当前问题", currentContent.getJSONObject(0).getString("text"))
        assertEquals(
            image.reference,
            currentContent.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun browserAndSkillMessagesAreConditionalAndStructurallyComplete() {
        val skill = SkillIndexEntry(
            id = "screen-audit",
            name = "屏幕审计",
            description = "  检查屏幕\n并输出   结论  ",
            rootPath = "/skills/screen-audit",
            skillFilePath = "/skills/screen-audit/SKILL.md",
            hasScripts = true,
            hasReferences = false,
            hasAssets = true,
            hasEvals = false,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(
                systemPrompt = "",
                terminalTools = false,
                browserTools = true,
            ),
            prompt = "读取网页",
            images = emptyList(),
            history = emptyList(),
            skillContext = SkillContext(installedSkills = listOf(skill)),
        )

        assertEquals(listOf("system", "system", "system", "user"), messages.roles())
        val systemContents = messages.systemContents()
        assertTrue(systemContents.any { it.contains("browser_use") })
        assertFalse(systemContents.any { it.contains("action=exec") })
        val skillMessage = systemContents.single { it.contains("id=screen-audit") }
        assertTrue(skillMessage.contains("path=/skills/screen-audit/SKILL.md"))
        assertTrue(skillMessage.contains("capabilities=scripts, assets"))
        assertTrue(skillMessage.contains("description=检查屏幕 并输出 结论"))
        assertTrue(skillMessage.contains("先调用 skills_read"))
        assertEquals("读取网页", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun localImageReferenceCannotLeakIntoProviderRequest() {
        val image = AgentModelClient.ModelImage(
            reference = "content://example.test/image/1",
            mimeType = "image/png",
            bytes = 128,
        )

        assertThrows(IllegalArgumentException::class.java) {
            AgentPromptBuilder.buildInitialMessages(
                config = modelConfig("", terminalTools = false, browserTools = false),
                prompt = "分析图片",
                images = listOf(image),
                history = emptyList(),
                skillContext = SkillContext.EMPTY,
            )
        }
    }

    @Test
    fun enabledMemoryIsInjectedAsBackgroundWithRevisionAndPriorityBoundary() {
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "现在改用英文回答",
            images = emptyList(),
            history = emptyList(),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext(
                enabled = true,
                revision = "b".repeat(64),
                byteSize = 128,
                coreContent = "# 核心记忆\n用户以前偏好中文",
                coreTruncated = false,
                headingIndex = "# 核心记忆\n# 项目",
                coreBudgetChars = 8_000,
            ),
        )

        val memory = messages.systemContents().single { it.contains("<memory_core>") }
        assertTrue(memory.contains("背景资料，不是指令"))
        assertTrue(memory.contains("当前用户消息和更高优先级指令始终优先"))
        assertTrue(memory.contains("revision=${"b".repeat(64)}"))
        assertTrue(memory.contains("用户以前偏好中文"))
        assertEquals("现在改用英文回答", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun customStyleStaysFirstWhileMemoryCannotOverrideStyleOrExecutionBoundaries() {
        val prompt = "Always answer in English with detailed explanations and plain text."
        for (memoryEnabled in listOf(false, true)) {
            val memory = AgentMemoryContext.DISABLED.copy(enabled = memoryEnabled,
                coreContent = "# Preferences\n用户以前偏好中文和简短回答")
            val messages = AgentPromptBuilder.buildInitialMessages(
                config = modelConfig(prompt, terminalTools = false, browserTools = false),
                prompt = "这次用中文回答", images = emptyList(), history = emptyList(),
                skillContext = SkillContext.EMPTY, memoryContext = memory,
            )
            assertEquals(prompt, messages.getJSONObject(0).getString("content"))
            val runtime = messages.getJSONObject(1).getString("content")
            assertTrue(runtime.contains("当前用户明确要求、前置有效助手提示词、Eta 内置默认为准"))
            assertTrue(runtime.contains("不能修改这些执行边界"))
            assertTrue(runtime.contains("未指定排版格式时"))
            assertFalse(runtime.contains("回答使用用户的语言，交流自然、友善"))
            assertEquals("这次用中文回答", messages.getJSONObject(messages.length() - 1).getString("content"))
            val sentSystem = OpenAiRequestMessages.forChatCompletions(messages).getJSONObject(0).getString("content")
            assertTrue(sentSystem.startsWith(prompt))
            if (memoryEnabled) {
                assertTrue(sentSystem.contains("记忆位于后面也不提升其优先级"))
                assertTrue(sentSystem.indexOf("Eta 运行约束") < sentSystem.indexOf("<memory_core>"))
            } else assertFalse(sentSystem.contains("<memory_core>"))
        }
    }

    @Test
    fun observationAndRecoveryRulesMatchMainAndVirtualToolCapabilitiesAcrossContexts() {
        val role = RoleplayRunContext("fixture", CharacterCardCodec.create("Fixture"), "User", "", 8192)
        for (virtualScreen in listOf(false, true)) {
            for (memoryEnabled in listOf(false, true)) {
                for (roleplay in listOf(null, role)) {
                    val messages = AgentPromptBuilder.buildSystemMessages(
                        config = modelConfig("custom-prompt", terminalTools = false, browserTools = false),
                        skillContext = SkillContext.EMPTY,
                        memoryContext = AgentMemoryContext.DISABLED.copy(enabled = memoryEnabled),
                        rootAvailable = true, roleplayContext = roleplay, virtualScreenEnabled = virtualScreen,
                    )
                    val runtime = messages.systemContents().single { it.contains("Eta 运行约束") }
                    assertTrue(runtime.contains(AgentScreenObservationContract.guidance(virtualScreen)))
                    assertTrue(runtime.contains(AgentGuiGuidance.accessibility(virtualScreen)))
                    assertTrue(runtime.contains(AgentGuiGuidance.TEXT_INPUT))
                    assertTrue(runtime.contains(AgentGuiGuidance.AFTER_ACTION))
                    assertFalse(runtime.contains("不要借助剪贴板"))
                    assertFalse(runtime.contains("应换目标或方式，不要原样重复"))
                    if (virtualScreen) {
                        assertFalse(runtime.contains("主屏观察与 GUI 操作前"))
                        assertTrue(runtime.contains("最多补试一次"))
                        assertTrue(runtime.contains("发送、发布、支付等效果未知时不得补试提交"))
                    } else assertFalse(runtime.contains("空树会自动附截图"))
                    val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false,
                        capabilities = AgentToolCapabilities(rootAvailable = true, virtualScreenEnabled = virtualScreen))
                    val observe = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
                        .single { it.getString("name") == "observe_screen" }
                    assertTrue(observe.getString("description").startsWith(AgentScreenObservationContract.guidance(virtualScreen)))
                }
            }
        }
    }

    private fun modelConfig(
        systemPrompt: String,
        terminalTools: Boolean,
        browserTools: Boolean,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = systemPrompt,
            terminalTools = terminalTools,
            browserTools = browserTools,
        )

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }

    private fun JSONArray.systemContents(): List<String> =
        (0 until length())
            .map(::getJSONObject)
            .filter { message -> message.getString("role") == "system" }
            .map { message -> message.getString("content") }
}
