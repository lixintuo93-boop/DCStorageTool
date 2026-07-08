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
    private val tmpTokenDb get() = File(cacheDir, "DCStorage_tmp_token")
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
        binding.btnPaste.setOnClickListener {
            val cm2 = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cm2.hasPrimaryClip()) {
                val clip = cm2.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (clip.isNotEmpty()) binding.etNewUuid.setText(clip)
            }
        }
        binding.btnClearInput.setOnClickListener { binding.etNewUuid.text.clear() }

        attachUuidWatcher()
        logD("📱 DCStorageTool 启动 | target=$TARGET_PKG")
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

    private fun updateStatus(r: NormResult) {
        when (r) {
            is NormResult.Empty -> {
                binding.tvInputStatus.text = "等待输入..."
                binding.tvInputStatus.setTextColor(0xFF757575.toInt())
            }
            is NormResult.Valid -> {
                binding.tvInputStatus.text = "✓ 将写入 ${r.canonical}"
                binding.tvInputStatus.setTextColor(0xFF2E7D32.toInt())
            }
            is NormResult.Invalid -> {
                binding.tvInputStatus.text = "✗ ${r.msg}"
                binding.tvInputStatus.setTextColor(0xFFC62828.toInt())
            }
        }
    }

    private fun attachUuidWatcher() {
        binding.etNewUuid.addTextChangedListener(object : TextWatcher {
            var oldLen = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                oldLen = s?.length ?: 0
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                val raw = s?.toString() ?: ""
                // 完整登录 JSON（含 value 包装也支持）
                if (isFullSessionJson(raw) || isFullSessionJson(tryExtractValue(raw))) {
                    binding.tvInputStatus.text = "✓ 检测到完整登录 JSON，将执行账号迁移"
                    binding.tvInputStatus.setTextColor(0xFF2E7D32.toInt())
                    return
                }
                val isPaste = raw.length - oldLen >= 16
                if (isPaste) {
                    val r = normalizeUuid(raw)
                    if (r is NormResult.Valid && r.canonical != raw) {
                        suppressWatcher = true
                        s?.replace(0, s.length, r.canonical)
                        suppressWatcher = false
                        binding.etNewUuid.setSelection(r.canonical.length)
                        logD("📋 粘贴后已自动规范化: ${mask(raw)} → ${r.canonical}")
                    }
                    updateStatus(r)
                } else {
                    updateStatus(normalizeUuid(raw))
                }
            }
        })
        updateStatus(NormResult.Empty)
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
                readAccountInfo()
            } catch (e: Exception) {
                Log.e(TAG, "read error", e)
                logD("🔴 读取异常: ${e.message}")
                ui { binding.tvValDb.text = "—"; toast("读取错误: ${e.message}") }
            }
        }.start()
    }

    // ─── 写入 ────────────────────────────────────────────────────

    /** 从完整登录响应中提取 value 对象 */
    private fun tryExtractValue(raw: String): String {
        val t = raw.trim()
        val m = Regex("\"value\"\\s*:\\s*").find(t)
        if (m != null) {
            val start = m.range.last + 1
            if (start < t.length && t[start] == '{') {
                // 手动提取 {...} 对象
                var pos = start; var depth = 0; val sb = StringBuilder()
                while (pos < t.length) {
                    val c = t[pos]; sb.append(c)
                    when {
                        c == '{' -> depth++
                        c == '}' -> { depth--; if (depth == 0) return sb.toString() }
                        c == '"' -> { pos++; while (pos < t.length && t[pos] != '"') { if (t[pos] == '\\') pos++; pos++ } }
                    }
                    pos++
                }
            }
        }
        return t
    }

    private fun doWrite() {
        val raw = binding.etNewUuid.text.toString().trim()

        // 先提取 value（处理完整登录响应）
        val content = tryExtractValue(raw)

        // 判断：完整登录 JSON → session 迁移，否则尝试 UUID
        var newUuid: String? = null
        var newToken: String? = null

        if (isFullSessionJson(content)) {
            newToken = content
        } else {
            val norm = normalizeUuid(content)
            if (norm is NormResult.Valid) {
                newUuid = norm.canonical
            } else {
                toast("请输入合法 UUID 或完整登录 JSON")
                return
            }
        }

        Thread {
            if (newUuid != null) logD("───── 写入 UUID: $newUuid ─────")
            if (newToken != null) logD("───── 写入 Session: ${newToken?.let { if (it.length <= 60) it else it.take(25) + "…" + it.takeLast(25)} } ─────")

            // 1. force-stop 目标 App
            val fs = suFull("am force-stop $TARGET_PKG")
            logD("🛑 force-stop 退出码=${fs.first}${if (fs.third.isNotEmpty()) " err=${fs.third}" else ""}")

            var dbWriteOk = false; var dcOk = false; var uniOk = false

            if (newUuid != null) {
            // 2. 定位三处存储
            val realDbPath = findDbPath()
            val dcFile     = findDcFile()
            val uniXml     = findUniPref()
            logD("📁 db=$realDbPath"); logD("📁 dc=$dcFile"); logD("📁 uni=$uniXml")

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

            // 5. 解密旧 DCStorage 值（仅用于显示格式 + hex 比对，不再做格式适配）
            val dbOldPlain = oldEncrypted?.let { decryptSync(it) } ?: ""
            logD("🔎 旧值 db=${mask(dbOldPlain)} dc=${mask(dcOld)} uni=${mask(uniOld)}")
            logD("📐 旧格式 db=${if (dbOldPlain.isNotEmpty()) detectFmt(dbOldPlain).toString() else "-"} dc=${if (dcOld.isNotEmpty()) detectFmt(dcOld).toString() else "-"} uni=${if (uniOld.isNotEmpty()) detectFmt(uniOld).toString() else "-"}")

            // 以 DCStorage 为基准：仅当 .DC / Uni 旧值的核心 hex 与 DCStorage 相同时才修改
            val dbHex   = coreHex(dbOldPlain)
            val dcSame  = dbHex.isNotEmpty() && dcOld.isNotEmpty()  && coreHex(dcOld)  == dbHex
            val uniSame = dbHex.isNotEmpty() && uniOld.isNotEmpty() && coreHex(uniOld) == dbHex
            logD("🔗 与 DCStorage 比对 dc=${if (dcSame) "相同→修改" else "不同→保持"} uni=${if (uniSame) "相同→修改" else "不同→保持"}")

            // 不再按各处旧格式适配，三处统一写入规范形式（8-4-4-4-12 带- 小写）
            val newForDb  = newUuid
            val newForDc  = newUuid
            val newForUni = newUuid
            logD("✏️ 即将写入 db=$newForDb")
            if (dcSame)  logD("✏️ 即将写入 dc=$newForDc")
            if (uniSame) logD("✏️ 即将写入 uni=$newForUni")

            // 6. 加密 DCStorage 新值
            val newEncrypted = encryptSync(newForDb)
            if (newEncrypted.isEmpty() || newEncrypted.startsWith("ERROR")) {
                logD("🔴 加密失败: $newEncrypted")
                ui { toast("加密失败") }; return@Thread
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
                    ui { toast("DCStorage 中未找到 Key") }; return@Thread
                }
            } catch (e: Exception) {
                Log.e(TAG, "write db error", e)
                logD("🔴 写 DCStorage 出错: ${e.message}")
                ui { toast("写入 DCStorage 失败: ${e.message}") }; return@Thread
            }

            val dbUid = suOut("stat -c '%u' '$realDbPath'").trim()
            val dbGid = suOut("stat -c '%g' '$realDbPath'").trim()
            val dbWriteOk = su("cp '$tmp' '$realDbPath'")
            if (dbWriteOk && dbUid.isNotEmpty()) {
                val g = if (dbGid.isNotEmpty()) dbGid else dbUid
                su("chown $dbUid:$g '$realDbPath'")
                su("chmod 660 '$realDbPath'")
                logD("💾 DCStorage 写回 ✓ chown $dbUid:$g")
            } else {
                logD("🔴 DCStorage 写回失败")
            }

            // 8. 删 DCStorage WAL/SHM/journal
            val cleanOk = su("rm -f '${realDbPath}-journal' '${realDbPath}-shm' '${realDbPath}-wal'")
            logD("🧹 删除 WAL/SHM/journal: ${if (cleanOk) "✓" else "✗"}")

            // 9. 写 .DC*.txt —— 仅当与 DCStorage 相同
            val dcOk = if (dcFile != null && dcSame) {
                val uid = suOut("stat -c '%u' '$dcFile'").trim()
                val gid = suOut("stat -c '%g' '$dcFile'").trim()
                val ok  = su("printf '%s' '$newForDc' > '$dcFile'")
                if (ok && uid.isNotEmpty()) {
                    val g = if (gid.isNotEmpty()) gid else uid
                    su("chown $uid:$g '$dcFile'")
                    su("chmod 600 '$dcFile'")
                }
                val verify = suOut("cat '$dcFile' 2>/dev/null").trim()
                val match = verify == newForDc
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
            val uniOk = if (uniXml != null && uniOld.isNotEmpty() && uniSame) {
                val uid = suOut("stat -c '%u' '$uniXml'").trim()
                val gid = suOut("stat -c '%g' '$uniXml'").trim()
                val ok  = su(
                    "sed -i 's|<string name=\"android_device_dcloud_id\">[^<]*</string>" +
                    "|<string name=\"android_device_dcloud_id\">$newForUni</string>|' '$uniXml'"
                )
                if (ok && uid.isNotEmpty()) {
                    val g = if (gid.isNotEmpty()) gid else uid
                    su("chown $uid:$g '$uniXml'")
                    su("chmod 660 '$uniXml'")
                }
                val verify = suOut(
                    "sed -n 's|.*<string name=\"android_device_dcloud_id\">\\([^<]*\\)</string>.*|\\1|p' '$uniXml' 2>/dev/null"
                ).trim()
                val match = verify == newForUni
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

            // ─── 写入 Token / Session ───
            var tokenOk = false
            if (newToken != null) {
                val realDbPath = findDbPath()
                val tmpTk = tmpTokenDb.absolutePath
                val myUid = android.os.Process.myUid()
                su("rm -f '$tmpTk'")
                if (su("cp '$realDbPath' '$tmpTk' && chown $myUid:$myUid '$tmpTk' && chmod 644 '$tmpTk'") && File(tmpTk).exists()) {
                    try {
                        val db = SQLiteDatabase.openDatabase(tmpTk, null, SQLiteDatabase.OPEN_READWRITE)
                        val tableName = findTable(db)
                        if (tableName != null) {
                            if (isFullSessionJson(newToken)) {
                                logD("📦 检测到完整登录 JSON，执行账号迁移...")
                                tokenOk = writeFullSession(newToken, db, tableName, newUuid != null)
                            }
                        } else { logD("🔴 未找到主表") }
                        db.close()
                        if (tokenOk) {
                            val dbUid = suOut("stat -c '%u' '$realDbPath'").trim()
                            val dbGid = suOut("stat -c '%g' '$realDbPath'").trim()
                            val ok = su("cp '$tmpTk' '$realDbPath'")
                            if (ok && dbUid.isNotEmpty()) { val g = if (dbGid.isNotEmpty()) dbGid else dbUid; su("chown $dbUid:$g '$realDbPath'"); su("chmod 660 '$realDbPath'") }
                            logD("💾 Session 写回 ${if (ok) "✓" else "✗"}")
                        }
                    } catch (e: Exception) { Log.e(TAG, "write session error", e) }
                } else { logD("🔴 复制 DCStorage 失败（session 写入）") }
            }

            val sb = StringBuilder("写入完成\n")
            if (newUuid != null) sb.append("UUID: ").append(if (dbWriteOk) "✓" else "✗").append('\n')
            if (newToken != null) sb.append("账号迁移: ").append(if (tokenOk) "✓" else "✗")
            ui { toast(sb.toString().trim()) }

            logD("🔄 写入完成，重新从磁盘读取...")
            doRead()
        }.start()
    }

    /** 判断输入是否为完整登录 JSON（包含 token + id + mobile 字段） */
    private fun isFullSessionJson(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("{") && t.contains("\"token\"") && t.contains("\"id\"") && t.contains("\"mobile\"")
    }

    /** 从 JSON 串中提取指定 key 的字符串值 */
    private fun extractJsonString(json: String, key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        return m?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
    }

    /** 从 JSON 中提取嵌套对象 {...} */
    private fun extractJsonObject(json: String, key: String): String? {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        var pos = json.indexOf('{', idx)
        if (pos < 0) return null
        var depth = 0; val sb = StringBuilder()
        while (pos < json.length) {
            val c = json[pos]; sb.append(c)
            if (c == '{') depth++
            else if (c == '}') { depth--; if (depth == 0) return sb.toString() }
            else if (c == '"') { sb.append(c); pos++; while (pos < json.length && json[pos] != '"') { if (json[pos] == '\\') { sb.append(json[pos]); pos++ }; sb.append(json[pos]); pos++ }; if (pos < json.length) sb.append(json[pos]) }
            pos++
        }
        return null
    }

    /** 写入完整 session JSON + 同步关联 key */
    private fun writeFullSession(json: String, db: SQLiteDatabase, tableName: String, skipDeviceId: Boolean = false): Boolean {
        try {
            fun insertOrReplace(k: String, v: String) {
                val cv = ContentValues()
                cv.put("key", k)
                cv.put("value", v)
                cv.put("timestamp", System.currentTimeMillis().toString())
                db.replace(tableName, null, cv)
            }

            val sessionCipher = encryptByAESSync(json)
            if (sessionCipher.isEmpty() || sessionCipher.startsWith("ERROR")) { logD("🔴 加密 session 失败"); return false }
            insertOrReplace("$DEF_HOSPITAL_ID.product.app.session", sessionCipher)
            logD("💾 app.session 已写入")

            if (!skipDeviceId) {
                val devId = extractJsonString(json, "deviceId")
                if (devId.isNotEmpty()) {
                    val c = encryptSync(devId)
                    if (c.isNotEmpty() && !c.startsWith("ERROR")) { insertOrReplace(DEF_DB_KEY, c); logD("💾 deviceId 已同步: $devId") }
                }
            }

            val mobile = extractJsonString(json, "mobile")
            if (mobile.isNotEmpty()) {
                val c = encryptByAESSync("{\"mobile\":\"$mobile\"}")
                if (c.isNotEmpty() && !c.startsWith("ERROR")) { insertOrReplace("$DEF_HOSPITAL_ID.product.cache.account", c); logD("💾 cache.account 已同步") }
            }

            val patient = extractJsonObject(json, "defaultPatient")
            if (patient != null) {
                val c = encryptByAESSync(patient)
                if (c.isNotEmpty() && !c.startsWith("ERROR")) { insertOrReplace("$DEF_HOSPITAL_ID.product.default.member", c); logD("💾 default.member 已同步") }
                val pid = extractJsonString(patient, "id")
                if (pid.isNotEmpty()) { val pc = encryptByAESSync(pid); if (pc.isNotEmpty() && !pc.startsWith("ERROR")) { insertOrReplace("$DEF_HOSPITAL_ID.product.default.peopleId", pc); logD("💾 default.peopleId 已同步: $pid") } }
            }

            // 同步 .DC 文件和 XML
            if (!skipDeviceId) {
                val devId = extractJsonString(json, "deviceId")
                if (devId.isNotEmpty()) {
                    val dcFile = findDcFile(); if (dcFile != null) { su("printf '%s' '$devId' > '$dcFile'"); logD("💾 .DC 文件已同步") }
                    val uniXml = findUniPref(); if (uniXml != null) { su("sed -i 's|<string name=\"android_device_dcloud_id\">[^<]*</string>|<string name=\"android_device_dcloud_id\">$devId</string>|' '$uniXml'"); logD("💾 Uni XML 已同步") }
                }
            }

            return true
        } catch (e: Exception) { Log.e(TAG, "writeFullSession error", e); return false }
    }

    /** 便捷方法：自动打开 DB 写入 token/session */
    private fun writeTokenToSp(newToken: String?): Boolean {
        if (newToken == null) return false
        val realDbPath = findDbPath()
        val tmpTk = tmpTokenDb.absolutePath
        val myUid = android.os.Process.myUid()
        su("rm -f '$tmpTk'")
        if (!su("cp '$realDbPath' '$tmpTk' && chown $myUid:$myUid '$tmpTk' && chmod 644 '$tmpTk'") || !File(tmpTk).exists()) return false
        try {
            val db = SQLiteDatabase.openDatabase(tmpTk, null, SQLiteDatabase.OPEN_READWRITE)
            val tn = findTable(db)
            val ok = if (tn != null) {
                if (isFullSessionJson(newToken)) writeFullSession(newToken, db, tn)
                else writeTokenOnly(newToken, db, tn)
            } else false
            db.close()
            if (ok) {
                val dbUid = suOut("stat -c '%u' '$realDbPath'").trim()
                val dbGid = suOut("stat -c '%g' '$realDbPath'").trim()
                val cp = su("cp '$tmpTk' '$realDbPath'")
                if (cp && dbUid.isNotEmpty()) { val g = if (dbGid.isNotEmpty()) dbGid else dbUid; su("chown $dbUid:$g '$realDbPath'"); su("chmod 660 '$realDbPath'") }
            }
            return ok
        } catch (e: Exception) { Log.e(TAG, "writeTokenToSp error", e); return false }
    }

    private fun readToken() {
        try {
            val tokenKey = "$DEF_HOSPITAL_ID.product.app.session"
            val realDbPath = findDbPath()
            val tmp = tmpTokenDb.absolutePath
            val myUid = android.os.Process.myUid()
            su("rm -f '$tmp'")
            if (!su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'") || !File(tmp).exists()) {
                logD("⚠️ 复制 DCStorage 失败（token 读取）"); ui { binding.tvValUni.text = "读取失败" }; return
            }
            val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)
            val tableName = findTable(db)
            if (tableName == null) { db.close(); logD("⚠️ 未找到主表"); ui { binding.tvValUni.text = "未找到" }; return }
            val cur = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf(tokenKey))
            if (!cur.moveToFirst()) { cur.close(); db.close(); logD("⚠️ 无 app.session 键"); ui { binding.tvValUni.text = "无数据" }; return }
            val cipher = cur.getString(0); cur.close(); db.close()
            logD("🔐 Token 密文长度=${cipher.length}")
            val plain = decryptByAesSync(cipher)
            if (plain.isEmpty() || plain.startsWith("ERROR")) { logD("🔴 Token 解密失败"); ui { binding.tvValUni.text = "解密失败" }; return }
            val tokenValue = extractTokenFromJson(plain)
            logD("🎫 Token: ${if (tokenValue.length <= 60) tokenValue else tokenValue.take(25) + "…" + tokenValue.takeLast(25)}")
            ui { binding.tvValUni.text = if (tokenValue.isNotEmpty()) tokenValue else "(空)" }
        } catch (e: Exception) { Log.e(TAG, "read token error", e); ui { binding.tvValUni.text = "读取错误" } }
    }

    private fun readAccountInfo() {
        try {
            logD("👤 正在读取账号信息...")
            val realDbPath = findDbPath()
            val tmp = tmpTokenDb.absolutePath
            val myUid = android.os.Process.myUid()
            su("rm -f '$tmp'")
            if (!su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'") || !File(tmp).exists()) {
                logD("⚠️ 复制 DCStorage 失败（账号读取）"); return
            }
            val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)
            val tableName = findTable(db)
            if (tableName == null) { db.close(); logD("⚠️ 未找到主表（账号读取）"); return }
            logD("📋 账号表: $tableName")

            // 手机号
            var phoneOk = false
            val c1 = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf("$DEF_HOSPITAL_ID.product.cache.account"))
            if (c1.moveToFirst()) {
                val p = decryptByAesSync(c1.getString(0))
                logD("🔓 cache.account: $p")
                val m = Regex("\"?mobile\"?\\s*:\\s*\"?([^\",}]+)\"?").find(p)?.groupValues?.get(1) ?: ""
                if (m.isNotEmpty()) { ui { binding.tvValPhone.text = "手机号：$m" }; phoneOk = true; logD("📱 $m") }
            }
            c1.close()

            // 患者姓名（主患者 + 家庭成员，顿号分隔）
            val names = mutableListOf<String>()
            val c2 = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf("$DEF_HOSPITAL_ID.product.default.member"))
            if (c2.moveToFirst()) {
                val p = decryptByAesSync(c2.getString(0))
                logD("🔓 default.member: $p")
                val n = Regex("\"?name\"?\\s*:\\s*\"?([^\",}]+)\"?").find(p)?.groupValues?.get(1) ?: ""
                if (n.isNotEmpty()) names.add(n)
            }
            c2.close()
            val c3 = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf("$DEF_HOSPITAL_ID.product.app.session"))
            if (c3.moveToFirst()) {
                val s = decryptByAesSync(c3.getString(0))
                logD("🔓 app.session: $s")
                for (m in Regex("\"?name\"?\\s*:\\s*\"?([^\",}]+)\"?").findAll(s)) {
                    val name = m.groupValues[1]
                    if (!names.contains(name)) names.add(name)
                }
            }
            c3.close(); db.close()

            if (names.isNotEmpty()) {
                ui { binding.tvValAccount.text = "患者：" + names.joinToString("、") }
                logD("👤 患者: ${names.joinToString("、")}")
            }
            if (!phoneOk && names.isEmpty()) {
                ui { binding.tvValAccount.text = "无数据" }
            }
        } catch (e: Exception) { Log.e(TAG, "readAccountInfo error", e); logD("🔴 readAccountInfo 异常: ${e.message}") }
    }

    private fun extractTokenFromJson(json: String): String {
        var j = json.trim()
        while (j.startsWith("\"") && j.endsWith("\"")) j = j.substring(1, j.length - 1)
        return Regex("\"token\"\\s*:\\s*\"([^\"]*)\"").find(j)?.groupValues?.get(1) ?: j
    }

    private fun decryptByAesSync(cipherText: String): String {
        val lock = CountDownLatch(1); val result = arrayOf("")
        ui { webView.evaluateJavascript("decryptByAes('${cipherText.replace("'", "\\'")}','$DEF_HOSPITAL_ID')") { res -> result[0] = (res ?: "").removeSurrounding("\"").replace("\\\"", ""); lock.countDown() } }
        lock.await(5, TimeUnit.SECONDS)
        return result[0]
    }

    private fun encryptByAESSync(plaintext: String): String {
        val lock = CountDownLatch(1); val result = arrayOf("")
        // 移除换行，只保留空格，然后转义单引号
        val safeText = plaintext.replace("\n", " ").replace("\r", " ").replace("'", "\\'")
        ui { webView.evaluateJavascript("encryptByAES('$DEF_HOSPITAL_ID','$safeText')") { res ->
            var v = res ?: ""
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
            v = v.replace("\\\"", "\"")
            result[0] = v; lock.countDown()
        } }
        lock.await(5, TimeUnit.SECONDS)
        if (result[0].isEmpty() || result[0] == "null") { logD("🔴 encryptByAESSync 返回异常"); return "ERROR:加密结果为空" }
        return result[0]
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
