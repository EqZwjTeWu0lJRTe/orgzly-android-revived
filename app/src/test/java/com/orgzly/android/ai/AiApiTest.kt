package com.orgzly.android.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiApiTest {

    @Test
    fun chatEndpoint_appendsSuffix() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", AiApi.chatEndpoint("https://api.deepseek.com/v1"))
        assertEquals("https://x/chat/completions", AiApi.chatEndpoint("https://x/chat/completions"))
    }

    @Test
    fun parsesObjectArrayWithContent() {
        val raw = """[{"title":"调研阶段","content":"了解现状"},{"title":"开发阶段"},{"title":"验收阶段","description":"对照清单逐项检查"}]"""
        assertEquals(
            listOf(
                AiSubtask("调研阶段", "了解现状"),
                AiSubtask("开发阶段", null),
                AiSubtask("验收阶段", "对照清单逐项检查")
            ),
            AiApi.parseSubtasks(raw)
        )
    }

    @Test
    fun parsesPlainStringArray() {
        val titles = AiApi.parseSubtasks("""["调研阶段","开发阶段","验收阶段"]""")
        assertEquals(
            listOf(AiSubtask("调研阶段"), AiSubtask("开发阶段"), AiSubtask("验收阶段")),
            titles
        )
    }

    @Test
    fun parsesArrayInsideFences() {
        val raw = "```json\n[{\"title\":\"a\"},{\"title\":\"b\",\"content\":\"note\"}]\n```"
        assertEquals(listOf(AiSubtask("a"), AiSubtask("b", "note")), AiApi.parseSubtasks(raw))
    }

    @Test
    fun parsesWrappedSubtasksObject() {
        val raw = """{"subtasks": [{"title": "x", "content": "c1"}, {"title": "y"}]}"""
        assertEquals(listOf(AiSubtask("x", "c1"), AiSubtask("y")), AiApi.parseSubtasks(raw))
    }

    @Test
    fun fallsBackToLineParsing() {
        val raw = """"first"
"second"
"third""""
        assertEquals(
            listOf(AiSubtask("first"), AiSubtask("second"), AiSubtask("third")),
            AiApi.parseSubtasks(raw)
        )
    }
}
