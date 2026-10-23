package com.water.widget

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class DiagLevel { ERROR, WARN, INFO, DEBUG }

/**
 * 滚动日志：diag.0.log 为当前文件，写满后 diag.0 -> diag.1 -> diag.2，最旧的被删除。
 * 纯 JVM 实现（只依赖 java.io.File），可以直接单元测试。
 */
class RotatingLog(
    private val dir: File,
    private val prefix: String,
    private val maxBytes: Long,
    private val count: Int,
) {
    init {
        require(count >= 1)
        require(maxBytes > 0)
    }

    var size: Long = file(0).length()
        private set

    fun file(index: Int): File = File(dir, prefix + index + ".log")

    fun files(): List<File> = (0 until count).map { file(it) }

    @Synchronized
    fun append(bytes: ByteArray) {
        if (size + bytes.size > maxBytes) rotate()
        try {
            dir.mkdirs()
            file(0).appendBytes(bytes)
            size += bytes.size
        } catch (_: Throwable) {
        }
    }

    private fun rotate() {
        try {
            file(count - 1).delete()
            for (i in count - 2 downTo 0) file(i).renameTo(file(i + 1))
        } catch (_: Throwable) {
        }
        size = 0
    }

    @Synchronized
    fun clear() {
        for (i in 0 until count) {
            try { file(i).delete() } catch (_: Throwable) {}
        }
        size = 0
    }

    fun totalBytes(): Long = files().sumOf { try { it.length() } catch (_: Throwable) { 0L } }
}

/**
 * 单文件日志，超限时从头部裁到 [keepRatio]，保留最近的记录。
 * 用于「异常一定要保留」的场景：异常量很小，但绝不能因为滚动被整段丢掉。
 */
class TruncatingLog(
    private val file: File,
    private val maxBytes: Long,
    private val keepRatio: Double = 0.75,
) {
    var size: Long = file.length()
        private set

    @Synchronized
    fun append(bytes: ByteArray) {
        if (size + bytes.size > maxBytes) size = trim()
        try {
            file.parentFile?.mkdirs()
            file.appendBytes(bytes)
            size += bytes.size
        } catch (_: Throwable) {
        }
    }

    private fun trim(): Long = try {
        val text = file.readText()
        val keepChars = (maxBytes * keepRatio).toInt()
        var cut = text.length - keepChars
        if (cut < 0) cut = 0
        val newline = text.indexOf('\n', cut)
        val kept = if (newline >= 0) text.substring(newline + 1) else text.takeLast(keepChars)
        file.writeText(kept)
        kept.toByteArray(Charsets.UTF_8).size.toLong()
    } catch (_: Throwable) {
        0L
    }

    @Synchronized
    fun clear() {
        try { file.delete() } catch (_: Throwable) {}
        size = 0
    }

    fun current(): File = file
}

/**
 * 诊断日志。
 *
 * 开关：正式版默认**关闭**，debug 包默认**开启**；用户可在设置页随时开关。
 * 关闭时 [log] 第一行就返回，不格式化、不入队、不落盘 —— 零开销。
 *
 * 分级：
 *   ERROR 请求失败 / 异常，永远同时写入 diag-error.log（独立保留，不参与滚动）
 *   WARN  业务层异常（例如 code=-99）
 *   INFO  每个 HTTP 请求一行，**不解析响应体**
 *   DEBUG 详细模式：请求/响应体摘要
 *
 * 隐私：所有内容经 [AuthRedactor] 脱敏；默认只记长度，不记任何哈希。
 * 详细模式下才注入 HMAC tag（密钥只存本机，重装即失效）。
 */
object DiagLog {

    private const val TAG = "WaterDiag"
    private const val PREFS = "diag_log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VERBOSE = "verbose"
    private const val KEY_TAG_SECRET = "tag_secret"

    const val MAX_FILE_BYTES = 1024L * 1024
    const val FILE_COUNT = 3
    const val ERROR_FILE_BYTES = 512L * 1024
    private const val QUEUE_CAPACITY = 256
    const val ERROR_FILE_NAME = "diag-error.log"

    private class Entry(val level: DiagLevel, val at: Long, val text: String)

    /**
     * 同一个模板在短时间内反复出现时（例如整批重试连续 4 次同样的网络失败），
     * 只写第一行，收尾补一行「重复 N 次」。窗口 60 秒：跨窗口的重复仍会各写一次，
     * 便于看出「凌晨这次失败」和「中午那次」是不是同一类。
     */
    private const val DEDUPE_WINDOW_MS = 60_000L
    private var lastText: String? = null
    private var lastAt = 0L
    private var repeatCount = 0

