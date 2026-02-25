package com.tool.dcstoragetool

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
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

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG             = "DCStorageTool"
        const val TARGET_PKG      = "com.ewell.guahao.beijingguanganmen"
        const val DB_PATH         = "/data/user/0/$TARGET_PKG/databases/DCStorage"
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
        binding.btnPaste.setOnClickListener {
            val cm   = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                binding.etNewUuid.setText(text)
                binding.etNewUuid.setSelection(text.length)
            } else {
                toast("剪贴板为空")
            }
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

    // ─── 读取 ────────────────────────────────────────────────────

    private fun doRead() {
        Thread {
            val tmp   = tmpDb.absolutePath
            val myUid = android.os.Process.myUid()

            su("rm -f '$tmp'")

            val findOut    = suFull("find /data/user/0/$TARGET_PKG /data/data/$TARGET_PKG -name 'DCStorage' 2>/dev/null")
            val realDbPath = findOut.second.lines()
                .firstOrNull { it.contains("DCStorage") && !it.contains("media") } ?: DB_PATH

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
                        binding.etNewUuid.setText(plain)
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

        webView.evaluateJavascript("encrypt('$DEF_HOSPITAL_ID', '\"$newVal\"')") { encRes ->
            val encrypted = encRes?.removeSurrounding("\"") ?: ""
            if (encrypted.isEmpty() || encrypted.startsWith("ERROR")) {
                toast("加密失败"); return@evaluateJavascript
            }

            Thread {
                val tmp   = tmpDb.absolutePath
                val myUid = android.os.Process.myUid()

                val findRes  = suFull("find /data/user/0/$TARGET_PKG /data/data/$TARGET_PKG -name 'DCStorage' 2>/dev/null")
                val realPath = findRes.second.lines()
                    .firstOrNull { it.contains("DCStorage") && !it.contains("media") } ?: DB_PATH

                su("rm -f '$tmp'")
                su("cp '$realPath' '$tmp' && chown $myUid:$myUid '$tmp' && chmod 644 '$tmp'")

                try {
                    val db        = SQLiteDatabase.openDatabase(tmp, null, SQLiteDatabase.OPEN_READWRITE)
                    val tableName = findTable(db) ?: run {
                        db.close(); ui { toast("未找到数据表") }; return@Thread
                    }
                    val cv   = ContentValues().apply { put("value", encrypted) }
                    val rows = db.update(tableName, cv, "key=?", arrayOf(DEF_DB_KEY))
                    db.close()

                    if (rows > 0) {
                        val uid = suOut("stat -c '%u' '$realPath'")
                        val ok  = su("cp '$tmp' '$realPath'")
                        if (ok && uid.isNotEmpty()) su("chown $uid:$uid '$realPath'")
                        ui {
                            if (ok) {
                                binding.tvDecrypted.text = newVal
                                toast("写入成功")
                            } else {
                                toast("写回失败")
                            }
                        }
                    } else {
                        ui { toast("未找到对应 Key，写入失败") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "write error", e)
                    ui { toast("写入错误: ${e.message}") }
                }
            }.start()
        }
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
        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
