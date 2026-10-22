package com.netease.lite.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一歌曲模型，对应桌面版 `electron\api\endpoints.js` 的 `normalizeSong()`。
 *
 * 网易云对同一首歌有好几种形状：v6 歌单里是 `ar`/`al`/`dt`，搜索结果是
 * `artists`/`album`/`duration`，App 方言又是另一套缩写。所有网络层出口都归一到这里，
 * 界面层就只认这一种。
 */
data class Song(
    val id: Long,
    val name: String,
    val alias: List<String> = emptyList(),
    val cover: String = "",
    val albumId: Long = 0,
    val albumName: String = "",
    val artists: List<Artist> = emptyList(),
    val duration: Long = 0,
    val fee: Int = 0,
    val reason: String = "",
    val unavailable: Boolean = false,
    val mvid: Long = 0,
    /**
     * 音轨号 / 碟号（用户 m07048 第④点）。
     *
     * 下载时写进 ID3 的 `TRCK` / `TPOS` 帧 —— 官方客户端下的文件里有这两帧，
     * 我们原来只写一个 `TSSE`（编码器），拷进别的播放器就丢了「第几首」。
     * 接口里字段名是 `no` / `cd`（`song/detail` 与专辑接口都有），缺省 0 表示未知，
     * 未知时不写帧 —— 写个 `0` 比不写更糟，播放器会显示「第 0 首」。
     */
    val trackNo: Int = 0,
    val discNo: Int = 0,
    /**
     * 碟名原文，写 `TPOS` 用（用户 m15041）。
     *
     * `cd` 字段有两种形状：单碟专辑是 `"01"`（纯数字），多碟合集是
     * `"disc1 万籁拂旧尘"`（碟号 + 碟名）。官方客户端的 `TPOS` 帧对后者是
     * **原文照抄**，所以除了算一个整数 [discNo]，还得把原文留一份。
     *
     * 只留带碟名的那种；纯数字的留空，由 [com.netease.lite.data.Id3] 补两位碟号。
     */
    val discText: String = "",
) {
    /**
     * 多位歌手的显示串，斜杠**前后不加空格**（用户 m11461 第②点）。
     *
     * 官方客户端就是这么显示的（`鸣潮先约电台/jkinss/薄荷Miint`）。带空格那版
     * （`A / B`）是我们自己早先拍的，官方 app 的本地音乐拿它去比对会当成两首不同的歌，
     * 于是列表里挂一个感叹号、点开显示「原 / 新」两串对不上。
     *
     * 注意这条只影响**显示与标签**：写进文件名时要换成空格（`/` 在文件名里非法），
     * 那一步在 [com.netease.lite.data.Downloader.applyTemplate] 里单独处理。
     */
    val artistText: String get() = artists.joinToString("/") { it.name }

    /** 需要单曲购买（数字专辑）——能看不能放，也下不了。 */
    val isPaid: Boolean get() = fee == 1

    val isVip: Boolean get() = fee == 1
}

data class Artist(val id: Long, val name: String)

/**
 * 歌手主页用的完整资料。
 *
 * 单独一个类型而不是往 [Artist] 里加字段：`Artist` 同时被当作 `Song.artists`
 * 的元素，一首歌只关心 id 和名字；把头像/粉丝数塞进去会让最近播放落盘的
 * 每首歌都多背几个永远为空的字段。
 */
data class ArtistProfile(
    val id: Long,
    val name: String = "",
    val avatar: String = "",
    val alias: List<String> = emptyList(),
    val followed: Boolean = false,
    val albumSize: Int = 0,
    val musicSize: Int = 0,
    val mvSize: Int = 0,
    /**
     * 歌手简介（第⑭点）。`/api/v1/artist/<id>` 的 `briefDesc` 恒为空串 ——
     * 真正的简介在 `/api/artist/head/info/get`，桌面版为此单开了一个
     * `artistDesc()`（`electron/api/endpoints.js:739`）。这里只做容器。
     */
    val briefDesc: String = "",
)

/** 播客电台（`/api/djradio/recommend`、`/api/dj/program/byradio` 里的 `radio`）。 */
data class Radio(
    val id: Long,
    val name: String = "",
    val cover: String = "",
    val desc: String = "",
    val programCount: Int = 0,
    val subCount: Int = 0,
    /** 主播。播客页显示成「主播」，点进去是用户页。 */
    val djId: Long = 0,
    val djName: String = "",
    val djAvatar: String = "",
)

