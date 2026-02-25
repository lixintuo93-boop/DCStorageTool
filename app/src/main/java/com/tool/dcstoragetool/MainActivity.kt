package com.tool.dcstoragetool

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.tool.dcstoragetool.databinding.ActivityMainBinding
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val TARGET_PKG  = "com.ewell.guahao.beijingguanganmen"
        const val DB_PATH     = "/data/data/$TARGET_PKG/databases/DCStorage"
        const val DEF_HOSPITAL_ID = "10097"
        const val DEF_DB_KEY  = "$DEF_HOSPITAL_ID.product.deviceId"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView
    private var jsReady = false

    // 复制到 App 私有目录，无需任何存储权限
    private val tmpDb get() = File(cacheDir, "DCStorage_tmp")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 预填默认值
        binding.etHospitalId.setText(DEF_HOSPITAL_ID)
        binding.etDbKey.setText(DEF_DB_KEY)
        binding.tvStatus.text = "⏳ 加载 CryptoJS..."

        // 隐藏 WebView，仅用于 JS 加解密
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    jsReady = true
                    binding.tvStatus.text = "✅ 就绪，可以读取数据"
                    binding.btnRead.isEnabled = true
                }
            }
            loadUrl("file:///android_asset/index.html")
        }

        binding.btnRead.isEnabled = false
        binding.btnRead.setOnClickListener { doRead() }
        binding.btnWrite.setOnClickListener { doWrite() }
        binding.btnGenerate.setOnClickListener {
            binding.etNewUuid.setText(UUID.randomUUID().toString())
        }
    }

    // ─── Root 工具 ───────────────────────────────────────────────

    private fun su(cmd: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                .also { it.waitFor() }.exitValue() == 0
        } catch (e: Exception) { false }
    }

    private fun suOut(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        } catch (e: Exception) { "" }
    }

    // ─── 读取 ────────────────────────────────────────────────────

    private fun doRead() {
        val hid = binding.etHospitalId.text.toString().trim()
        val key = binding.etDbKey.text.toString().trim()
        if (hid.isEmpty() || key.isEmpty()) { status("❌ 请填写 Hospital ID 和 存储 Key"); return }

        status("⏳ 复制数据库...")
        Thread {
            val tmp = tmpDb.absolutePath
            // 获取本 App 的 UID，复制后 chown 给自己，无需存储权限
            val myUid = android.os.Process.myUid()
            su("rm -f '$tmp'")
            if (!su("cp '$DB_PATH' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")) {
                ui { status("❌ 复制失败，请确认已 ROOT 且目标 App 已安装") }
                return@Thread
            }
            try {
                val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READONLY)

                // 自动探测表名
                val tableName = findTable(db)
                if (tableName == null) {
                    db.close()
                    ui { status("❌ 未找到合适的数据表，请检查数据库结构") }
                    return@Thread
                }

                val cur = db.rawQuery("SELECT value FROM $tableName WHERE key=?", arrayOf(key))
                if (!cur.moveToFirst()) {
                    cur.close(); db.close()
                    ui { status("❌ 未找到 key: $key（表: $tableName）") }
                    return@Thread
                }
                val raw = cur.getString(0)
                cur.close(); db.close()

                ui {
                    binding.tvRaw.text = raw
                    status("⏳ 解密中...")
                    val escaped = raw.replace("'", "\\'")
                    webView.evaluateJavascript("decrypt('$hid','$escaped')") { res ->
                        val plain = res?.removeSurrounding("\"") ?: ""
                        binding.tvDecrypted.text = plain
                        binding.etNewUuid.setText(plain.removeSurrounding("\""))
                        status("✅ 读取成功（表: $tableName）")
                    }
                }
            } catch (e: Exception) {
                ui { status("❌ 数据库错误: ${e.message}") }
            }
        }.start()
    }

    // ─── 写入 ────────────────────────────────────────────────────

    private fun doWrite() {
        val hid    = binding.etHospitalId.text.toString().trim()
        val key    = binding.etDbKey.text.toString().trim()
        val newVal = binding.etNewUuid.text.toString().trim()
        if (newVal.isEmpty()) { status("❌ 请输入新的 UUID"); return }

        status("⏳ 加密新值...")
        // 原 app 存储的是 JSON.stringify(uuid) = '"uuid"'
        val jsCall = "encrypt('$hid', '\"$newVal\"')"
        webView.evaluateJavascript(jsCall) { encRes ->
            val encrypted = encRes?.removeSurrounding("\"") ?: ""
            if (encrypted.isEmpty() || encrypted.startsWith("ERROR")) {
                status("❌ 加密失败: $encRes"); return@evaluateJavascript
            }
            Thread {
                val tmp = tmpDb.absolutePath
                val myUid = android.os.Process.myUid()
                su("rm -f '$tmp'")
                su("cp '$DB_PATH' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")
                try {
                    val db = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READWRITE)
                    val tableName = findTable(db) ?: run {
                        db.close(); ui { status("❌ 未找到数据表") }; return@Thread
                    }
                    val cv = ContentValues().apply { put("value", encrypted) }
                    val rows = db.update(tableName, cv, "key=?", arrayOf(key))
                    db.close()

                    if (rows > 0) {
                        val uid = suOut("stat -c '%u' '$DB_PATH'")
                        val ok  = su("cp '$tmp' '$DB_PATH'")
                        if (ok && uid.isNotEmpty()) su("chown $uid:$uid '$DB_PATH'")
                        ui { status(if (ok) "✅ 写入成功！\n新 UUID: $newVal" else "⚠️ 写回失败，请手动检查 Root 权限") }
                    } else {
                        ui { status("❌ 更新行数为 0，key 不存在或表结构有误") }
                    }
                } catch (e: Exception) {
                    ui { status("❌ 写入异常: ${e.message}") }
                }
            }.start()
        }
    }

    // ─── 辅助 ────────────────────────────────────────────────────

    /** 自动检测包含 key/value 列的表名 */
    private fun findTable(db: SQLiteDatabase): String? {
        val candidates = mutableListOf<String>()
        val cur = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
        while (cur.moveToNext()) candidates.add(cur.getString(0))
        cur.close()
        return candidates.firstOrNull { name ->
            try {
                val c = db.rawQuery("SELECT key, value FROM $name LIMIT 1", null)
                val ok = c.columnCount >= 2
                c.close(); ok
            } catch (e: Exception) { false }
        }
    }

    private fun status(msg: String) { binding.tvStatus.text = msg }
    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
