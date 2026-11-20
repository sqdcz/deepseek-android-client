package com.shipyard.deepchat.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Citation(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val site: String = ""
)

@Serializable
data class ToolTrace(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = "search",
    val label: String = "",
    val status: String = "running",
    val sources: List<Citation> = emptyList()
)

@Serializable
data class Msg(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val reasoning: String = "",
    val images: List<String> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val tools: List<ToolTrace> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val error: String? = null,
    val durationMs: Long = 0L,
    val stopped: Boolean = false,
    /** 原样保存 server_tool_calls，多轮时 DeepSeek 要求完整回传 */
    val rawTools: String = "",
    val rawAnnotations: String = "",
    /** 分支树：父节点 id，根为 null */
    val parentId: String? = null,
    /** 分支树：当前选中的子节点，多版本时指向其中一个 */
    val selectedChildId: String? = null
) {
    val isUser: Boolean get() = role == "user"
}

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新对话",
    val messages: List<Msg> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val providerId: String = "",
    val model: String = "",
    val pinned: Boolean = false,
    val thinking: Boolean = true,
    val effort: String = "high",
    val webSearch: Boolean = false,
    val systemPrompt: String = "",
    /** 分支树根节点；null 表示仍是老的线性结构 */
    val rootId: String? = null
) {
    val preview: String
        get() = messages.lastOrNull()?.let { m ->
            (m.content.ifBlank { m.reasoning }).replace("\n", " ").take(80)
        } ?: ""
}

@Serializable
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新服务",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val models: List<String> = listOf("gpt-4o-mini"),
    val defaultModel: String = "gpt-4o-mini",
    val kind: String = "openai",
    val supportsThinking: Boolean = false,
    val supportsWeb: Boolean = false,
    val supportsVision: Boolean = false,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    val isDeepSeek: Boolean get() = kind == "deepseek"
    val chatUrl: String
        get() {
            val b = baseUrl.trimEnd('/')
            return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
        }
}

@Serializable
data class WebDavConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val user: String = "",
    val pass: String = "",
    val path: String = "/deepchat",
    val autoSync: Boolean = false,
    val lastSync: Long = 0L
)

@Serializable
data class DayUsage(val tokens: Int = 0, val calls: Int = 0)

/** 没设置过提示词时用的默认人设 */
const val DEFAULT_SYSTEM_PROMPT =
    "你是 DeepSeek，一个由深度求索开发的 AI 助手。回答简洁、准确、直接，默认使用中文。"

@Serializable
data class Settings(
    val theme: String = "system",
    val fontSize: Float = 16f,
    val showReasoning: Boolean = true,
    val haptics: Boolean = true,
    val sendKey: String = "enter",
    val autoSpeak: Boolean = false,
    /** system = 手机自带 TTS；api = DeepSeek /v1/tools/tts */
    val speakMode: String = "system",
    /** API 朗读音色：stella（女）/ tide（男） */
    val voiceId: String = "stella",
    /** 系统 TTS 的引擎音色名，空表示用默认 */
    val systemVoice: String = "",
    val contextLimit: Int = 1_000_000,
    /** 全局系统提示词，会话没有单独设置时用它 */
    val globalSystemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** 长期记忆条目，每次请求会附在系统提示词后面 */
    val memories: List<String> = emptyList(),
    /** 语音识别引擎：system 系统自带 / vosk 本地模型 / api DeepSeek 接口 */
    val asrEngine: String = "system",
    /** 识别完直接填进输入框，否则（false）自动发送 */
    val asrAutoFill: Boolean = true,
    /** 每日用量，key 是 yyyy-MM-dd */
    val dailyUsage: Map<String, DayUsage> = emptyMap(),
    val activeProviderId: String = DEFAULT_PROVIDER_ID,
    val providers: List<Provider> = defaultProviders(),
    val webdav: WebDavConfig = WebDavConfig(),
    val showTokenUsage: Boolean = true
) {
    val activeProvider: Provider
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.first()

    companion object {
        const val DEFAULT_PROVIDER_ID = "deepseek-official"

        fun defaultProviders(): List<Provider> = listOf(
            Provider(
                id = DEFAULT_PROVIDER_ID,
                name = "DeepSeek 官方",
                baseUrl = "https://api.deepseek.com",
                apiKey = "",
                models = listOf("deepseek-flash", "deepseek-v4-pro"),
                defaultModel = "deepseek-flash",
                kind = "deepseek",
                supportsThinking = true,
                supportsWeb = true,
                supportsVision = true
            )
        )
    }
}