data class Album(
    val id: Long,
    val name: String,
    val cover: String = "",
    val artistText: String = "",
    val publishTime: Long = 0,
    val size: Int = 0,
    /**
     * 专辑简介（第⑮点）。`/api/v1/album/<id>` 的 `album.description` 就是它，
     * 桌面版专辑页有一段「专辑介绍」；移动端原来连字段都没解析，所以只能显示歌名列表。
     */
    val description: String = "",
)

data class Playlist(
    val id: Long,
    val name: String,
    val cover: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val creatorId: Long = 0,
    val creatorName: String = "",
    val subscribed: Boolean = false,
    val specialType: Int = 0,
    val description: String = "",
    /**
     * 创建者头像。歌单页的大图头部要显示「谁建的」—— 只写昵称的版本
     * 看起来像一份匿名歌单，和桌面版的 `.pl-creator img` 对不上。
     */
    val creatorAvatar: String = "",
    /**
     * 创建时间（毫秒）。0 表示接口没给，页头那一行就不显示，
     * 而不是显示一个 1970 年。
     */
    val createTime: Long = 0,
) {
    /** 0 = 普通歌单；5 = 我喜欢的音乐（不能删/不能取消收藏）。 */
    val isLiked: Boolean get() = specialType == 5
}

data class User(
    val id: Long,
    val nickname: String,
    val avatar: String = "",
    val signature: String = "",
    val vipType: Int = 0,
    val follows: Int = 0,
    val followeds: Int = 0,
    val playlistCount: Int = 0,
    val createTime: Long = 0,
    val level: Int = 0,
    val listenSongs: Int = 0,
    /** 用户把听歌排行设成私密时，用户页要提示而不是显示空列表。 */
    val recordVisible: Boolean = true,
    /** 今日是否已签到。接口顶层就带，用它把按钮直接渲染成「今日已签到」。 */
    val signedToday: Boolean = false,
    val followed: Boolean = false,
)

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    /** 翻译逐行对齐原词，找不到对应行时用空串占位。 */
    val translated: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 评论里被引用的那一条（第⑬点）。
 *
 * 网易云把「回复某人」存成 `beReplied` 数组：外层是回复者的内容，里层是被回复者
 * 的原话。桌面版评论行会把它画成一段灰底引用块（`renderer/js/views.js` 的
 * `.cm-quote`），移动端原来只渲染外层，于是「A：你说得对」看起来像在自言自语。
 *
 * 只取第一条：接口理论上允许多条引用，实际从未见过两条以上，而多层嵌套在手机
 * 窄屏上无法阅读 —— 桌面版同样只取 `[0]`。
 */
data class QuotedComment(
    val nickname: String = "",
    val content: String = "",
)

data class Comment(
    val id: Long,
    val content: String,
    val time: Long,
    val likedCount: Int,
    val liked: Boolean,
    val userId: Long,
    val nickname: String,
    val avatar: String = "",
    /** 被引用的评论；不是回复别人的评论就是 null。 */
    val quoted: QuotedComment? = null,
)

/** 一次取流的完整结果，对应 `endpoints.js` 的 `songUrl()` 返回。 */
data class SongUrl(
    val id: Long,
    val url: String? = null,
    val br: Int = 0,
    val size: Long = 0,
    val fee: Int? = null,
    val level: String? = null,
    val type: String? = null,
    val md5: String? = null,
    /** 只能听一小段（VIP 歌未登录时的试听）。 */
    val trial: Boolean = false,
    val playable: Boolean = false,
    /** 请求本身失败（网络断了 / 服务端没回数据）——和「没版权」是两回事。 */
    val failed: Boolean = false,
    val code: Int = 0,
    val message: String = "",
) {
    val ok: Boolean get() = !url.isNullOrEmpty()
}

// ---------------------------------------------------------------- 解析

private fun JSONObject.long(key: String): Long = optLong(key, 0L)

/**
 * 图片地址统一成 https。
 *
 * 只有一个原因：**Android 9 起默认禁止明文 HTTP**。网易云的图片 CDN
 * （`p1.music.126.net` 等）历史字段里偶尔会给 `http://`，一旦如此，
 * Coil 在列表里加载不出来（封面全灰）、Media3 在通知栏加载不出来
 * （日志刷 `Cleartext HTTP traffic not permitted`），但两处都只是警告，
 * 界面上看不到任何错误提示 —— 排查时很容易以为是「图片源挂了」。
 * 音频直链在 `Endpoints.httpsUrl()` 里做同样的事。
 */
