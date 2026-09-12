package com.orgzly.android.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A generated subtask: a title plus an optional short body/description. */
data class AiSubtask(
    val title: String,
    val content: String? = null
)

/** Raised for any recoverable AI failure (network, HTTP, parsing). */
class AiException(message: String) : Exception(message)

object AiApi {
    private const val REQUEST_TIMEOUT_MS = 30_000
    private const val LONG_REQUEST_TIMEOUT_MS = 120_000
    private const val MAX_RETRIES = 3

    fun chatEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "API 地址未配置" }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /**
     * Calls an OpenAI-compatible chat API and asks for [count] subtasks for [taskTitle].
     * Blocks the calling thread. Throws [AiException] on failure.
     */
    fun requestSubtasks(
            baseUrl: String,
            apiKey: String,
            model: String,
            taskTitle: String,
            count: Int,
            context: String? = null,
            hint: String? = null
    ): List<AiSubtask> {
        if (apiKey.isBlank()) throw AiException("请先在设置中配置 API Key")
        if (baseUrl.isBlank()) throw AiException("请先在设置中配置 API 地址")
        if (taskTitle.isBlank()) throw AiException("任务标题为空")

        val prompt = buildPrompt(taskTitle, count, context, hint)

        val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.7)
                .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system")
                                .put("content", "你是任务分解专家。只输出 JSON 字符串数组，不要其它内容。"))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                .toString()

        var lastError: Exception? = null

        for (attempt in 1..MAX_RETRIES) {
            try {
                val content = postChat(baseUrl, apiKey, model, body)
                val titles = parseSubtasks(content)
                if (titles.isNotEmpty()) {
                    return titles.take(count.coerceIn(1, 20))
                }
                lastError = AiException("AI 返回内容无法解析，请重试")
            } catch (e: Exception) {
                lastError = e
                if (e is AiException && e.message?.contains("401") == true) break
            }
        }

        throw AiException(lastError?.message ?: "AI 请求失败")
    }

    /** Sends a tiny request just to verify base URL / key / model. */
    fun test(baseUrl: String, apiKey: String, model: String) {
        val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 8)
                .put("messages", JSONArray()
                        .put(JSONObject().put("role", "user").put("content", "回复 OK")))
                .toString()

        postChat(baseUrl, apiKey, model, body)
    }

    /**
     * Asks the model to polish a whole Org outline (headings + body) and returns the complete
     * Org text. No count limit / no JSON-array constraint here.
     */
    fun polish(
            baseUrl: String,
            apiKey: String,
            model: String,
            outline: String,
            hint: String?
    ): String {
        if (apiKey.isBlank()) throw AiException("请先在设置中配置 API Key")
        if (baseUrl.isBlank()) throw AiException("请先在设置中配置 API 地址")
        if (outline.isBlank()) throw AiException("内容为空")

        val hintPart = hint?.takeIf { it.isNotBlank() }?.let { "额外要求：$it\n\n" } ?: ""
        val prompt = """
            请完善下面的 Org 大纲。要求：
            1. 保留 '*' 层级结构和 TODO/DONE 关键字，不要合并或删减有意义的条目
            2. 只对确有必要之处做润色/补全；不要为每个节点长篇扩写正文
            3. 整体输出尽量精炼，与原文长度大致相当
            4. 只输出完善后的 Org 大纲纯文本，不要输出任何解释或代码块标记

            ${hintPart}以下是需要完善的大纲：
            $outline
        """.trimIndent()

        val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.6)
                .put("max_tokens", 4096)
                .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system")
                                .put("content", "你是大纲/任务写作助手，只输出 Org 大纲纯文本。"))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                .toString()

        var lastError: Exception? = null
        for (attempt in 1..2) {
            try {
                val content = postChat(baseUrl, apiKey, model, body, LONG_REQUEST_TIMEOUT_MS).trim()
                val cleaned = content
                        .removePrefix("```")
                        .removePrefix("org")
                        .removePrefix("text")
                        .trim()
                        .removeSuffix("```")
                        .trim()
                if (cleaned.isNotEmpty()) return cleaned
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw AiException(lastError?.message ?: "AI 完善失败")
    }

    private fun buildPrompt(taskTitle: String, count: Int, context: String?, hint: String?): String {
        val examples = """[
            {"title": "收集本周数据", "content": "汇总并核对各来源数据"},
            {"title": "分析关键指标", "content": "找出偏差与根因"},
            {"title": "撰写周报正文", "content": ""},
            {"title": "发送给团队", "content": ""}]""".trimIndent()

        val contextPart = context?.takeIf { it.isNotBlank() }?.let {
            """
            背景信息（该任务在计划中的位置与说明，仅作参考，不要重复它们）：
            $it
            """.trimIndent()
        }?.let { "$it\n\n" } ?: ""

        val hintPart = hint?.takeIf { it.isNotBlank() }?.let {
            """
            额外要求：$it
            """.trimIndent()
        }?.let { "$it\n\n" } ?: ""

        return """
            请把下面的任务分解为 ${count.coerceIn(1, 20)} 个具体的、可执行的一层子任务。

            规则：
            1. 只分解一层，不要递归
            2. 每个子任务标题简洁明确
            3. 子任务之间逻辑清晰、互不重叠
            4. 只返回 JSON 数组；每个元素是对象 {"title": "子任务标题", "content": "一句话说明或执行要点(可以省略或为空字符串)"}
            5. 不要输出 JSON 以外的任何文字

            示例输入：完成项目周报
            示例输出：$examples

            ${contextPart}${hintPart}任务：$taskTitle
        """.trimIndent()
    }

    private fun postChat(
            baseUrl: String,
            apiKey: String,
            model: String,
            body: String,
            timeoutMs: Int = REQUEST_TIMEOUT_MS): String {
        val endpoint = chatEndpoint(baseUrl)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "application/json")

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = readStream(connection.errorStream).take(300)
                throw AiException("HTTP $code: $detail")
            }

            val json = JSONObject(readStream(connection.inputStream))
            val content = json
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?: throw AiException("响应缺少 choices[0].message.content")

            if (content.isBlank()) {
                throw AiException("AI 返回为空")
            }

            return content
        } finally {
            connection.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return ByteArrayOutputStream().use { out ->
            stream.use { input ->
                input.copyTo(out)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    /**
     * Parses subtasks out of the model's content. Accepts:
     * - an array of objects {"title": "...", "content": "..."},
     * - an array of plain strings (title only),
     * - either wrapped in a {"subtasks": [...]} object,
     * - or a plain line list as a last resort.
     */
    fun parseSubtasks(content: String): List<AiSubtask> {
        val cleaned = content.trim()

        // 1. Prefer a raw JSON array anywhere in the text.
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val array = JSONArray(cleaned.substring(start, end + 1))
                val result = parseArray(array)
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {
                // fall through
            }
        }

        // 2. Some models wrap it as {"subtasks": [...]}
        try {
            val obj = JSONObject(cleaned)
            obj.optJSONArray("subtasks")?.let {
                val result = parseArray(it)
                if (result.isNotEmpty()) return result
            }
        } catch (_: Exception) {
            // fall through
        }

        // 3. Fall back to line-based list parsing (title only).
        return cleaned.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("```") && it != "[" && it != "]" }
                .mapNotNull { line ->
                    val title = line.trim('"', ',', ' ', '\t')
                    if (title.isEmpty()) null else AiSubtask(title)
                }
    }

    private fun parseArray(array: JSONArray): List<AiSubtask> {
        val result = ArrayList<AiSubtask>(array.length())

        for (i in 0 until array.length()) {
            val item = array.opt(i) ?: continue

            when (item) {
                is String -> {
                    val title = item.trim()
                    if (title.isNotEmpty()) result.add(AiSubtask(title))
                }
                is JSONObject -> {
                    val title = item.optString("title", item.optString("name", "")).trim()
                    if (title.isNotEmpty()) {
                        val content = item.optString("content", item.optString("description", ""))
                                .trim()
                                .takeIf { it.isNotEmpty() }
                        result.add(AiSubtask(title, content))
                    }
                }
            }
        }

        return result
    }
}
