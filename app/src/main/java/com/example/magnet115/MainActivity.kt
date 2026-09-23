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
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SecureStore(this)
        setContent { MaterialTheme { App(store) } }
    }
}

data class QrToken(val uid:String, val time:String, val sign:String, val qrText:String)

class SecureStore(ctx: android.content.Context) {
    private val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val p = EncryptedSharedPreferences.create(
        ctx, "auth", key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun cookie() = p.getString("cookie", "") ?: ""
    fun saveCookie(v:String) = p.edit().putString("cookie", v).apply()
    fun clear() = p.edit().clear().apply()
}

class Api {
    private val c = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).build()

    suspend fun qrToken(): QrToken = requestJson(
        Request.Builder().url("https://qrcodeapi.115.com/api/1.0/web/1.0/token/").build()
    ).let {
        val d=it.getJSONObject("data")
        QrToken(d.getString("uid"), d.get("time").toString(), d.getString("sign"), d.getString("qrcode"))
    }

    suspend fun status(t:QrToken): Int {
        val u=HttpUrl.Builder().scheme("https").host("qrcodeapi.115.com")
            .addPathSegments("get/status/")
            .addQueryParameter("uid",t.uid).addQueryParameter("time",t.time)
            .addQueryParameter("sign",t.sign).build()
        return requestJson(Request.Builder().url(u).build()).getJSONObject("data").getInt("status")
    }

    suspend fun loginAlipayMini(t:QrToken): String {
        val body=FormBody.Builder().add("app","alipaymini").add("account",t.uid).build()
        val req=Request.Builder()
            .url("https://passportapi.115.com/app/1.0/alipaymini/1.0/login/qrcode/")
            .post(body).build()
        return withContext(Dispatchers.IO) {
            c.newCall(req).execute().use { r ->
                if(!r.isSuccessful) error("HTTP ${r.code}")
                val raw=r.body?.string().orEmpty()
                val j=JSONObject(raw)
                if(!j.optBoolean("state", j.optInt("code")==0)) error(j.optString("message","登录失败"))
                // Current responses commonly expose cookie fields in data.cookie.
                // Keep parsing defensive because this is not a stable public Android SDK.
                val d=j.optJSONObject("data")
                val cookie = when {
                    d?.optJSONObject("cookie") != null -> {
                        val x=d.getJSONObject("cookie")
                        x.keys().asSequence().joinToString("; ") { "$it=${x.optString(it)}" }
                    }
                    d?.has("cookie") == true -> d.optString("cookie")
                    else -> ""
                }
                if(cookie.isBlank()) error("登录成功，但响应中未发现 Cookie；请保留错误信息用于适配。")
                cookie
            }
        }
    }

    private suspend fun requestJson(req:Request)=withContext(Dispatchers.IO) {
        c.newCall(req).execute().use { r ->
            if(!r.isSuccessful) error("HTTP ${r.code}")
            JSONObject(r.body?.string().orEmpty())
        }
    }
}

@Composable
fun App(store:SecureStore) {
    val api=remember { Api() }
    val scope=rememberCoroutineScope()
    var cookie by remember { mutableStateOf(store.cookie()) }
    var token by remember { mutableStateOf<QrToken?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf(if(cookie.isBlank()) "115 未登录" else "115 已保存登录凭证") }
    var magnet by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun beginLogin() {
        scope.launch {
            busy=true
            try {
                val t=api.qrToken(); token=t
                qr=makeQr(t.qrText)
                status="请使用115 App扫描二维码并确认"
                // Poll until confirmed. Observed status=2 means confirmed.
                repeat(120) {
                    delay(1500)
                    val s=api.status(t)
                    if(s==2) {
                        status="已确认，正在获取支付宝小程序登录凭证…"
                        val ck=api.loginAlipayMini(t)
                        store.saveCookie(ck); cookie=ck
                        qr=null
                        status="115 已登录 · 设备身份：支付宝小程序"
                        return@launch
                    }
                }
                status="二维码等待超时，请重新生成"
            } catch(e:Exception) {
                status="登录错误：${e.message}"
            } finally { busy=false }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("115 Magnet", style=MaterialTheme.typography.headlineMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(status)
                    if(cookie.isBlank()) {
                        Button(onClick={beginLogin()}, enabled=!busy) { Text(if(busy) "等待扫码…" else "扫码登录115") }
                    } else {
                        OutlinedButton(onClick={store.clear();cookie="";status="115 未登录"}) { Text("清除本机登录凭证") }
                    }
                }
            }
            qr?.let {
                Image(it.asImageBitmap(), "115登录二维码",
                    Modifier.size(260.dp).align(Alignment.CenterHorizontally))
            }
            OutlinedTextField(
                value=magnet, onValueChange={magnet=it},
                label={Text("磁力链接")},
                placeholder={Text("magnet:?xt=urn:btih:...")},
                modifier=Modifier.fillMaxWidth(),
                minLines=3,
                keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.None)
            )
            Button(
                onClick={ status = if(cookie.isBlank()) "请先登录115"
                    else if(!magnet.trim().startsWith("magnet:?")) "请输入有效的 magnet 链接"
                    else "登录链路已就绪。下一版接入115离线签名与提交接口。" },
                modifier=Modifier.fillMaxWidth()
            ) { Text("转存到115") }
            Text("V0.1：先验证 alipaymini 扫码登录与本地凭证保存。")
        }
    }
}

fun makeQr(text:String, size:Int=800):Bitmap {
    val m:BitMatrix=MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE,size,size)
    val b=Bitmap.createBitmap(size,size,Bitmap.Config.RGB_565)
    for(x in 0 until size) for(y in 0 until size)
        b.setPixel(x,y,if(m[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return b
}