internal fun httpsUrl(raw: String): String =
    if (raw.isEmpty()) raw else raw.replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")

/** 把一首歌的任意形状折叠成 [Song]。 */
fun parseSong(s: JSONObject?): Song? {
    if (s == null) return null
    val album = s.optJSONObject("al") ?: s.optJSONObject("album") ?: JSONObject()
    val artistsArr = s.optJSONArray("ar") ?: s.optJSONArray("artists") ?: JSONArray()

    val aliasArr = s.optJSONArray("alia") ?: s.optJSONArray("tns") ?: JSONArray()
    val alias = (0 until aliasArr.length()).mapNotNull { aliasArr.optString(it).ifEmpty { null } }

    val artists = (0 until artistsArr.length()).mapNotNull { i ->
        val a = artistsArr.optJSONObject(i) ?: return@mapNotNull null
        Artist(a.long("id"), a.optString("name"))
    }

    // 音轨号 / 碟号：写 ID3 用（用户 m07048 第④点）。
    // `no` 是 song/detail 里的序号，`cd` 是碟号（单碟专辑恒为 1）。
    // `cd` 可能是 `"disc1 万籁拂旧尘"`，`optInt` 会得 0 —— 走 discFields 拆开
    // （用户 m15041：拆错了 TPOS 整帧不写，官方 app 下的文件就比我们多一行属性）。
    val (discNo, discText) = discFields(s.optString("cd").ifEmpty { s.optString("disc") })

    return Song(
        id = s.long("id"),
        name = s.optString("name"),
        alias = alias,
        cover = httpsUrl(album.optString("picUrl").ifEmpty { s.optString("picUrl") }),
        albumId = album.long("id"),
        albumName = album.optString("name"),
        artists = artists,
        // dt（v6 / 详情）与 duration（搜索）二选一
        duration = if (s.has("dt")) s.long("dt") else s.long("duration"),
        fee = s.optInt("fee", 0),
        // 每日推荐每首歌带一句推荐理由，其它接口没有这个字段
        reason = s.optString("recommendReason").ifEmpty { s.optString("reason") },
        // st === -200 表示当前地区/账号下架
        unavailable = s.optInt("st", 0) == -200 || s.optBoolean("noCopyrightRcmd"),
        mvid = if (s.has("mv")) s.long("mv") else 0L,
        trackNo = s.optInt("no", 0),
        discNo = discNo,
        discText = discText,
    )
}

/**
 * 把接口里那个 `cd` 拆成「碟号 + 碟名原文」。
 *
 * 两种形状都要认，官方客户端的 `TPOS` 就是照这两种写的：
 *  - `"01"` / `"1"` / `1`     → 碟号 1，碟名空（`TPOS` 补两位写 `01`）
 *  - `"disc1 万籁拂旧尘"`      → 碟号 1，碟名 `"disc1 万籁拂旧尘"`（`TPOS` 原文照抄）
 *
 * 老接口 `/api/song/detail` 把这字段叫 `disc`，新接口 `/api/v3/song/detail` 叫 `cd`，
 * 值完全一样，两个都试。
 */
