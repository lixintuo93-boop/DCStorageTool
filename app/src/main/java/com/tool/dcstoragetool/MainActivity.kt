package com.tool.dcstoragetool

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tool.dcstoragetool.databinding.ActivityMainBinding
import java.io.File
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
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    private val tmpDb get() = File(cacheDir, "DCStorage_tmp")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "CryptoJS loaded, auto reading...")
                    doRead()
                }
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e(TAG, "CryptoJS error: $description")
                    toast("CryptoJS 加载失败")
                }
            }
            loadUrl("file:///android_asset/index.html")
        }

        binding.btnWrite.setOnClickListener { doWrite() }
        binding.btnGenerate.setOnClickListener {
            binding.etNewUuid.setText(UUID.randomUUID().toString())
        }
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
    // 不同机器的目标 App 把同一个 deviceId 存成不同形态：
    //   一加: 36 字符带 4 个 "-"（标准 UUID）
    //   小米: 32 字符纯 hex，不带 "-"，且大小写也不同
    // 这里检测每个存储位置的现存格式，再把用户输入的新 UUID 转成同样的形态。

    private data class UuidFmt(val dashes: Boolean, val upper: Boolean)

    private fun detectFmt(s: String): UuidFmt {
        val t = s.trim().trim('"')
        val dashes = t.length == 36 && t.count { it == '-' } == 4
        val core = t.replace("-", "")
        val hasUpper = core.any { it.isLetter() && it.isUpperCase() }
        val hasLower = core.any { it.isLetter() && it.isLowerCase() }
        val upper = hasUpper && !hasLower
        return UuidFmt(dashes, upper)
    }

    private fun formatAs(uuidIn: String, fmt: UuidFmt): String {
        val core = uuidIn.replace("-", "")
            .let { if (fmt.upper) it.uppercase() else it.lowercase() }
        if (core.length != 32) return uuidIn
        return if (fmt.dashes) {
            "${core.substring(0,8)}-${core.substring(8,12)}-${core.substring(12,16)}-${core.substring(16,20)}-${core.substring(20,32)}"
        } else core
    }

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
            val tmp   = tmpDb.absolutePath
            val myUid = android.os.Process.myUid()

            su("rm -f '$tmp'")

            val realDbPath = findDbPath()

            val cpOk    = su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")
            val tmpFile = File(tmp)

            if (!cpOk || !tmpFile.exists()) {
                ui { binding.tvDecrypted.text = "—"; toast("复制数据库失败") }
                return@Thread
            }

            try {
                val db        = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)
                val tableName = findTable(db)
                if (tableName == null) {
                    db.close()
                    ui { binding.tvDecrypted.text = "—"; toast("未找到数据表") }
                    return@Thread
                }

                val cur = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf(DEF_DB_KEY))
                if (!cur.moveToFirst()) {
                    cur.close(); db.close()
                    ui { binding.tvDecrypted.text = "—"; toast("未找到 Key") }
                    return@Thread
                }

                val raw = cur.getString(0)
                cur.close(); db.close()

                ui {
                    val escaped = raw.replace("'", "\\'")
                    webView.evaluateJavascript("decrypt('$DEF_HOSPITAL_ID','$escaped')") { res ->
                        val plain = res
                            ?.removeSurrounding("\"")
                            ?.replace("\\\"", "")
                            ?: ""
                        binding.tvDecrypted.text = plain
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "read error", e)
                ui { binding.tvDecrypted.text = "—"; toast("读取错误: ${e.message}") }
            }
        }.start()
    }

    // ─── 写入 ────────────────────────────────────────────────────

    private fun doWrite() {
        val newVal = binding.etNewUuid.text.toString().trim()
        if (newVal.isEmpty()) { toast("请输入新的 UUID"); return }

        Thread {
            // 1. 先把目标 App 干掉，避免内存中持有旧值反向覆盖
            su("am force-stop $TARGET_PKG")

            // 2. 定位三处存储
            val realDbPath = findDbPath()
            val dcFile     = findDcFile()
            val uniXml     = findUniPref()
            Log.d(TAG, "paths db=$realDbPath dc=$dcFile uni=$uniXml")

            // 3. 把 DCStorage 拷到本地，并读取旧密文
            val tmp   = tmpDb.absolutePath
            val myUid = android.os.Process.myUid()
            su("rm -f '$tmp'")
            val cpOk = su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")
            if (!cpOk || !File(tmp).exists()) {
                ui { toast("复制数据库失败") }; return@Thread
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
            }
            if (tableName == null) {
                ui { toast("未找到数据表") }; return@Thread
            }

            // 4. 读 .DC*.txt 和 __UNI__*.xml 中现有值（明文），用于检测格式
            val dcOld = dcFile?.let { suOut("cat '$it' 2>/dev/null").trim() } ?: ""
            val uniOld = uniXml?.let {
                suOut("sed -n 's|.*<string name=\"android_device_dcloud_id\">\\([^<]*\\)</string>.*|\\1|p' '$it' 2>/dev/null").trim()
            } ?: ""

            // 5. 解密旧 DCStorage 值 -> 检测 DCStorage 当前的格式
            val dbOldPlain = oldEncrypted?.let { decryptSync(it) } ?: ""
            val fmtDb  = if (dbOldPlain.isNotEmpty())  detectFmt(dbOldPlain) else UuidFmt(true,  false)
            val fmtDc  = if (dcOld.isNotEmpty())       detectFmt(dcOld)      else UuidFmt(false, false)
            val fmtUni = if (uniOld.isNotEmpty())      detectFmt(uniOld)     else UuidFmt(false, true)

            val newForDb  = formatAs(newVal, fmtDb)
            val newForDc  = formatAs(newVal, fmtDc)
            val newForUni = formatAs(newVal, fmtUni)
            Log.d(TAG, "fmt db=$fmtDb dc=$fmtDc uni=$fmtUni")
            Log.d(TAG, "new db=$newForDb dc=$newForDc uni=$newForUni")

            // 6. 加密新 DCStorage 值
            val newEncrypted = encryptSync(newForDb)
            if (newEncrypted.isEmpty() || newEncrypted.startsWith("ERROR")) {
                ui { toast("加密失败") }; return@Thread
            }

            // 7. 写 DCStorage
            try {
                val db   = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READWRITE)
                val cv   = ContentValues().apply { put("value", newEncrypted) }
                val rows = db.update(tableName, cv, "key=?", arrayOf(DEF_DB_KEY))
                db.close()
                if (rows <= 0) {
                    ui { toast("DCStorage 中未找到 Key") }; return@Thread
                }
            } catch (e: Exception) {
                Log.e(TAG, "write db error", e)
                ui { toast("写入 DCStorage 失败: ${e.message}") }; return@Thread
            }

            val dbUid = suOut("stat -c '%u' '$realDbPath'").trim()
            val dbGid = suOut("stat -c '%g' '$realDbPath'").trim()
            val dbWriteOk = su("cp '$tmp' '$realDbPath'")
            if (dbWriteOk && dbUid.isNotEmpty()) {
                val g = if (dbGid.isNotEmpty()) dbGid else dbUid
                su("chown $dbUid:$g '$realDbPath'")
                su("chmod 660 '$realDbPath'")
            }

            // 8. 删 DCStorage 的 WAL/SHM/journal，防止重放
            su("rm -f '${realDbPath}-journal' '${realDbPath}-shm' '${realDbPath}-wal'")

            // 9. 写 .DC*.txt
            val dcOk = if (dcFile != null) {
                val uid = suOut("stat -c '%u' '$dcFile'").trim()
                val gid = suOut("stat -c '%g' '$dcFile'").trim()
                val ok  = su("printf '%s' '$newForDc' > '$dcFile'")
                if (ok && uid.isNotEmpty()) {
                    val g = if (gid.isNotEmpty()) gid else uid
                    su("chown $uid:$g '$dcFile'")
                    su("chmod 600 '$dcFile'")
                }
                ok
            } else false

            // 10. 写 __UNI__*.xml 的 android_device_dcloud_id
            val uniOk = if (uniXml != null && uniOld.isNotEmpty()) {
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
                ok
            } else false

            ui {
                binding.tvDecrypted.text = newForDb
                val sb = StringBuilder("写入完成\n")
                sb.append("DCStorage: ").append(if (dbWriteOk) "✓ $newForDb" else "✗").append('\n')
                sb.append("DCFile: ")
                  .append(if (dcFile == null) "未找到" else if (dcOk) "✓ $newForDc" else "✗")
                  .append('\n')
                sb.append("UniPref: ")
                  .append(if (uniXml == null) "未找到" else if (uniOk) "✓ $newForUni" else if (uniOld.isEmpty()) "未含该 Key" else "✗")
                toast(sb.toString())
            }
        }.start()
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
        val match = dcTable ?: fallback
        Log.d(TAG, "tables=$tables selected=$match")
        return match
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
