package com.tool.dcstoragetool

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tool.dcstoragetool.databinding.ActivityMainBinding
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val TARGET_PKG      = "com.ewell.guahao.beijingguanganmen"
        const val DB_PATH         = "/data/user/0/$TARGET_PKG/databases/DCStorage"
        const val DEF_HOSPITAL_ID = "10097"
        const val DEF_DB_KEY      = "$DEF_HOSPITAL_ID.product.deviceId"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private val log = StringBuilder()

    private val tmpDb get() = File(cacheDir, "DCStorage_tmp")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etHospitalId.setText(DEF_HOSPITAL_ID)
        binding.etDbKey.setText(DEF_DB_KEY)

        // 日志区域长按可复制
        binding.tvLog.setTextIsSelectable(true)

        // 复制按钮
        binding.btnCopyLog.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("debug_log", binding.tvLog.text))
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    appendLog("✅ CryptoJS 加载完成")
                    binding.btnRead.isEnabled = true
                }
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    appendLog("❌ CryptoJS 加载失败: $description (code=$errorCode)")
                }
            }
            loadUrl("file:///android_asset/index.html")
        }
        appendLog("App 启动，加载 CryptoJS...")

        binding.btnRead.isEnabled = false
        binding.btnRead.setOnClickListener { doRead() }
        binding.btnWrite.setOnClickListener { doWrite() }
        binding.btnGenerate.setOnClickListener {
            binding.etNewUuid.setText(UUID.randomUUID().toString())
        }
    }

    // ─── Root 工具 ───────────────────────────────────────────────

    private fun suFull(cmd: String): Triple<Int, String, String> {
        return try {
            // -M (mount-master) 使用 Magisk 全局挂载命名空间，绕过 SELinux 限制
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
        val (exit, stdout, stderr) = suFull(cmd)
        appendLog("  CMD: $cmd")
        appendLog("  EXIT: $exit | OUT: ${stdout.take(200)} | ERR: ${stderr.take(200)}")
        return exit == 0
    }

    private fun suOut(cmd: String): String {
        val (_, stdout, _) = suFull(cmd)
        return stdout
    }

    // ─── 读取 ────────────────────────────────────────────────────

    private fun doRead() {
        val hid = binding.etHospitalId.text.toString().trim()
        val key = binding.etDbKey.text.toString().trim()
        log.clear()
        appendLog("========== 开始读取 ==========")
        appendLog("HospitalId: $hid")
        appendLog("DB Key: $key")
        appendLog("DB 路径: $DB_PATH")
        appendLog("临时路径: ${tmpDb.absolutePath}")
        appendLog("App UID: ${android.os.Process.myUid()}")

        Thread {
            val tmp = tmpDb.absolutePath
            val myUid = android.os.Process.myUid()

            appendLog("\n--- Step1: 删除旧临时文件 ---")
            su("rm -f '$tmp'")

            appendLog("\n--- Step2: 自动定位数据库 ---")
            // 动态搜索，兼容所有手机路径
            val findOut = suFull("find /data/user/0/$TARGET_PKG /data/data/$TARGET_PKG -name 'DCStorage' 2>/dev/null")
            appendLog("  find结果: ${findOut.second}")
            val realDbPath = findOut.second.lines().firstOrNull { it.contains("DCStorage") && !it.contains("media") }
                ?: DB_PATH
            appendLog("  使用路径: $realDbPath")

            appendLog("\n--- Step3: 复制 + chown ---")
            val cpOk = su("cp '$realDbPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")

            appendLog("\n--- Step4: 检查临时文件 ---")
            val tmpFile = File(tmp)
            appendLog("  tmpFile.exists()=${tmpFile.exists()} size=${tmpFile.length()}")
            appendLog("  canRead=${tmpFile.canRead()}")

            if (!cpOk || !tmpFile.exists()) {
                appendLog("❌ 复制失败，终止")
                return@Thread
            }

            appendLog("\n--- Step5: 打开数据库 ---")
            try {
                val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)
                appendLog("  数据库打开成功")

                appendLog("\n--- Step6: 查询所有表 ---")
                val tables = mutableListOf<String>()
                val tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                while (tc.moveToNext()) tables.add(tc.getString(0))
                tc.close()
                appendLog("  表列表: $tables")

                val tableName = findTable(db)
                appendLog("  选用表: $tableName")

                if (tableName == null) {
                    db.close(); appendLog("❌ 未找到合适表"); return@Thread
                }

                appendLog("\n--- Step7: 查询 Key ---")
                val cur = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf(key))
                appendLog("  查询结果行数: ${cur.count}")

                if (!cur.moveToFirst()) {
                    // 打印所有 key 帮助定位
                    appendLog("  ⚠️ 未找到指定 key，列出所有 key:")
                    val all = db.rawQuery("SELECT key FROM $tableName", null)
                    while (all.moveToNext()) appendLog("    > ${all.getString(0)}")
                    all.close()
                    cur.close(); db.close(); return@Thread
                }

                val raw = cur.getString(0)
                cur.close(); db.close()
                appendLog("  原始密文: $raw")

                ui {
                    binding.tvRaw.text = raw
                    appendLog("\n--- Step8: 解密 ---")
                    val escaped = raw.replace("'", "\\'")
                    webView.evaluateJavascript("decrypt('$hid','$escaped')") { res ->
                        appendLog("  解密结果: $res")
                        val plain = res?.removeSurrounding("\"") ?: ""
                        binding.tvDecrypted.text = plain
                        binding.etNewUuid.setText(plain.removeSurrounding("\""))
                        appendLog("✅ 读取完成")
                    }
                }
            } catch (e: Exception) {
                appendLog("❌ 异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    // ─── 写入 ────────────────────────────────────────────────────

    private fun doWrite() {
        val hid    = binding.etHospitalId.text.toString().trim()
        val key    = binding.etDbKey.text.toString().trim()
        val newVal = binding.etNewUuid.text.toString().trim()
        if (newVal.isEmpty()) { appendLog("❌ 请输入新的 UUID"); return }

        log.clear()
        appendLog("========== 开始写入 ==========")
        appendLog("新值: $newVal")

        val jsCall = "encrypt('$hid', '\"$newVal\"')"
        appendLog("JS调用: $jsCall")

        webView.evaluateJavascript(jsCall) { encRes ->
            val encrypted = encRes?.removeSurrounding("\"") ?: ""
            appendLog("加密结果: $encrypted")
            if (encrypted.isEmpty() || encrypted.startsWith("ERROR")) {
                appendLog("❌ 加密失败"); return@evaluateJavascript
            }
            Thread {
                val tmp = tmpDb.absolutePath
                val myUid = android.os.Process.myUid()
                // 同样动态定位数据库路径
                val findRes = suFull("find /data/user/0/$TARGET_PKG /data/data/$TARGET_PKG -name 'DCStorage' 2>/dev/null")
                val realPath = findRes.second.lines().firstOrNull { it.contains("DCStorage") && !it.contains("media") } ?: DB_PATH
                appendLog("写入目标路径: $realPath")
                su("rm -f '$tmp'")
                su("cp '$realPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")

                try {
                    val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READWRITE)
                    val tableName = findTable(db) ?: run {
                        db.close(); appendLog("❌ 未找到数据表"); return@Thread
                    }
                    val cv = ContentValues().apply { put("value", encrypted) }
                    val rows = db.update(tableName, cv, "key=?", arrayOf(key))
                    db.close()
                    appendLog("更新行数: $rows")

                    if (rows > 0) {
                        val uid = suOut("stat -c '%u' '$realPath'")
                        val ok  = su("cp '$tmp' '$realPath'")
                        if (ok && uid.isNotEmpty()) su("chown $uid:$uid '$realPath'")
                        appendLog(if (ok) "✅ 写入成功" else "❌ 写回失败")
                        ui { binding.tvDecrypted.text = newVal }
                    } else {
                        appendLog("❌ 更新行数为 0")
                    }
                } catch (e: Exception) {
                    appendLog("❌ 异常: ${e.message}")
                }
            }.start()
        }
    }

    // ─── 辅助 ────────────────────────────────────────────────────

    private fun findTable(db: SQLiteDatabase): String? {
        val cur = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
        val tables = mutableListOf<String>()
        while (cur.moveToNext()) tables.add(cur.getString(0))
        cur.close()
        appendLog("  所有表: $tables")

        // 优先选 DC_*_storage（DCloud 专属表），其次才是其他有 key/value 列的表
        val dcTable = tables.firstOrNull { it.matches(Regex("DC_\\d+_storage")) }
        val fallback = tables.firstOrNull { name ->
            if (name == "android_metadata") return@firstOrNull false
            try {
                val c = db.rawQuery("SELECT key, value FROM $name LIMIT 1", null)
                val ok = c.columnCount >= 2; c.close(); ok
            } catch (e: Exception) { false }
        }
        val match = dcTable ?: fallback
        appendLog("  选用表: $match（DC专属表: $dcTable, 备选: $fallback）")

        // 打印选中表的前几行 key
        if (match != null) {
            try {
                val c = db.rawQuery("SELECT key FROM $match LIMIT 10", null)
                appendLog("  [$match] 前10行 key:")
                while (c.moveToNext()) appendLog("    > ${c.getString(0)}")
                c.close()
            } catch (e: Exception) { }
        }
        return match
    }

    private fun appendLog(msg: String) {
        log.append(msg).append("\n")
        runOnUiThread { binding.tvLog.text = log.toString() }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