private fun discFields(raw: String): Pair<Int, String> {
    val s = raw.trim()
    if (s.isEmpty()) return 0 to ""
    val n = Regex("^(?:disc)?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    // 去掉 `disc1` 前缀后还剩下非空白字符 → 这句里有碟名，得留原文
    val tail = s.replace(Regex("^disc\\s*\\d+\\s*", RegexOption.IGNORE_CASE), "").trim()
    return n to if (tail.isEmpty()) "" else s
}

fun parseSongs(arr: JSONArray?): List<Song> {
    if (arr == null) return emptyList()
    val out = ArrayList<Song>(arr.length())
    for (i in 0 until arr.length()) parseSong(arr.optJSONObject(i))?.let { out.add(it) }
    return out
}

fun parseArtist(a: JSONObject?): Artist? {
    if (a == null) return null
    // 详情接口把 id 放在 base 里，搜索接口直接放顶层
    val id = if (a.has("id")) a.long("id") else (a.optJSONObject("base")?.long("id") ?: 0L)
    val name = a.optString("name").ifEmpty { a.optJSONObject("base")?.optString("name") ?: "" }
    return Artist(id, name)
}

fun parsePlaylist(p: JSONObject?): Playlist? {
    if (p == null) return null
    val creator = p.optJSONObject("creator")
    return Playlist(
        id = p.long("id"),
        name = p.optString("name"),
        cover = httpsUrl(p.optString("coverImgUrl").ifEmpty { p.optString("picUrl") }),
        trackCount = p.optInt("trackCount", 0),
        playCount = p.long("playCount"),
        creatorId = creator?.long("userId") ?: 0L,
        creatorName = creator?.optString("nickname") ?: "",
        subscribed = p.optBoolean("subscribed"),
        specialType = p.optInt("specialType", 0),
        description = p.optString("description"),
        creatorAvatar = httpsUrl(creator?.optString("avatarUrl").orEmpty()),
        createTime = p.long("createTime"),
    )
}

fun parseArtistProfile(a: JSONObject?): ArtistProfile? {
    if (a == null) return null
    val base = a.optJSONObject("base") ?: a.optJSONObject("artist") ?: a
    val aliasArr = base.optJSONArray("alias") ?: base.optJSONArray("transNames") ?: JSONArray()
    val alias = (0 until aliasArr.length()).mapNotNull { aliasArr.optString(it).ifEmpty { null } }
    return ArtistProfile(
        id = base.long("id").let { if (it != 0L) it else a.long("id") },
        name = base.optString("name"),
        // `picUrl` 是方形头像；`img1v1Url` 是老接口字段，圆头像
        avatar = httpsUrl(base.optString("picUrl").ifEmpty { base.optString("img1v1Url") }.ifEmpty { base.optString("avatar") }),
        alias = alias,
        followed = base.optBoolean("followed"),
        albumSize = base.optInt("albumSize", 0),
        musicSize = base.optInt("musicSize", 0),
        mvSize = base.optInt("mvSize", 0),
    )
}

fun parseRadio(r: JSONObject?): Radio? {
    if (r == null) return null
    val dj = r.optJSONObject("dj")
    return Radio(
        id = r.long("id"),
        name = r.optString("name"),
        cover = httpsUrl(r.optString("picUrl").ifEmpty { r.optString("picURL") }),
        desc = r.optString("desc"),
        programCount = r.optInt("programCount", 0),
        subCount = r.optInt("subCount", 0),
        djId = dj?.optLong("userId") ?: 0L,
        djName = dj?.optString("nickname") ?: "",
        djAvatar = httpsUrl(dj?.optString("avatarUrl") ?: ""),
    )
}

fun parseUser(u: JSONObject?): User? {
    if (u == null) return null
    val profile = u.optJSONObject("profile") ?: u
    return User(
        id = profile.long("userId").let { if (it != 0L) it else profile.long("id") },
        nickname = profile.optString("nickname"),
        avatar = httpsUrl(profile.optString("avatarUrl")),
        signature = profile.optString("signature"),
        // vipType 110 = 黑胶 VIP；0 = 普通用户
        vipType = profile.optInt("vipType", 0),
        follows = profile.optInt("follows", 0),
        followeds = profile.optInt("followeds", 0),
    )
}

/**
 * 解析歌词。返回的 `lrc` 是 `[mm:ss.xxx]词` 形式，`tlyric` 是逐行对齐的翻译。
 *
 * 翻译的对齐方式：两边行数**通常**一致，但缺行时网易云会留一行空 `[]`，
 * 所以这里按索引对齐而不是按时间轴匹配 —— 后者在副歌重复时容易错位。
 */
fun parseLyrics(lrc: String, tlyric: String): Lyrics {
    val lines = parseLrcBody(lrc)
    if (lines.isEmpty()) return Lyrics()
    val trans = parseLrcBody(tlyric)
    val translated = if (trans.size == lines.size) {
        trans.map { it.text }
    } else {
        // 行数对不上就按时间就近匹配，匹配不到留空串。
        val byTime = trans.associate { it.timeMs to it.text }
        lines.map { byTime[it.timeMs] ?: "" }
    }
    return Lyrics(lines, translated)
}

private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

private fun parseLrcBody(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val out = ArrayList<LyricLine>()
    for (line in raw.lineSequence()) {
        val tags = LRC_TAG.findAll(line).toList()
        if (tags.isEmpty()) continue
        val text = line.substring(tags.last().range.last + 1).trim()
        for (t in tags) {
            val min = t.groupValues[1].toLongOrNull() ?: continue
            val sec = t.groupValues[2].toLongOrNull() ?: continue
            // 毫秒段可能是 2 位（.xx = 十毫秒）也可能是 3 位（.xxx = 毫秒）
            val fracRaw = t.groupValues[3]
            val ms = when (fracRaw.length) {
                0 -> 0L
                1 -> fracRaw.toLong() * 100
                2 -> fracRaw.toLong() * 10
                else -> fracRaw.take(3).toLong()
            }
            out.add(LyricLine(min * 60_000 + sec * 1000 + ms, text))
        }
    }
    out.sortBy { it.timeMs }

    // 同一时间戳被解析出多行（翻译混在原词里）时只留第一条，避免歌词滚动跳两下。
    val dedup = ArrayList<LyricLine>(out.size)
    var last = Long.MIN_VALUE
    for (l in out) {
        if (l.timeMs == last) continue
        dedup.add(l)
        last = l.timeMs
    }
    return dedup
}

fun parseSongsFromSearch(j: JSONObject): List<Song> {
    val result = j.optJSONObject("result") ?: return emptyList()
    return parseSongs(result.optJSONArray("songs"))
}

// ------------------------------------------------------- 本地持久化用的序列化
//
// 桌面版把「最近播放」塞进 localStorage 之前先做了一次瘦身（见
// `renderer\js\store.js` 的 `trimSong()`）：`normalizeSong()` 带出来的
// `privilege` 等字段前端一个都用不到，200 首能到几百 KB。
// 这里同理 —— SharedPreferences 是 XML，写大对象会拖慢每次读取。

/** 只留下界面真正需要的字段。刻意不含 `privilege` / `reason` 之外的原始 JSON。 */
fun Song.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    if (alias.isNotEmpty()) put("alias", JSONArray(alias))
    put("cover", cover)
    put("albumId", albumId)
    put("albumName", albumName)
    put("artists", JSONArray().apply {
        for (a in artists) put(JSONObject().apply { put("id", a.id); put("name", a.name) })
    })
    put("duration", duration)
    put("fee", fee)
    if (reason.isNotEmpty()) put("reason", reason)
    if (unavailable) put("unavailable", true)
    if (mvid != 0L) put("mvid", mvid)
    if (trackNo > 0) put("trackNo", trackNo)
    if (discNo > 0) put("discNo", discNo)
    if (discText.isNotEmpty()) put("discText", discText)
}

