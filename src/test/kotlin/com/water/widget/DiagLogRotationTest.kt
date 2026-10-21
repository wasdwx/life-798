package com.water.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 日志落盘的边界行为：滚动、裁头、上限。
 * 这些是纯 JVM 类（不碰 Android API），所以可以直接测。
 */
class DiagLogRotationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun rotatingLogKeepsOnlyConfiguredFileCount() {
        val dir = tmp.newFolder("diag")
        val log = RotatingLog(dir, "diag.", maxBytes = 100, count = 3)

        // 每行 8 字节、上限 100 字节 → 反复触发滚动
        repeat(60) { log.append(("line-" + it + "\n").toByteArray()) }

        val existing = log.files().filter { it.exists() }
        assertTrue("至少当前文件存在", existing.isNotEmpty())
        assertTrue("不能超过配置的文件数", existing.size <= 3)
        // 最新的内容一定在 diag.0.log 里
        assertTrue(log.file(0).readText().contains("line-59"))
        // 最旧的已经被滚动掉
        assertFalse(log.file(0).readText().contains("line-0\n"))
    }

    @Test fun rotatingLogClearRemovesEverything() {
        val dir = tmp.newFolder("diag2")
        val log = RotatingLog(dir, "diag.", maxBytes = 1000, count = 2)
        log.append("hello\n".toByteArray())
        assertTrue(log.file(0).exists())
        log.clear()
        assertFalse(log.file(0).exists())
        assertEquals(0L, log.totalBytes())
    }

    @Test fun truncatingLogTrimsFromHeadAndKeepsRecent() {
        val file = File(tmp.newFolder("err"), "diag-error.log")
        val log = TruncatingLog(file, maxBytes = 200, keepRatio = 0.75)

        repeat(50) { log.append(("error-" + it + "\n").toByteArray()) }

        assertTrue("文件不能无限增长", file.length() <= 400)
        val text = file.readText()
        // 最近的一条必须还在
        assertTrue(text.contains("error-49"))
        // 最旧的应该已经被裁掉
        assertFalse(text.contains("error-0\n"))
    }

    @Test fun truncatingLogSurvivesRepeatedOverflow() {
        val file = File(tmp.newFolder("err2"), "diag-error.log")
        val log = TruncatingLog(file, maxBytes = 128)
        repeat(200) { log.append(("x" + it + "\n").toByteArray()) }
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        assertTrue(file.length() <= 256)
    }
}
