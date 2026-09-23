package com.example.magnet115

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        setContent { MaterialTheme { App(store) } }
    }
}

data class QrToken(val uid: String, val time: String, val sign: String, val qrText: String)

class SecureStore(ctx: android.content.Context) {
    private val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val p = EncryptedSharedPreferences.create(
        ctx, "auth", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun cookie() = p.getString("cookie", "") ?: ""
    fun saveCookie(v: String) = p.edit().putString("cookie", v).apply()
    fun clear() = p.edit().clear().apply()
}

class Api {
    private val c = OkHttpClient.Builder().build()
    private val browserUa = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val alipayUa = "Mozilla/5.0 (Linux; Android 13; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/111.0 Mobile Safari/537.36 AliApp(AP/10.3.86.6000) AlipayClient/10.3.86.6000"

    suspend fun qrToken(): QrToken {
        val j = requestJson(Request.Builder().url("https://qrcodeapi.115.com/api/1.0/web/1.0/token/").build())
        val d = j.getJSONObject("data")
        return QrToken(d.getString("uid"), d.get("time").toString(), d.getString("sign"), d.getString("qrcode"))
    }

    suspend fun status(t: QrToken): Int {
        val u = HttpUrl.Builder().scheme("https").host("qrcodeapi.115.com")
            .addPathSegments("get/status/")
            .addQueryParameter("uid", t.uid)
            .addQueryParameter("time", t.time)
            .addQueryParameter("sign", t.sign)
            .addQueryParameter("_", System.currentTimeMillis().toString())
            .build()
        return requestJson(Request.Builder().url(u).build()).getJSONObject("data").getInt("status")
    }

    suspend fun loginAlipayMini(t: QrToken): String {
        val body = FormBody.Builder().add("app", "alipaymini").add("account", t.uid).build()
        val req = Request.Builder()
            .url("https://passportapi.115.com/app/1.0/alipaymini/1.0/login/qrcode/")
            .header("User-Agent", alipayUa)
            .post(body).build()

        return withContext(Dispatchers.IO) {
            c.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("登录 HTTP ${r.code}: ${raw.take(180)}")
                val j = JSONObject(raw)
                val d = j.optJSONObject("data")
                    ?: error(j.optString("message", j.optString("error", "登录响应缺少 data")))
                val cookieObj = d.optJSONObject("cookie")
                val cookie = when {
                    cookieObj != null -> cookieObj.keys().asSequence().mapNotNull { k ->
                        val v = cookieObj.optString(k)
                        if (v.isBlank()) null else "$k=$v"
                    }.joinToString("; ")
                    d.has("cookie") -> d.optString("cookie")
                    else -> ""
                }
                if (cookie.isBlank()) error("扫码已确认，但未取得 Cookie。响应：" + raw.take(250))
                cookie
            }
        }
    }

    suspend fun checkLogin(cookie: String): Boolean {
        val j = requestJson(authRequest("https://my.115.com/?ct=guide&ac=status", cookie).build())
        return jsonState(j)
    }

    suspend fun getUid(cookie: String): String {
        val j = requestJson(authRequest("https://my.115.com/?ct=ajax&ac=get_user_aq", cookie).build())
        val d = j.optJSONObject("data")
        val uid = d?.optString("uid").orEmpty().ifBlank { d?.optString("user_id").orEmpty() }
        if (uid.isBlank()) error("获取115 UID失败：" + j.toString().take(250))
        return uid
    }

    suspend fun getOfflineSign(cookie: String): Pair<String, String> {
        val url = "https://115.com/?ct=offline&ac=space&_=" + System.currentTimeMillis()
        val j = requestJson(authRequest(url, cookie).build())
        val sign = j.optString("sign")
        val time = j.opt("time")?.toString().orEmpty()
        if (sign.isBlank() || time.isBlank()) {
            val msg = j.optString("error_msg", j.optString("message", j.optString("error", "")))
            error("获取离线签名失败" + if (msg.isBlank()) "：${j.toString().take(250)}" else "：$msg")
        }
        return sign to time
    }

    suspend fun addOfflineTask(cookie: String, magnet: String): JSONObject {
        if (!checkLogin(cookie)) error("115 登录凭证已失效，请清除凭证后重新扫码登录")
        val uid = getUid(cookie)
        val (sign, time) = getOfflineSign(cookie)

        val body = FormBody.Builder()
            .add("url", magnet.trim())
            .add("uid", uid)
            .add("sign", sign)
            .add("time", time)
            .add("savepath", "")
            .add("wp_path_id", "0")
            .build()

        val req = authRequest("https://115.com/web/lixian/?ct=lixian&ac=add_task_url", cookie)
            .header("Origin", "https://115.com")
            .header("Referer", "https://115.com/?tab=offline&mode=wangpan")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body).build()