/**
 * 从 [Song.toJson] 写出的对象读回来。
 *
 * 刻意用手写的 get 而不是复用 `parseSong()`：后者认的是服务端的
 * `al`/`ar`/`dt` 缩写形状，读自己写出去的字段名会静默拿到空值
 * —— 症状是「重启后最近播放里每首歌都没有歌手和封面」。
 */
fun songFromJson(o: JSONObject): Song {
    val artistsArr = o.optJSONArray("artists")
    val artists = if (artistsArr == null) emptyList() else
        (0 until artistsArr.length()).mapNotNull { i ->
            val a = artistsArr.optJSONObject(i) ?: return@mapNotNull null
            Artist(a.optLong("id", 0L), a.optString("name"))
        }
    val aliasArr = o.optJSONArray("alias")
    val alias = if (aliasArr == null) emptyList() else
        (0 until aliasArr.length()).mapNotNull { aliasArr.optString(it).ifEmpty { null } }
    return Song(
        id = o.optLong("id", 0L),
        name = o.optString("name"),
        alias = alias,
        cover = httpsUrl(o.optString("cover")),
        albumId = o.optLong("albumId", 0L),
        albumName = o.optString("albumName"),
        artists = artists,
        duration = o.optLong("duration", 0L),
        fee = o.optInt("fee", 0),
        reason = o.optString("reason"),
        unavailable = o.optBoolean("unavailable"),
        mvid = o.optLong("mvid", 0L),
        trackNo = o.optInt("trackNo", 0),
        discNo = o.optInt("discNo", 0),
        discText = o.optString("discText"),
    )
}

fun List<Song>.toJsonArray(): String {
    val arr = JSONArray()
    for (s in this) arr.put(s.toJson())
    return arr.toString()
}

fun songsFromJsonArray(raw: String?): List<Song> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { songFromJson(it) }
        }
    } catch (e: Exception) {
        // 存坏了就当空的：一份听歌历史不值得让应用起不来。
        emptyList()
    }
}
