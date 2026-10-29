package com.netease.lite.api

import okhttp3.Cookie
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * 网易云 HTTP 层（`netease-lite\electron\api\client.js` 的 Kotlin 移植）。
 *
 * 两种方言：
 *   - 明文 GET /api/...      只读接口，带 Cookie 就够
 *   - 加密 POST /weapi/...   登录与一切改状态的接口，body 由 [Weapi] 包出来
 *
 * 另有少数接口只在 App 方言（interface.music.163.com，明文 POST）下正常，
 * 见 [postApp]。
 *
 * Cookie 存在 Map 里，登录拿到 MUSIC_U 后整段会话都靠它 —— 和浏览器一样。
 */
object Http {
    const val BASE = "https://music.163.com"
    /** App 方言域名，少数 weapi 下永远失败的接口只在这里正常（见 [postApp]）。 */
    const val APP_BASE = "https://interface.music.163.com"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val APP_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.0.18.203152"
}

/** 带 code / payload 的接口异常，对应 JS 版的 `ApiError`。 */
class ApiException(val code: Int, message: String?, val payload: JSONObject? = null) :
    Exception(message ?: "API error $code")

/**
 * 请求失败的一种特殊情形：网络层就断了（超时、DNS、连接重置），
 * 服务端一个字节都没回。上层要把它和「服务端明确说 code 404」区分开 ——
 * 前者应该提示「网络连接失败」，后者才是「无版权或已下架」。
 * 对应桌面版 `electron\api\endpoints.js` 里的 `transportFailure()`。
 */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 极简 cookie jar：只按名存值，不做 domain/path 匹配（我们只跟两个固定域名打交道）。 */
object CookieJar {
    private val map = LinkedHashMap<String, String>()

    @Synchronized
    fun setCookieString(str: String?) {
        map.clear()
        if (str.isNullOrBlank()) return
        for (part in str.split(';')) {
            val i = part.indexOf('=')
            if (i > 0) map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
    }

    @Synchronized
    fun getCookieString(): String = map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    @Synchronized
    fun has(key: String): Boolean = map.containsKey(key)

    @Synchronized
    fun get(key: String): String? = map[key]

    /** 补一个「环境标识」类的 cookie（如 `os=pc`），已有值时不覆盖。 */
    @Synchronized
    fun setIfAbsent(key: String, value: String) {
        if (!map.containsKey(key)) map[key] = value
    }

    /** 供 App 方言自己拼 Cookie 头用。 */
    @Synchronized
    fun snapshot(): Map<String, String> = LinkedHashMap(map)

    val csrf: String get() = get("__csrf") ?: ""

    /** 手里握着真正的登录票据。 */
    val isLoggedIn: Boolean get() = has("MUSIC_U")

    /** 吸收响应里的 Set-Cookie。 */
    fun absorb(headers: List<String>) {
        for (raw in headers) {
            val kv = raw.substringBefore(';')
            val i = kv.indexOf('=')
            if (i > 0) {
                val k = kv.substring(0, i).trim()
                val v = kv.substring(i + 1).trim()
                if (v.isEmpty() || v.equals("EXPIRED", ignoreCase = true)) map.remove(k)
                else map[k] = v
            }
        }
    }
}

/**
 * 小写十六进制 MD5。目前只有密码登录用得上。
 *
 * 服务端 `/weapi/login/cellphone` 收的 `password` 是**已经 MD5 过的那串**
 * （参考实现里写的是 `query.md5_password || MD5(query.password)`，直连 weapi 时
 * 得自己先算）。发明文过去服务端算出来是另一个哈希，必然回 502「账号或密码错误」——
 * 桌面版就是这个毛病，见 netease-lite 的 `electron/api/crypto.js`。
 */
fun md5Hex(text: String): String {
    val digest = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** 用 OkHttp 的 Cookie 类喂给 Coil / ExoPlayer 那些只认 okhttp3.Cookie 的地方。 */
fun cookiesFor(url: HttpUrl): List<Cookie> =
    CookieJar.snapshot().mapNotNull { (k, v) ->
        Cookie.Builder().name(k).value(v).domain(url.host).path("/").build()
    }
