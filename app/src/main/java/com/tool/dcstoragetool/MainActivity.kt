package com.tool.dcstoragetool

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tool.dcstoragetool.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG             = "DCStorageTool"
        const val TARGET_PKG      = "com.ewell.guahao.beijingguanganmen"
        const val DATA_DIR        = "/data/user/0/$TARGET_PKG"
        const val DB_PATH         = "$DATA_DIR/databases/DCStorage"
        const val DEF_HOSPITAL_ID = "10097"
        const val DEF_DB_KEY      = "$DEF_HOSPITAL_ID.product.deviceId"

        private val UUID_LIKE     = Regex("[0-9a-fA-F-]{32,40}")
        private val INVISIBLES    = Regex("[\\s\\u00A0\\u200B\\u200C\\u200D\\uFEFF]")
        private val WRAPPER_PAIRS = listOf(
            '"' to '"', '\'' to '\'', '{' to '}', '[' to ']',
            '<' to '>', '(' to ')', '（' to '）',
            '「' to '」', '『' to '』', '‹' to '›', '“' to '”'
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private val tmpDb get() = File(cacheDir, "DCStorage_tmp")
    private val logFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var suppressWatcher = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDebug.movementMethod = ScrollingMovementMethod()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    logD("🟢 CryptoJS 加载完成，自动读取当前 deviceId")
                    doRead()
                }
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e(TAG, "CryptoJS error: $description")
                    logD("🔴 CryptoJS 加载失败: $description")
                    toast("CryptoJS 加载失败")
                }
            }
            loadUrl("file:///android_asset/index.html")
        }

        binding.btnGenerate.setOnClickListener { doRead() }
        binding.btnWrite.setOnClickListener { doWrite() }
        binding.btnClearLog.setOnClickListener {
            binding.tvDebug.text = ""
            logD("(日志已清空)")
        }
        binding.btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DCStorageTool Log", binding.tvDebug.text))
            toast("日志已复制到剪贴板")
        }

        attachUuidWatcher()
        logD("📱 DCStorageTool 启动 | target=$TARGET_PKG")
    }

    // ─── 输入解析 ──────────────────────────────────────────────

    data class ParsedInput(
        val uuidNorm: NormResult?,
        val token: String?
    )

    private fun parseInput(raw: String): ParsedInput {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        var uuidResult: NormResult? = null
        var token: String? = null
        for (line in lines) {
            when (val norm = normalizeUuid(line)) {
                is NormResult.Valid -> { if (uuidResult == null) uuidResult = norm }
                is NormResult.Invalid -> {
                    if (token == null && !looksLikePartialUuid(line)) token = line
                }
                is NormResult.Empty -> {}
            }
        }
        if (token == null) {
            token = lines.firstOrNull { line ->
                val n = normalizeUuid(line)
                n !is NormResult.Valid && !looksLikePartialUuid(line) && line.length > 10
            }
        }
        return ParsedInput(uuidResult, token)
    }

    private fun looksLikePartialUuid(s: String): Boolean {
        val cleaned = s.replace(INVISIBLES, "").replace("-", "")
        if (cleaned.isEmpty()) return false
        val hexCount = cleaned.count { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        return hexCount.toDouble() / cleaned.length > 0.7
    }

    // ─── UUID 规范化 ─────────────────────────────────────────────

    private sealed class NormResult {
        data class Valid(val canonical: String) : NormResult()
        data class Invalid(val msg: String) : NormResult()
        object Empty : NormResult()
    }

    private fun normalizeUuid(raw: String): NormResult {
        if (raw.isEmpty()) return NormResult.Empty

        // 1. 去掉所有空白和零宽字符
        var s = raw.replace(INVISIBLES, "")
        if (s.isEmpty()) return NormResult.Empty

        // 2. 剥外层成对包装符
        for ((l, r) in WRAPPER_PAIRS) {
            while (s.length >= 2 && s.first() == l && s.last() == r) {
                s = s.substring(1, s.length - 1)
            }
        }

        // 3. 正则抽出第一段 UUID-like
        val match = UUID_LIKE.find(s)?.value ?: s

        // 4. 去掉所有 -，看剩下的 hex
        val hex = match.replace("-", "")

        // 5. 校验长度
        if (hex.length < 32) {
            return NormResult.Invalid("当前 ${hex.length} 个 hex 字符，期望 32 个（是不是复制时被截断了？）")
        }
        if (hex.length > 32) {
            return NormResult.Invalid("当前 ${hex.length} 个 hex 字符，期望 32 个（开头/结尾多了 ${hex.length - 32} 个字符）")
        }

        // 6. 校验字符集
        val badIdx = hex.indexOfFirst { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }
        if (badIdx >= 0) {
            return NormResult.Invalid("第 ${badIdx + 1} 位是 '${hex[badIdx]}'，不是 0-9 / a-f")
        }

        // 7. 重排为 8-4-4-4-12 小写
        val lo = hex.lowercase()
        val canonical = "${lo.substring(0, 8)}-${lo.substring(8, 12)}-${lo.substring(12, 16)}-${lo.substring(16, 20)}-${lo.substring(20, 32)}"
        return NormResult.Valid(canonical)
    }

    private fun updateInputStatus(parsed: ParsedInput) {
        val sb = StringBuilder()
        if (parsed.uuidNorm is NormResult.Valid) sb.append("✓ UUID: ${parsed.uuidNorm.canonical}")
        else if (parsed.uuidNorm is NormResult.Invalid) sb.append("✗ UUID: ${parsed.uuidNorm.msg}")
        if (parsed.token != null) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("✓ Token: ${maskToken(parsed.token)}")
        }
        if (sb.isEmpty()) {
            binding.tvInputStatus.text = "粘贴 UUID 和/或 Token（换行分隔）"
            binding.tvInputStatus.setTextColor(0xFF757575.toInt())
        } else {
            binding.tvInputStatus.text = sb.toString()
            binding.tvInputStatus.setTextColor(
                if (parsed.uuidNorm is NormResult.Invalid) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()
            )
        }
    }

    private fun attachUuidWatcher() {
        binding.etNewUuid.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s?.toString() ?: ""
                updateInputStatus(parseInput(raw))
            }
        })
        updateInputStatus(ParsedInput(null, null))
    }

    // ─── Root 工具 ───────────────────────────────────────────────

    private fun suFull(cmd: String): Triple<Int, String, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-M", "-c", cmd))
            val stdout = p.inputStream.bufferedReader().readText().trim()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            Triple(p.exitValue(), stdout, stderr)
        } catch (e: Exception) {
            Triple(-1, "", e.message ?: "exception")
        }
    }

    private fun su(cmd: String): Boolean {
        val (exit, _, stderr) = suFull(cmd)
        Log.d(TAG, "su exit=$exit err=$stderr | $cmd")
        return exit == 0
    }

    private fun suOut(cmd: String): String = suFull(cmd).second

    // ─── 格式检测 / 转换 ─────────────────────────────────────────

    private data class UuidFmt(val dashes: Boolean, val upper: Boolean) {
        override fun toString() = "${if (dashes) "带-" else "无-"}/${if (upper) "大写" else "小写"}"
    }

    private fun detectFmt(s: String): UuidFmt {
        val t = s.trim().trim('"')
        val dashes = t.length == 36 && t.count { it == '-' } == 4
        val core = t.replace("-", "")
        val hasUpper = core.any { it.isLetter() && it.isUpperCase() }
        val hasLower = core.any { it.isLetter() && it.isLowerCase() }
        val upper = hasUpper && !hasLower
        return UuidFmt(dashes, upper)
    }

    /** 取核心 hex：去掉 '-' 并转小写，用于忽略格式（带-/大小写）的相等比较。 */
    private fun coreHex(s: String): String = s.replace("-", "").lowercase()

    // ─── 路径定位 ────────────────────────────────────────────────

    private fun findDbPath(): String {
        val out = suFull("find $DATA_DIR /data/data/$TARGET_PKG -name 'DCStorage' 2>/dev/null").second
        return out.lines().firstOrNull { it.contains("DCStorage") && !it.contains("media") } ?: DB_PATH
    }

    private fun findDcFile(): String? {
        val out = suFull("find $DATA_DIR/files -maxdepth 1 -name '.DC*.txt' 2>/dev/null").second
        return out.lines().firstOrNull { it.isNotBlank() }
    }

    private fun findUniPref(): String? {
        val out = suFull("ls $DATA_DIR/shared_prefs/ 2>/dev/null").second
        val name = out.lines().firstOrNull {
            it.startsWith("__UNI__") && it.endsWith(".xml") && !it.endsWith("_storages.xml")
        } ?: return null
        return "$DATA_DIR/shared_prefs/$name"
    }

    // ─── 读取 ────────────────────────────────────────────────────

    private fun doRead() {
        Thread {
            logD("───── 读取 ─────")
            val tmp   = tmpDb.absolutePath
            val myUid = android.os.Process.myUid()

            su("rm -f '$tmp'")

            val realDbPath = findDbPath()
            logD("🔍 DCStorage 路径: $realDbPath")

            val cpOk    = su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")
            val tmpFile = File(tmp)

            if (!cpOk || !tmpFile.exists()) {
                logD("🔴 复制数据库到 cache 失败")
                ui { binding.tvValDb.text = "—"; toast("复制数据库失败") }
                return@Thread
            }

            try {
                val db        = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)
                val tableName = findTable(db)
                if (tableName == null) {
                    db.close()
                    logD("🔴 数据库中找不到主表")
                    ui { binding.tvValDb.text = "—"; toast("未找到数据表") }
                    return@Thread
                }
                logD("📋 主表: $tableName")

                val cur = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf(DEF_DB_KEY))
                if (!cur.moveToFirst()) {
                    cur.close(); db.close()
                    logD("🔴 主表中找不到 Key=$DEF_DB_KEY")
                    ui { binding.tvValDb.text = "—"; toast("未找到 Key") }
                    return@Thread
                }

                val raw = cur.getString(0)
                cur.close(); db.close()
                logD("🔐 DCStorage 密文长度=${raw.length}")

                val plain = decryptSync(raw)
                logD("🔓 解密得到: ${mask(plain)} | 格式=${detectFmt(plain)}")
                ui { binding.tvValDb.text = if (plain.isNotEmpty()) plain else "(空)" }

                val dcFile = findDcFile()
                if (dcFile != null) {
                    val dcVal = suOut("cat '$dcFile' 2>/dev/null").trim()
                    logD("📁 .DC 文件: $dcFile")
                    logD("   值: ${mask(dcVal)} | 格式=${detectFmt(dcVal)}")
                    ui { binding.tvValDc.text = if (dcVal.isNotEmpty()) dcVal else "(空)" }
                } else {
                    logD("⚠️  .DC*.txt 文件未找到")
                    ui { binding.tvValDc.text = "未找到" }
                }

                readToken()
            } catch (e: Exception) {
                Log.e(TAG, "read error", e)
                logD("🔴 读取异常: ${e.message}")
                ui { binding.tvValDb.text = "—"; toast("读取错误: ${e.message}") }
            }
        }.start()
    }

    // ─── 写入 ────────────────────────────────────────────────────

    private fun doWrite() {
        val raw = binding.etNewUuid.text.toString()
        val parsed = parseInput(raw)

        if (parsed.uuidNorm == null && parsed.token == null) {
            toast("请粘贴 UUID 和/或 Token（换行分隔）")
            return
        }
        if (parsed.uuidNorm is NormResult.Invalid) {
            toast("UUID 不合规：${parsed.uuidNorm.msg}")
            logD("🔴 写入被拒：${parsed.uuidNorm.msg}")
            return
        }

        val newUuid = (parsed.uuidNorm as? NormResult.Valid)?.canonical
        val newToken = parsed.token

        Thread {
            if (newUuid != null) logD("───── 写入 UUID: $newUuid ─────")
            if (newToken != null) logD("───── 写入 Token: ${maskToken(newToken)} ─────")

            // 1. force-stop 目标 App
            val fs = suFull("am force-stop $TARGET_PKG")
            logD("🛑 force-stop 退出码=${fs.first}${if (fs.third.isNotEmpty()) " err=${fs.third}" else ""}")

            var dbWriteOk = false
            var dcOk = false
            var uniOk = false

            if (newUuid != null) {
            // 2. 定位三处存储
            val realDbPath = findDbPath()
            val dcFile     = findDcFile()
            val uniXml     = findUniPref()
            logD("📁 db=$realDbPath")
            logD("📁 dc=$dcFile")
            logD("📁 uni=$uniXml")

            // 3. 拷 DCStorage 到 cache，读旧密文
            val tmp   = tmpDb.absolutePath
            val myUid = android.os.Process.myUid()
            su("rm -f '$tmp'")
            val cpOk = su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")
            if (!cpOk || !File(tmp).exists()) {
                logD("🔴 复制 DCStorage 失败")
                ui { toast("复制数据库失败") }
                writeTokenToSp(newToken); doRead(); return@Thread
            }

            var oldEncrypted: String? = null
            var tableName: String? = null
            try {
                val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)
                tableName = findTable(db)
                if (tableName != null) {
                    val cur = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf(DEF_DB_KEY))
                    if (cur.moveToFirst()) oldEncrypted = cur.getString(0)
                    cur.close()
                }
                db.close()
            } catch (e: Exception) {
                Log.e(TAG, "read old enc error", e)
                logD("🔴 读旧 DCStorage 出错: ${e.message}")
            }
            if (tableName == null) {
                logD("🔴 找不到主表，终止 UUID 写入")
                ui { toast("未找到数据表") }
                writeTokenToSp(newToken); doRead(); return@Thread
            }

            // 4. 读 .DC*.txt 和 __UNI__*.xml 旧值
            val dcOld = dcFile?.let { suOut("cat '$it' 2>/dev/null").trim() } ?: ""
            val uniOld = uniXml?.let {
                suOut("sed -n 's|.*<string name=\"android_device_dcloud_id\">\\([^<]*\\)</string>.*|\\1|p' '$it' 2>/dev/null").trim()
            } ?: ""

            // 5. 解密旧 DCStorage 值（仅用于显示格式 + hex 比对）
            val dbOldPlain = oldEncrypted?.let { decryptSync(it) } ?: ""
            logD("🔎 旧值 db=${mask(dbOldPlain)} dc=${mask(dcOld)} uni=${mask(uniOld)}")
            logD("📐 旧格式 db=${if (dbOldPlain.isNotEmpty()) detectFmt(dbOldPlain).toString() else "-"} dc=${if (dcOld.isNotEmpty()) detectFmt(dcOld).toString() else "-"} uni=${if (uniOld.isNotEmpty()) detectFmt(uniOld).toString() else "-"}")

            // 以 DCStorage 为基准：仅当 .DC / Uni 旧值的核心 hex 与 DCStorage 相同时才修改
            val dbHex   = coreHex(dbOldPlain)
            val dcSame  = dbHex.isNotEmpty() && dcOld.isNotEmpty()  && coreHex(dcOld)  == dbHex
            val uniSame = dbHex.isNotEmpty() && uniOld.isNotEmpty() && coreHex(uniOld) == dbHex
            logD("🔗 与 DCStorage 比对 dc=${if (dcSame) "相同→修改" else "不同→保持"} uni=${if (uniSame) "相同→修改" else "不同→保持"}")

            // 6. 加密 DCStorage 新值
            val newEncrypted = encryptSync(newUuid)
            if (newEncrypted.isEmpty() || newEncrypted.startsWith("ERROR")) {
                logD("🔴 加密失败: $newEncrypted")
                ui { toast("加密失败") }
                writeTokenToSp(newToken); doRead(); return@Thread
            }
            logD("🔐 加密成功，密文长度=${newEncrypted.length}")

            // 7. 写 DCStorage
            try {
                val db   = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READWRITE)
                val cv   = ContentValues().apply { put("value", newEncrypted) }
                val rows = db.update(tableName, cv, "key=?", arrayOf(DEF_DB_KEY))
                db.close()
                logD("💾 DCStorage rows updated = $rows")
                if (rows <= 0) {
                    ui { toast("DCStorage 中未找到 Key") }
                    writeTokenToSp(newToken); doRead(); return@Thread
                }
            } catch (e: Exception) {
                Log.e(TAG, "write db error", e)
                logD("🔴 写 DCStorage 出错: ${e.message}")
                ui { toast("写入 DCStorage 失败: ${e.message}") }
                writeTokenToSp(newToken); doRead(); return@Thread
            }

            val dbUid = suOut("stat -c '%u' '$realDbPath'").trim()
            val dbGid = suOut("stat -c '%g' '$realDbPath'").trim()
            dbWriteOk = su("cp '$tmp' '$realDbPath'")
            if (dbWriteOk && dbUid.isNotEmpty()) {
                val g = if (dbGid.isNotEmpty()) dbGid else dbUid
                su("chown $dbUid:$g '$realDbPath'")
                su("chmod 660 '$realDbPath'")
                logD("💾 DCStorage 写回 ✓ chown $dbUid:$g")
            } else {
                logD("🔴 DCStorage 写回失败")
            }

            // 8. 删 DCStorage WAL/SHM/journal
            su("rm -f '${realDbPath}-journal' '${realDbPath}-shm' '${realDbPath}-wal'")
            logD("🧹 删除 WAL/SHM/journal")

            // 9. 写 .DC*.txt —— 仅当与 DCStorage 相同
            dcOk = if (dcFile != null && dcSame) {
                val uid = suOut("stat -c '%u' '$dcFile'").trim()
                val gid = suOut("stat -c '%g' '$dcFile'").trim()
                val ok  = su("printf '%s' '$newUuid' > '$dcFile'")
                if (ok && uid.isNotEmpty()) {
                    val g = if (gid.isNotEmpty()) gid else uid
                    su("chown $uid:$g '$dcFile'")
                    su("chmod 600 '$dcFile'")
                }
                val verify = suOut("cat '$dcFile' 2>/dev/null").trim()
                val match = verify == newUuid
                logD("💾 .DC 写入 ${if (ok && match) "✓" else "✗"} (校验 ${if (match) "一致" else "不一致 verify=${mask(verify)}"})")
                ok && match
            } else if (dcFile == null) {
                logD("⚠️  .DC 文件未找到，跳过")
                false
            } else {
                logD("⏭️  .DC 与 DCStorage 不同，按规则保持不变 (dc=${mask(dcOld)})")
                false
            }

            // 10. 写 __UNI__*.xml —— 仅当与 DCStorage 相同
            uniOk = if (uniXml != null && uniOld.isNotEmpty() && uniSame) {
                val uid = suOut("stat -c '%u' '$uniXml'").trim()
                val gid = suOut("stat -c '%g' '$uniXml'").trim()
                val ok  = su(
                    "sed -i 's|<string name=\"android_device_dcloud_id\">[^<]*</string>" +
                    "|<string name=\"android_device_dcloud_id\">$newUuid</string>|' '$uniXml'"
                )
                if (ok && uid.isNotEmpty()) {
                    val g = if (gid.isNotEmpty()) gid else uid
                    su("chown $uid:$g '$uniXml'")
                    su("chmod 660 '$uniXml'")
                }
                val verify = suOut(
                    "sed -n 's|.*<string name=\"android_device_dcloud_id\">\\([^<]*\\)</string>.*|\\1|p' '$uniXml' 2>/dev/null"
                ).trim()
                val match = verify == newUuid
                logD("💾 Uni 写入 ${if (ok && match) "✓" else "✗"} (校验 ${if (match) "一致" else "不一致 verify=${mask(verify)}"})")
                ok && match
            } else if (uniXml == null) {
                logD("⚠️  Uni XML 未找到，跳过")
                false
            } else if (uniOld.isEmpty()) {
                logD("⚠️  Uni XML 没有 android_device_dcloud_id 键，跳过")
                false
            } else {
                logD("⏭️  Uni 与 DCStorage 不同，按规则保持不变 (uni=${mask(uniOld)})")
                false
            }

            logD("───── 完成 db=${if (dbWriteOk) "✓" else "✗"} dc=${if (dcOk) "✓" else if (dcSame) "✗" else "保持"} uni=${if (uniOk) "✓" else if (uniSame) "✗" else "保持"} ─────")
            }

            // ─── 写入 Token（如果提供了） ───
            val tokenOk = writeTokenToSp(newToken)

            val sb = StringBuilder("写入完成\n")
            if (newUuid != null) sb.append("UUID: ").append(if (dbWriteOk) "✓" else "✗").append('\n')
            if (newToken != null) sb.append("Token: ").append(if (tokenOk) "✓" else "✗")
            ui { toast(sb.toString().trim()) }

            logD("🔄 写入完成，重新从磁盘读取...")
            doRead()
        }.start()
    }

    private fun writeTokenToSp(newToken: String?): Boolean {
        if (newToken == null) return false
        try {
            val spFile = findTokenSpFile()
            if (spFile == null) { logD("🔴 未找到 token SP 文件，无法写入"); return false }
            logD("🔑 Token SP 文件: $spFile")
            var actualKey: String? = null; var oldCipher: String? = null
            for (mode in listOf("production", "debug")) {
                val key = "$DEF_HOSPITAL_ID.$mode.app.session"
                val v = extractSpValue(spFile, key)
                if (v != null) { actualKey = key; oldCipher = v; break }
            }
            if (actualKey == null || oldCipher == null) { logD("🔴 SP 文件中未找到 app.session 键"); return false }
            val oldJson = decryptByAesSync(oldCipher)
            if (oldJson.isEmpty() || oldJson.startsWith("ERROR")) { logD("🔴 解密旧 token JSON 失败"); return false }
            val newJson = replaceTokenInJson(oldJson, newToken)
            val newCipher = encryptByAESSync(newJson)
            if (newCipher.isEmpty() || newCipher.startsWith("ERROR")) { logD("🔴 加密新 token JSON 失败"); return false }
            val escapedOld = oldCipher.replace("/", "\\/").replace("&", "\\&")
            val escapedNew = newCipher.replace("/", "\\/").replace("&", "\\&")
            val uid = suOut("stat -c '%u' '$spFile'").trim()
            val gid = suOut("stat -c '%g' '$spFile'").trim()
            val ok = su("sed -i 's|<string name=\"$actualKey\">$escapedOld</string>|<string name=\"$actualKey\">$escapedNew</string>|' '$spFile'")
            if (ok && uid.isNotEmpty()) { val g = if (gid.isNotEmpty()) gid else uid; su("chown $uid:$g '$spFile'"); su("chmod 660 '$spFile'") }
            val verify = extractSpValue(spFile, actualKey)
            val match = verify == newCipher
            logD("💾 Token 写入 ${if (ok && match) "✓" else "✗"}")
            return ok && match
        } catch (e: Exception) { Log.e(TAG, "write token error", e); return false }
    }

    private fun replaceTokenInJson(json: String, newToken: String): String {
        var j = json.trim()
        while (j.startsWith("\"") && j.endsWith("\"")) j = j.substring(1, j.length - 1).trim()
        val regex = Regex("\"token\"\\s*:\\s*\"[^\"]*\"")
        if (regex.containsMatchIn(j)) return regex.replaceFirst(j, "\"token\":\"$newToken\"")
        if (j.endsWith("}")) return j.substring(0, j.length - 1) + ",\"token\":\"$newToken\"}"
        return j
    }

    private fun findTokenSpFile(): String? {
        val out = suFull("grep -rl 'app\\.session' $DATA_DIR/shared_prefs/ 2>/dev/null").second
        out.lines().firstOrNull { it.isNotBlank() }?.let { return it }
        for (name in listOf("PandoraEntry.xml", "dcloud_storage.xml", "pdr.xml")) {
            val path = "$DATA_DIR/shared_prefs/$name"
            if (su("test -f '$path'") && suOut("grep -c 'app.session' '$path' 2>/dev/null").trim() != "0") return path
        }
        for (line in suOut("ls $DATA_DIR/shared_prefs/*.xml 2>/dev/null").lines().map { it.trim() }.filter { it.isNotBlank() }) {
            if (suOut("grep -c 'app.session' '$line' 2>/dev/null").trim() != "0") return line
        }
        return null
    }

    private fun extractSpValue(spPath: String, key: String): String? {
        val out = suOut("sed -n 's|.*<string name=\"$key\">\\([^<]*\\)</string>.*|\\1|p' '$spPath' 2>/dev/null").trim()
        return if (out.isNotEmpty()) out else null
    }

    private fun readToken() {
        try {
            val spFile = findTokenSpFile()
            if (spFile == null) { logD("⚠️  未找到 token 存储文件"); ui { binding.tvValUni.text = "未找到" }; return }
            logD("🔑 Token SP 文件: $spFile")
            var cipherValue: String? = null
            for (mode in listOf("production", "debug")) {
                val v = extractSpValue(spFile, "$DEF_HOSPITAL_ID.$mode.app.session")
                if (v != null) { cipherValue = v; break }
            }
            if (cipherValue == null) { logD("⚠️  SP 中无 app.session 键"); ui { binding.tvValUni.text = "无数据" }; return }
            logD("🔐 Token 密文长度=${cipherValue.length}")
            val plain = decryptByAesSync(cipherValue)
            if (plain.isEmpty() || plain.startsWith("ERROR")) { logD("🔴 Token 解密失败"); ui { binding.tvValUni.text = "解密失败" }; return }
            val tokenValue = extractTokenFromJson(plain)
            logD("🎫 Token: ${maskToken(tokenValue)}")
            ui { binding.tvValUni.text = if (tokenValue.isNotEmpty()) tokenValue else "(空)" }
        } catch (e: Exception) { Log.e(TAG, "read token error", e); ui { binding.tvValUni.text = "读取错误" } }
    }

    private fun extractTokenFromJson(json: String): String {
        var j = json.trim()
        while (j.startsWith("\"") && j.endsWith("\"")) j = j.substring(1, j.length - 1)
        val regex = Regex("\"token\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(j)?.groupValues?.get(1) ?: j
    }

    private fun decryptByAesSync(cipherText: String): String {
        val lock = CountDownLatch(1); val result = arrayOf("")
        val escaped = cipherText.replace("'", "\\'")
        ui { webView.evaluateJavascript("decryptByAes('$escaped','$DEF_HOSPITAL_ID')") { res -> result[0] = (res ?: "").removeSurrounding("\"").replace("\\\"", ""); lock.countDown() } }
        lock.await(5, TimeUnit.SECONDS)
        return result[0]
    }

    private fun encryptByAESSync(plaintext: String): String {
        val lock = CountDownLatch(1); val result = arrayOf("")
        val escaped = plaintext.replace("'", "\\'")
        ui { webView.evaluateJavascript("encryptByAES('$DEF_HOSPITAL_ID','$escaped')") { res -> result[0] = (res ?: "").removeSurrounding("\""); lock.countDown() } }
        lock.await(5, TimeUnit.SECONDS)
        return result[0]
    }

    private fun maskToken(s: String): String {
        if (s.isEmpty()) return "(空)"
        return if (s.length <= 60) s else "${s.take(25)}…${s.takeLast(25)}"
    }

    // ─── 同步包装 WebView JS 调用 ─────────────────────────────────

    private fun decryptSync(cipherB64: String): String {
        val lock = CountDownLatch(1)
        val result = arrayOf("")
        val escaped = cipherB64.replace("'", "\\'")
        ui {
            webView.evaluateJavascript("decrypt('$DEF_HOSPITAL_ID','$escaped')") { res ->
                result[0] = (res ?: "").removeSurrounding("\"").replace("\\\"", "")
                lock.countDown()
            }
        }
        lock.await(5, TimeUnit.SECONDS)
        return result[0]
    }

    private fun encryptSync(plain: String): String {
        val lock = CountDownLatch(1)
        val result = arrayOf("")
        ui {
            webView.evaluateJavascript("encrypt('$DEF_HOSPITAL_ID', '\"$plain\"')") { res ->
                result[0] = (res ?: "").removeSurrounding("\"")
                lock.countDown()
            }
        }
        lock.await(5, TimeUnit.SECONDS)
        return result[0]
    }

    // ─── 辅助 ────────────────────────────────────────────────────

    private fun findTable(db: SQLiteDatabase): String? {
        val cur    = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
        val tables = mutableListOf<String>()
        while (cur.moveToNext()) tables.add(cur.getString(0))
        cur.close()

        val dcTable  = tables.firstOrNull { it.matches(Regex("DC_\\d+_storage")) }
        val fallback = tables.firstOrNull { name ->
            if (name == "android_metadata") return@firstOrNull false
            try {
                val c  = db.rawQuery("SELECT key, value FROM $name LIMIT 1", null)
                val ok = c.columnCount >= 2; c.close(); ok
            } catch (e: Exception) { false }
        }
        return dcTable ?: fallback
    }

    private fun mask(s: String): String {
        if (s.isEmpty()) return "(空)"
        val core = s.replace("-", "")
        return if (core.length <= 8) s
        else "${core.take(4)}…${core.takeLast(4)}"
    }

    private fun logD(line: String) {
        Log.d(TAG, line)
        val stamped = "${logFmt.format(Date())}  $line\n"
        runOnUiThread {
            binding.tvDebug.append(stamped)
            val layout = binding.tvDebug.layout ?: return@runOnUiThread
            val scrollAmount = layout.getLineTop(binding.tvDebug.lineCount) - binding.tvDebug.height
            if (scrollAmount > 0) binding.tvDebug.scrollTo(0, scrollAmount)
            else binding.tvDebug.scrollTo(0, 0)
        }
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
