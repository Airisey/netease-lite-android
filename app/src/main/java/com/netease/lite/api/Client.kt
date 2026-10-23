package com.netease.lite.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 网易云 HTTP 客户端（`netease-lite\electron\api\client.js` 的 Kotlin 实现）。
 *
 * 两种方言：
 *   - 明文 GET  /api/...     只读接口，Cookie 够用
 *   - 加密 POST /weapi/...   登录与改状态的接口，body 由 [Weapi] 包出来
 *
 * 另有极少数接口只在「App 方言」下正常，见 [postApp]。关注用户是典型例子：
 * weapi 永远回 code 250，App 方言就通。
 *
 * 所有方法都是 suspend；网络调用统一走 [Dispatchers.IO]。
 */
object Client {

    /** 共享连接池：Coil 与 ExoPlayer 也用这个 client，省掉重复握手。 */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    private fun baseHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = Http.UA
        h["Referer"] = Http.BASE + "/"
        h["Origin"] = Http.BASE
        h["Accept-Language"] = "zh-CN,zh;q=0.9"
        h["Accept"] = "*/*"
        h.putAll(extra)
        val cookie = CookieJar.getCookieString()
        if (cookie.isNotEmpty()) h["Cookie"] = cookie
        return h
    }

    /** 把响应里的 Set-Cookie 收进 jar。OkHttp 的 headers 可能有多个同名项。 */
    private fun absorb(headers: okhttp3.Headers) {
        val list = headers.values("Set-Cookie")
        if (list.isNotEmpty()) CookieJar.absorb(list)
    }

    /**
     * 发一次请求。**只有连一个字节都没收到**（IOException）才抛 [TransportException] ——
     * 这层区分很重要：服务端明确回 code 200 但业务失败，和「网络断了」要给出完全
     * 不同的提示。桌面版没有这层区分，导致断网时误报「无版权或已下架」。
     */
    private suspend fun send(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { res ->
                absorb(res.headers)
                res.body?.string() ?: ""
            }
        } catch (e: IOException) {
            throw TransportException(transportMessage(e), e)
        }
    }

    /**
     * 把网络异常翻成一句用户能看懂的话（用户 m07698 第②点）。
     *
     * 原来直接把 `e.message` 往上抛，实测断网时整条链路最后显示给用户的是
     * `timeout` 六个字母 —— 既没说是网络问题，也没说该等还是该换个网。
     * 异常类型才是信息，消息往往只是 OkHttp 内部一句短语。
     *
     * 这里是**唯一**的翻译出口：`tryPost` / `tryGet` / 下载队列的取流阶段全都
     * 经由它取文案，单独在界面层做映射会漏掉其中一条路径。
     */
    private fun transportMessage(e: IOException): String = when (e) {
        is SocketTimeoutException -> "连接超时：网络不通或太慢，检查网络后重试"
        is UnknownHostException -> "无法解析服务器地址：当前没有网络"
        is ConnectException -> "连接被拒绝：服务器暂时不可达"
        is SSLException -> "HTTPS 握手失败：可能是网络被劫持，换个网络再试"
        else -> {
            val m = e.message ?: ""
            when {
                m.contains("timeout", true) -> "连接超时：网络不通或太慢，检查网络后重试"
                m.contains("Unable to resolve host", true) -> "无法解析服务器地址：当前没有网络"
                m.contains("Failed to connect", true) -> "连接被拒绝：服务器暂时不可达"
                m.contains("closed", true) -> "连接被中断，请重试"
                else -> "网络请求失败（${m.ifEmpty { e.javaClass.simpleName }}）"
            }
        }
    }

    /** 明文 GET。`params` 是查询串键值，null / 空串会被跳过。 */
    suspend fun get(path: String, params: Map<String, Any?> = emptyMap(), base: String = Http.BASE): JSONObject {
        val qs = params.entries
            .filter { it.value != null }
            .joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value.toString())}" }
        val url = base + path + if (qs.isNotEmpty()) "?$qs" else ""
        val text = send(Request.Builder().url(url).headers(headersOf(baseHeaders())).get().build())
        return parse(text, path, lenient = false)
    }

    /** 加密 POST /weapi/。 */
    suspend fun post(path: String, data: JSONObject = JSONObject(), lenient: Boolean = false): JSONObject {
        val clean = path.removePrefix("/weapi/").removePrefix("/")
        val url = "${Http.BASE}/weapi/$clean?csrf_token=${CookieJar.csrf}"
        val form = FormBody.Builder()
        for ((k, v) in Weapi.encode(data.toString())) form.add(k, v)
        val text = send(
            Request.Builder().url(url).headers(headersOf(baseHeaders())).post(form.build()).build(),
        )
        return parse(text, path, lenient)
    }

    /**
     * App 方言：明文 POST 到 interface.music.163.com。
     *
     * 它要求的 Cookie 不是浏览器那条，而是一串把 os / appver / deviceId / __csrf /
     * MUSIC_U 等字段百分号编码后拼起来的「头部 cookie」；缺 MUSIC_U 时会退化成未登录。
     *
     * 成功响应里通常没有 code 字段（直接返回业务对象），所以不会当成失败。
     */
    suspend fun postApp(path: String, data: JSONObject = JSONObject()): JSONObject {
        val fields = linkedMapOf(
            "osver" to "Microsoft-Windows-10-Professional-build-19045-64bit",
            "deviceId" to "NeteaseLite",
            "os" to "pc",
            "appver" to "3.0.18.203152",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (System.currentTimeMillis() / 1000).toString(),
            "resolution" to "1920x1080",
            "__csrf" to CookieJar.csrf,
            "channel" to "netease",
            "requestId" to "${System.currentTimeMillis()}_${(0..999).random().toString().padStart(4, '0')}",
        )
        CookieJar.get("MUSIC_U")?.let { fields["MUSIC_U"] = it }
        val cookie = fields.entries.joinToString("; ") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

        val body = data.keys().asSequence()
            .joinToString("&") { "${urlEncode(it)}=${urlEncode(data.optString(it))}" }
        val req = Request.Builder()
            .url(Http.APP_BASE + path)
            .headers(
                headersOf(
                    mapOf(
                        "User-Agent" to Http.APP_UA,
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to Http.BASE + "/",
                        "Cookie" to cookie,
                    ),
                ),
            )
            .post(body.toRequestBody(FORM))
            .build()
        return try {
            parse(send(req), path, lenient = false)
        } catch (e: TransportException) {
            throw e
        }
    }

    /**
     * @param lenient 为 true 时即使 code 非 200 也把响应体返回。
     *   风控接口会把成功写成**字符串** `"200"`（`json.code !== 200` 严格比较不成立），
     *   走严格模式会把成功当失败抛出，而抛出后 payload 又被 tryPost 丢掉。
     */
    private fun parse(text: String, path: String, lenient: Boolean): JSONObject {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ApiException(-1, "响应不是 JSON ($path): ${text.take(120)}")
        }
        // code 可能是数字 200 也可能是字符串 "200"，两种都当成功。
        // 还有一批接口（歌单详情、App 方言）压根不带 code，同样按成功处理。
        val raw = json.opt("code") ?: return json
        val codeStr = raw.toString()
        val code = codeStr.toIntOrNull()
        if (!lenient && code != null && code != 200) {
            throw ApiException(code, json.optString("message").ifEmpty { json.optString("msg") }.ifEmpty { "code $code" }, json)
        }
        return json
    }

    /**
     * 原始字节 POST（音乐云盘直传 NOS 用，用户 m05718）。
     *
     * 和 [post] 不同：这条不走 /weapi/ 加密，目标是 NOS 的上传节点，鉴权全在
     * 请求头里（`x-nos-token`）。Content-Length 交给 OkHttp 自己算，手写会和
     * 实际长度不一致而被 NOS 判成 400。
     */
    suspend fun postBytes(url: String, bytes: ByteArray, headers: Map<String, String>): Int =
        withContext(Dispatchers.IO) {
            val type = headers["Content-Type"] ?: "audio/mpeg"
            val req = Request.Builder()
                .url(url)
                .headers(headersOf(baseHeaders(headers)))
                .post(bytes.toRequestBody(type.toMediaType()))
                .build()
            try {
                http.newCall(req).execute().use { res ->
                    absorb(res.headers)
                    if (!res.isSuccessful) throw IOException("上传失败 HTTP ${res.code}")
                    res.code
                }
            } catch (e: IOException) {
                // 自己抛的「上传失败 HTTP xxx」原样往上送，别被翻译成「网络请求失败」。
                if (e.message?.startsWith("上传失败") == true) throw e
                throw TransportException(transportMessage(e), e)
            }
        }

    private fun headersOf(map: Map<String, String>): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
        for ((k, v) in map) b.add(k, v)
        return b.build()
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** 吞掉异常，返回 `{code, message}` —— 失败是常态的接口用它。 */
    suspend fun tryPost(path: String, data: JSONObject = JSONObject(), lenient: Boolean = false): JSONObject =
        try {
            post(path, data, lenient)
        } catch (e: TransportException) {
            JSONObject().put("code", -1).put("message", e.message ?: "网络连接失败").put("__transport", true)
        } catch (e: ApiException) {
            JSONObject().put("code", e.code).put("message", e.message ?: "请求失败")
        }

    suspend fun tryGet(path: String, params: Map<String, Any?> = emptyMap(), base: String = Http.BASE): JSONObject =
        try {
            get(path, params, base)
        } catch (e: TransportException) {
            JSONObject().put("code", -1).put("message", e.message ?: "网络连接失败").put("__transport", true)
        } catch (e: ApiException) {
            JSONObject().put("code", e.code).put("message", e.message ?: "请求失败")
        }
}

/** `JSONObject.optJSONArray` 的空安全包装，很多接口会返 null 而不是 `[]`。 */
fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

/** JSONArray → List<JSONObject>，跳过非对象项。 */
fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

/** 判断一次「没问到」是不是网络层断的。 */
fun JSONObject.isTransportFailure(): Boolean = optBoolean("__transport", false)