    private val queue = ArrayBlockingQueue<Entry>(QUEUE_CAPACITY)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dropped = AtomicLong()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var enabled = false

    @Volatile
    private var verbose = false

    @Volatile
    private var worker: Thread? = null

    private var rotating: RotatingLog? = null
    private var errorLog: TruncatingLog? = null

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, BuildConfig.DEBUG)
        verbose = prefs.getBoolean(KEY_VERBOSE, false)
        rotating = RotatingLog(app.filesDir, "diag.", MAX_FILE_BYTES, FILE_COUNT)
        errorLog = TruncatingLog(File(app.filesDir, ERROR_FILE_NAME), ERROR_FILE_BYTES)
        applyTagger()
    }

    fun isEnabled(): Boolean = enabled
    fun isVerbose(): Boolean = verbose

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
        applyTagger()
        if (value) info("日志已开启（详细模式=" + verbose + "）") else Unit
    }

    fun setVerbose(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERBOSE, value).apply()
        verbose = value
        applyTagger()
        info("详细模式=" + value)
    }

    /** 详细模式才注入 tagger；否则保持 null（默认不记录任何哈希）。 */
    private fun applyTagger() {
        AuthRedactor.tagger = if (enabled && verbose) { value -> tagOf(value) } else null
    }

    fun error(message: String) = log(DiagLevel.ERROR, message)
    fun warn(message: String) = log(DiagLevel.WARN, message)
    fun info(message: String) = log(DiagLevel.INFO, message)
    fun debug(message: String) = log(DiagLevel.DEBUG, message)

    /** 兼容旧调用点。 */
    fun note(message: String) = log(DiagLevel.INFO, message)

    fun log(level: DiagLevel, message: String) {
        if (!enabled) return
        if (level == DiagLevel.DEBUG && !verbose) return
        enqueue(Entry(level, System.currentTimeMillis(), message))
    }

    /**
     * 记录一次 HTTP 往返。
     * INFO 只写一行状态码/耗时/字节数；只有失败或 WARN 才附带脱敏后的响应摘要；
     * 详细模式额外记录请求体。
     */
    fun record(
        method: String,
        url: String,
        appType: String,
        versionCode: String,
        requestBody: String?,
        httpStatus: Int,
        responseBody: String?,
        startedAtMillis: Long,
    ) {
        if (!enabled) return
        val took = System.currentTimeMillis() - startedAtMillis
        val size = responseBody?.length ?: 0
        val failed = httpStatus < 0 || httpStatus >= 400
        val expired = responseBody != null && (responseBody.contains("\"code\":-99") || responseBody.contains("\"code\": -99"))
        val level = when {
            failed -> DiagLevel.ERROR
            expired -> DiagLevel.WARN
            else -> DiagLevel.INFO
        }
        val head = method + " " + AuthRedactor.maskUrl(url) +
            " " + appType + "/" + versionCode +
            " -> " + httpStatus + " " + took + "ms " + size + "B"
        log(level, head)
        if (level == DiagLevel.ERROR || level == DiagLevel.WARN) {
            log(level, "   resp: " + AuthRedactor.summarize(responseBody))
        }
        if (verbose) {
            // GET 请求没有请求体，过去会写一行「req : (empty)」，占整份日志的四分之一
            if (!requestBody.isNullOrBlank()) {
                log(DiagLevel.DEBUG, "   req : " + AuthRedactor.summarize(requestBody))
            }
            if (level == DiagLevel.INFO) log(DiagLevel.DEBUG, "   resp: " + AuthRedactor.summarize(responseBody))
        }
    }

    private fun enqueue(entry: Entry) {
        if (queue.offer(entry)) {
            ensureWorker()
            return
        }
        // 队列满：丢掉 INFO/DEBUG，ERROR/WARN 退回同步写，保证异常不丢
        if (entry.level == DiagLevel.ERROR || entry.level == DiagLevel.WARN) {
            writeNow(entry)
        } else {
            dropped.incrementAndGet()
        }
    }

    private fun ensureWorker() {
        if (worker != null) return
        synchronized(this) {
            if (worker != null) return
            val t = Thread({
                while (true) {
                    val entry = try {
                        queue.take()
                    } catch (_: InterruptedException) {
                        continue
                    }
                    try { writeNow(entry) } catch (_: Throwable) {}
                }
            }, "diag-writer")
            t.isDaemon = true
            t.priority = Thread.MIN_PRIORITY
            worker = t
            t.start()
        }
    }

    @Synchronized
    private fun writeNow(entry: Entry) {
        if (entry.text == lastText && entry.at - lastAt <= DEDUPE_WINDOW_MS) {
            repeatCount++
            lastAt = entry.at
            return
        }
        flushRepeats(entry.at)
        emit(entry)
        lastText = entry.text
        lastAt = entry.at
    }

    /** 把之前折叠掉的重复次数补成一行，跟在最后一条真实日志后面。 */
    private fun flushRepeats(at: Long) {
        if (repeatCount <= 0) return
        val text = "   ^ 上一条重复 " + repeatCount + " 次"
        repeatCount = 0
        try { Log.println(priority(DiagLevel.INFO), TAG, text) } catch (_: Throwable) {}
        val bytes = (timeFormat.format(Date(at)) + " " + letter(DiagLevel.INFO) + "| " + text + "\n")
            .toByteArray(Charsets.UTF_8)
        try { rotating?.append(bytes) } catch (_: Throwable) {}
    }

    private fun emit(entry: Entry) {
        val line = timeFormat.format(Date(entry.at)) + " " + letter(entry.level) + "| " + entry.text + "\n"
        try { Log.println(priority(entry.level), TAG, entry.text) } catch (_: Throwable) {}
        val bytes = line.toByteArray(Charsets.UTF_8)
        try { rotating?.append(bytes) } catch (_: Throwable) {}
        if (entry.level == DiagLevel.ERROR) {
            try { errorLog?.append(bytes) } catch (_: Throwable) {}
        }
    }

    fun totalBytes(): Long = try {
        (rotating?.totalBytes() ?: 0L) + (errorLog?.size ?: 0L)
    } catch (_: Throwable) {
        0L
    }

    fun droppedCount(): Long = dropped.get()

    fun clear() {
        try { rotating?.clear() } catch (_: Throwable) {}
        try { errorLog?.clear() } catch (_: Throwable) {}
        synchronized(this) {
            lastText = null
            lastAt = 0L
            repeatCount = 0
        }
    }

    /** 导出为 zip（放 cacheDir/diag/，供 FileProvider 分享）。 */
    fun exportZip(context: Context, errorsOnly: Boolean): File? = try {
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = File(dir, "water-diag-" + stamp + ".zip")
        val sources = ArrayList<File>()
        if (!errorsOnly) {
            for (i in 0 until FILE_COUNT) {
                val f = File(context.filesDir, "diag." + i + ".log")
                if (f.exists() && f.length() > 0) sources.add(f)
            }
        }
        val err = File(context.filesDir, ERROR_FILE_NAME)
        if (err.exists() && err.length() > 0) sources.add(err)
        if (sources.isEmpty()) return null
        // 只保留最近一次导出，避免缓存目录越积越多
        dir.listFiles()?.forEach { if (it.name.endsWith(".zip") && it.absolutePath != out.absolutePath) it.delete() }
        ZipOutputStream(out.outputStream()).use { zos ->
            for (f in sources) {
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        out
    } catch (_: Throwable) {
        null
    }

    private fun letter(level: DiagLevel): String = when (level) {
        DiagLevel.ERROR -> "E"
        DiagLevel.WARN -> "W"
        DiagLevel.INFO -> "I"
        DiagLevel.DEBUG -> "D"
    }

    private fun priority(level: DiagLevel): Int = when (level) {
        DiagLevel.ERROR -> Log.ERROR
        DiagLevel.WARN -> Log.WARN
        DiagLevel.INFO -> Log.INFO
        DiagLevel.DEBUG -> Log.DEBUG
    }

    /** HMAC(本机随机密钥, value) 前 8 位：同一安装内可判断"是不是同一个值"，跨安装/跨设备无法关联。 */
    private fun tagOf(value: String): String = try {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret(), "HmacSHA256"))
        val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 4) sb.append(String.format("%02x", digest[i]))
        sb.toString()
    } catch (_: Throwable) {
        "????????"
    }

    @Synchronized
    private fun secret(): ByteArray {
        val app = appContext ?: return ByteArray(16)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TAG_SECRET, null)
        if (stored != null) {
            try { return Base64.getDecoder().decode(stored) } catch (_: Throwable) {}
        }
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        prefs.edit().putString(KEY_TAG_SECRET, Base64.getEncoder().encodeToString(bytes)).apply()
        return bytes
    }
}