        val j = requestJson(req)
        if (!jsonState(j)) {
            val errno = j.opt("errno")?.toString() ?: j.opt("errcode")?.toString() ?: ""
            val msg = j.optString("error_msg", j.optString("message", j.optString("error", "添加离线任务失败")))
            when (errno) {
                "911" -> error("115要求安全验证，请先在115官方客户端/网页完成验证")
                "99" -> error("115登录状态已失效，请重新扫码登录")
                "10008" -> error("磁力链接无效：$msg")
                else -> error("115返回失败" + if (errno.isBlank()) "" else " [$errno]" + "：$msg")
            }
        }
        return j
    }

    private fun authRequest(url: String, cookie: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", browserUa)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")

    private fun jsonState(j: JSONObject): Boolean {
        if (!j.has("state")) return false
        return when (val v = j.opt("state")) {
            is Boolean -> v
            is Number -> v.toInt() == 1
            is String -> v.equals("true", true) || v == "1"
            else -> false
        }
    }

    private suspend fun requestJson(req: Request): JSONObject = withContext(Dispatchers.IO) {
        c.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: " + raw.take(250))
            try {
                JSONObject(raw)
            } catch (e: Exception) {
                error("115返回内容无法解析：" + raw.take(250))
            }
        }
    }
}

@Composable
fun App(store: SecureStore) {
    val api = remember { Api() }
    val scope = rememberCoroutineScope()
    var cookie by remember { mutableStateOf(store.cookie()) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf(if (cookie.isBlank()) "115 未登录" else "115 已保存登录凭证") }
    var magnet by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun beginLogin() {
        scope.launch {
            busy = true
            try {
                val t = api.qrToken()
                qr = makeQr(t.qrText)
                status = "请使用115 App扫描二维码并确认"
                repeat(120) {
                    delay(1500)
                    when (api.status(t)) {
                        1 -> status = "二维码已扫描，请在115 App中确认"
                        2 -> {
                            status = "已确认，正在获取支付宝小程序登录凭证…"
                            val ck = api.loginAlipayMini(t)
                            store.saveCookie(ck)
                            cookie = ck
                            qr = null
                            status = "115 已登录 · 支付宝小程序"
                            return@launch
                        }
                    }
                }
                status = "二维码等待超时，请重新生成"
            } catch (e: Exception) {
                status = "登录错误：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun submitMagnet() {
        val m = magnet.trim()
        if (cookie.isBlank()) {
            status = "请先扫码登录115"
            return
        }
        if (!m.startsWith("magnet:?", true)) {
            status = "请输入有效的 magnet 磁力链接"
            return
        }

        scope.launch {
            busy = true
            status = "正在提交到115离线下载…"
            try {
                val result = api.addOfflineTask(cookie, m)
                val name = result.optString("name")
                status = if (name.isBlank())
                    "✓ 已成功提交到115离线下载"
                else
                    "✓ 提交成功：$name"
            } catch (e: Exception) {
                status = "提交失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("115 Magnet", style = MaterialTheme.typography.headlineMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(status)
                    if (cookie.isBlank()) {
                        Button(onClick = { beginLogin() }, enabled = !busy) {
                            Text(if (busy) "等待扫码…" else "扫码登录115")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                store.clear()
                                cookie = ""
                                qr = null
                                status = "115 未登录"
                            },
                            enabled = !busy
                        ) {
                            Text("清除本机登录凭证")
                        }
                    }
                }
            }

            qr?.let {
                Image(
                    it.asImageBitmap(),
                    "115登录二维码",
                    Modifier.size(260.dp).align(Alignment.CenterHorizontally)
                )
            }

            OutlinedTextField(
                value = magnet,
                onValueChange = { magnet = it },
                label = { Text("磁力链接") },
                placeholder = { Text("magnet:?xt=urn:btih:...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )

            Button(
                onClick = { submitMagnet() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "处理中…" else "转存到115")
            }

            Text(
                "V0.2 · 支付宝小程序扫码登录 · 115离线任务提交",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

fun makeQr(text: String, size: Int = 800): Bitmap {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
        }
    }
    return bitmap
}
