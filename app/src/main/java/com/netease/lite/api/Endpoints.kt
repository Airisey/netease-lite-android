package com.netease.lite.api

import com.netease.lite.model.Album
import com.netease.lite.model.Artist
import com.netease.lite.model.ArtistProfile
import com.netease.lite.model.Comment
import com.netease.lite.model.Lyrics
import com.netease.lite.model.Playlist
import com.netease.lite.model.QuotedComment
import com.netease.lite.model.Radio
import com.netease.lite.model.Song
import com.netease.lite.model.SongUrl
import com.netease.lite.model.User
import com.netease.lite.model.httpsUrl
import com.netease.lite.model.parseArtistProfile
import com.netease.lite.model.parseLyrics
import com.netease.lite.model.parsePlaylist
import com.netease.lite.model.parseRadio
import com.netease.lite.model.parseSong
import com.netease.lite.model.parseSongs
import com.netease.lite.model.parseUser
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网易云接口一览（`netease-lite\electron\api\endpoints.js` 的 Kotlin 移植）。
 *
 * 只搬 Android 客户端用得到的那些：浏览、搜索、播放取流、歌词、评论、收藏、
 * 歌单增删、登录。桌面版里的下载队列、本地索引、小窗、皮肤等属于壳层能力，
 * 不在这里。
 *
 * 路径前缀的坑（沿用桌面版注释）：[Client.post] 自带 `/weapi` 前缀，
 * 所以走加密 POST 的路径必须写 `/v1/...` 或 `/v3/...`；写成 `/api/v1/...`
 * 会变成 `/weapi/api/v1/...` → 404 接口未找到，而且会被 tryPost 吞掉、静默少数据。
 */
object Endpoints {

    /** 音质档位表。hires 已并入 lossless（桌面版 m36690 第④点的结论）。 */
    val QUALITY: Map<String, Pair<Int, String>> = mapOf(
        "standard" to (128000 to "standard"),
        "higher" to (192000 to "higher"),
        "exhigh" to (320000 to "exhigh"),
        "lossless" to (999000 to "lossless"),
    )

    const val DEFAULT_QUALITY = "exhigh"

    val QUALITY_LABEL: Map<String, String> = mapOf(
        "standard" to "标准",
        "higher" to "较高",
        "exhigh" to "极高",
        "lossless" to "无损",
    )

    /**
     * 请求失败时统一返回的形状。
     *
     * 桌面版这里有个真 bug（m36690 第①点）：`(j.data || [])[0] || {}` 让断网时
     * 拿到 `url = null, fee = undefined`，界面就报成「无版权或已下架」。
     * 所以这里显式带 `failed = true`，让调用方能区分「网络没问到」与「服务端说没有」。
     */
    private fun transportFailure(id: Long, j: JSONObject, extra: SongUrl = SongUrl(id)): SongUrl =
        extra.copy(
            id = id,
            url = null,
            failed = true,
            code = j.optInt("code", -1),
            message = j.optString("message").ifEmpty { "网络请求失败" },
        )

    /**
     * 取流地址统一补成 https（另一处同样的逻辑在 `model.httpsUrl`）。
     *
     * 网易云的一部分音频 CDN（`m7xx.music.126.net`）只会回纯 http 地址，而
     * Android 9 起默认禁止明文流量 —— ExoPlayer 报的是
     * `Cleartext HTTP traffic to m701.music.126.net not permitted`，界面上
     * 就成了一按播放就跳到下一首。这些域名的 https 是通的，换掉协议即可。
     *
     * 桌面版（Electron）也做同一件事：`electron\download.js` 里那句
     * `String(info.url).replace(/^http:\/\//i, 'https://')`。两端口径必须一致，
     * 否则同一首歌桌面版能放、手机放不了。
     */
    private fun streamUrl(raw: String): String? =
        httpsUrl(raw).ifEmpty { null }

    /**
     * 取一个「可能为 JSON null」的文本字段。
     *
     * Android 的 `JSONObject.optString(key)` 在值是 JSON `null` 时返回的是
     * **字面量字符串 `"null"`**（`JSONObject.NULL.toString()`），`.ifEmpty{}` 兜不住，
     * 于是这个 "null" 会一路当成真值传下去：下载失败提示因此印成
     * 「只能取到专属格式（.null），无法下载」，而不是「暂无版权或已下架」
     * （用户 m04384 第②点）。这里统一把空串与 "null" 都当没有值。
     */
    private fun textOrNull(o: JSONObject, key: String): String? =
        o.optString(key).takeIf { it.isNotEmpty() && it != "null" }

    // ------------------------------------------------------------ 账号

    /** 当前登录状态。未登录时 `profile` 为 null。 */
    suspend fun account(): User? {
        val j = Client.tryGet("/api/nuser/account/get")
        return parseUser(j.optJSONObject("profile"))
    }

    /** 导出 Cookie 字符串（登录后要落盘，下次启动免登录）。 */
    fun exportCookie(): String = CookieJar.getCookieString()

    suspend fun logout(): Boolean {
        val j = Client.tryPost("/logout")
        CookieJar.setCookieString(null)
        return j.optInt("code", 500) == 200
    }

    // ------------------------------------------------------------ 浏览

    suspend fun userPlaylists(uid: Long): List<Playlist> {
        val j = Client.tryGet("/api/user/playlist", mapOf("uid" to uid, "limit" to 1000, "offset" to 0))
        return j.array("playlist").objects().mapNotNull { parsePlaylist(it) }
    }

    /**
     * 歌单详情。
     *
     * v6 对**别人的**歌单只在 `tracks` 里内联约 20 首，但 `trackIds` 是全量的；
     * 所以曲目多的时候要拿 trackIds 再补一批（见 [playlistTrackIds]），
     * 判据用 `trackCount` 而不是 `trackIds.length`。
     */
    suspend fun playlistDetail(id: Long, limit: Int = 1000, offset: Int = 0): Pair<Playlist?, List<Song>> {
        val j = Client.tryGet(
            "/api/v6/playlist/detail",
            mapOf("id" to id, "n" to limit, "s" to 8, "offset" to offset),
        )
        val pl = parsePlaylist(j.optJSONObject("playlist"))
        var songs = parseSongs(j.optJSONObject("playlist")?.optJSONArray("tracks"))
        val trackIds = j.optJSONObject("playlist")?.optJSONArray("trackIds") ?: JSONArray()
        val total = pl?.trackCount ?: 0
        if (total > songs.size && trackIds.length() > songs.size) {
            val ids = (0 until trackIds.length()).mapNotNull { trackIds.optJSONObject(it)?.optLong("id") }
            val extra = playlistTrackIds(ids)
            if (extra.isNotEmpty()) songs = extra
        }
        return pl to songs
    }

    /**
     * 按 trackIds 批量取歌。每批 500、并发 4。
     *
     * 这是桌面版 m34266 那个「大歌单只出 20 首」的修复：`/api/playlist/track/all`
     * 在 weapi / 明文 GET / App 三种方言下全部 404，只能走 `/v3/song/detail`。
     * 最后**按传入顺序重排** —— 服务端的返回顺序不可靠，歌单顺序就是这里丢的。
     */
    suspend fun playlistTrackIds(trackIds: List<Long>): List<Song> = coroutineScope {
        if (trackIds.isEmpty()) return@coroutineScope emptyList()
        val chunks = trackIds.chunked(500)
        val sem = Semaphore(4)
        val results = chunks.map { chunk ->
            async {
                sem.withPermit {
                    val c = JSONArray()
                    for (id in chunk) c.put(JSONObject().put("id", id))
                    val j = Client.tryPost("/v3/song/detail", JSONObject().put("c", c.toString()))
                    parseSongs(j.optJSONArray("songs"))
                }
            }
        }.map { it.await() }

        val byId = HashMap<Long, Song>(trackIds.size)
        for (list in results) for (s in list) byId[s.id] = s
        trackIds.mapNotNull { byId[it] }
    }

    /**
     * 按 id 批量取歌。
     *
     * 纯转发到 [playlistTrackIds] —— 那里面已经处理了分批、并发上限和「按传入顺序
     * 重排」。重新写一遍 `/v3/song/detail` 只会多一份要同步维护的 JSON 解析。
     * 名称单独留一个是为了调用点读起来是「取这几首歌」而不是「把这些 id 当歌单曲目」。
     */
    suspend fun songDetail(ids: List<Long>): List<Song> = playlistTrackIds(ids)

    /**
     * 每日推荐。
     *
     * 路径是 `/v1/discovery/recommend/songs`（不是 `/discovery/recommend/songs` ——
     * 那是推荐流，与每日推荐 0 首重合），数据正常在 `data.dailySongs`。
     * 但**部分账号/时段只回顶层裸 `recommend` 数组**，只读 `data.dailySongs`
     * 就会得到空列表，界面显示「今天的推荐还没出来」，而下拉刷新永远刷不出来。
     * 桌面版一直是两条都看的，这里对齐。
     */
    suspend fun dailyRecommend(): List<Song> {
        val j = Client.tryPost("/v1/discovery/recommend/songs")
        val daily = j.optJSONObject("data")?.optJSONArray("dailySongs")
        if (daily != null && daily.length() > 0) return parseSongs(daily)
        return parseSongs(j.optJSONArray("recommend"))
    }

    suspend fun recommendPlaylists(limit: Int = 12): List<Playlist> {
        val j = Client.tryGet("/api/personalized/playlist", mapOf("limit" to limit))
        return j.array("result").objects().mapNotNull { parsePlaylist(it) }
    }

    suspend fun toplists(): List<Playlist> {
        val j = Client.tryGet("/api/toplist/detail")
        return j.array("list").objects().mapNotNull { parsePlaylist(it) }
    }

    /** 我喜欢的音乐：先拿歌单壳（specialType 5），再一次性取全部曲目。 */
    suspend fun likedSongs(uid: Long): Pair<Playlist?, List<Song>> {
        val all = userPlaylists(uid)
        val liked = all.firstOrNull { it.specialType == 5 } ?: return null to emptyList()
        return liked to playlistDetail(liked.id).second
    }

    /**
     * 我喜欢的音乐的曲目 id 集合。
     *
     * 先问 `/api/song/like`（一次拿全量 id）；它不给 `ids` 数组时才回落到
     * 「找到 specialType 5 的歌单，再取它的曲目」。所以 [fallbackLiked] 是
     * 调用方传进来的歌单列表，避免这里重复请求一次 userPlaylists。
     */
    suspend fun likedIds(uid: Long): Set<Long> {
        val j = Client.tryGet("/api/song/like", mapOf("uid" to uid, "timestamp" to System.currentTimeMillis()))
        val ids = j.optJSONArray("ids")
        if (ids != null) {
            return (0 until ids.length()).mapNotNull { ids.optLong(it, 0L).takeIf { v -> v != 0L } }.toSet()
        }
        val liked = userPlaylists(uid).firstOrNull { it.specialType == 5 } ?: return emptySet()
        return playlistDetail(liked.id).second.map { it.id }.toSet()
    }

    /**
     * 最近播放（听歌排行）。
     *
     * `type=0` 才是全部记录，`type=1` 只回 `weekData`（一周）；只读后者会得到空列表。
     * 返回里的 `song` 是完整对象，直接交给 [parseSong]。
     */
    suspend fun playRecord(uid: Long, type: Int = 0): List<Song> {
        val j = Client.tryGet("/api/v1/play/record", mapOf("uid" to uid, "type" to type))
        val arr = if (type == 1) j.optJSONArray("weekData") else j.optJSONArray("allData") ?: j.optJSONArray("weekData")
        if (arr == null) return emptyList()
        val out = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            parseSong(arr.optJSONObject(i)?.optJSONObject("song"))?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------ 取流

    /**
     * 取播放直链（v1 方言，推荐用这个）。
     *
     * `encodeType` 名义上是「拿到什么容器」的开关：无损要 flac，其余要 mp3。
     * 实测结论：**服务端基本忽略它，容器由 `level` 决定** —— `level=lossless`
     * 时不管传什么都回 FLAC，`level<=exhigh` 才回 MP3。
     *
     * 返回的 `type` 才是服务端真正给出的容器（mp3 / flac / m4a…），
     * 下载时以它为准定后缀。
     */
    suspend fun songUrlV1(id: Long, quality: String = DEFAULT_QUALITY): SongUrl {
        val q = QUALITY[quality] ?: QUALITY.getValue(DEFAULT_QUALITY)
        val lossless = quality == "lossless"
        val enc = if (lossless) "flac" else "mp3"
        val j = Client.tryGet(
            "/api/song/enhance/player/url/v1",
            mapOf("ids" to JSONArray().put(id).toString(), "level" to q.second, "encodeType" to enc),
        )
        val data = j.optJSONArray("data")
        if (data == null) return transportFailure(id, j)

        val d = data.optJSONObject(0) ?: JSONObject()
        val trial = d.optJSONObject("freeTrialInfo")
        return SongUrl(
            id = id,
            url = streamUrl(d.optString("url")),
            br = d.optInt("br", 0),
            size = d.optLong("size", 0L),
            level = textOrNull(d, "level"),
            type = textOrNull(d, "type"),
            fee = if (d.has("fee")) d.optInt("fee") else null,
            md5 = textOrNull(d, "md5"),
            // 只有试听片段（非会员点会员曲）必须能认出来：把 30 秒试听当整首播
            // 比直接失败更糟 —— 用户以为听上了。
            trial = trial != null,
            playable = d.optString("url").isNotEmpty(),
        )
    }

    /** 旧方言，部分老歌只在它下面有直链；[songUrlV1] 拿不到时兜底。 */
    suspend fun songUrl(id: Long, quality: String = DEFAULT_QUALITY): SongUrl {
        val q = QUALITY[quality] ?: QUALITY.getValue(DEFAULT_QUALITY)
        val j = Client.tryGet("/api/song/enhance/player/url", mapOf("ids" to JSONArray().put(id).toString(), "br" to q.first))
        val data = j.optJSONArray("data") ?: return transportFailure(id, j)
        val d = data.optJSONObject(0) ?: JSONObject()
        return SongUrl(
            id = id,
            url = streamUrl(d.optString("url")),
            br = d.optInt("br", 0),
            size = d.optLong("size", 0L),
            fee = if (d.has("fee")) d.optInt("fee") else null,
            level = textOrNull(d, "level"),
            type = textOrNull(d, "type"),
            md5 = textOrNull(d, "md5"),
            trial = d.optJSONObject("freeTrialInfo") != null,
            playable = d.optString("url").isNotEmpty(),
        )
    }

    /**
     * 取流，带方言兜底：先 v1，拿不到再试老方言。
     *
     * 两次都「没问到数据」（而不是服务端明说没有）时，返回的结果带 `failed = true`，
     * 界面据此提示「网络连接失败」而不是「无版权或已下架」。
     */
    suspend fun resolveUrl(id: Long, quality: String = DEFAULT_QUALITY): SongUrl {
        val a = songUrlV1(id, quality)
        if (a.ok || !a.failed) return a
        val b = songUrl(id, quality)
        return if (b.ok || !b.failed) b else a
    }

    // ------------------------------------------------------------ 歌词 / 评论

    suspend fun lyrics(id: Long): Lyrics {
        val j = Client.tryGet("/api/song/lyric", mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1))
        val lrc = j.optJSONObject("lrc")?.optString("lyric") ?: ""
        val tlyric = j.optJSONObject("tlyric")?.optString("lyric") ?: ""
        return parseLyrics(lrc, tlyric)
    }

    private fun commentPrefix(type: String): String = when (type) {
        "playlist" -> "A_PL_0_"
        "album" -> "R_AL_3_"
        "dj" -> "R_DJ_3_"
        "mv" -> "R_MV_5_"
        else -> "R_SO_4_"
    }

    /** 评论的第一页。`type` 取 song / playlist / album / dj / mv。 */
    suspend fun comments(type: String, id: Long, limit: Int = 20, offset: Int = 0): Triple<Int, List<Comment>, List<Comment>> {
        val j = Client.tryGet(
            "/api/v1/resource/comments/${commentPrefix(type)}$id",
            mapOf("limit" to limit, "offset" to offset, "beforeTime" to 0),
        )
        return Triple(j.optInt("total", 0), parseComments(j.array("hotComments")), parseComments(j.array("comments")))
    }

    private fun parseComments(arr: JSONArray): List<Comment> {
        val out = ArrayList<Comment>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val u = c.optJSONObject("user") ?: JSONObject()
            // 被引用的那条（第⑬点）。桌面版 `electron/api/endpoints.js:948` 只取
            // `beReplied[0]` —— 手机上多层嵌套的引用串读不下去，一层足够表达
            // 「这条是在回谁、回的那句是什么」。
            val replied = c.optJSONArray("beReplied")?.optJSONObject(0)
            out.add(
                Comment(
                    id = c.optLong("commentId"),
                    content = c.optString("content"),
                    time = c.optLong("time"),
                    likedCount = c.optInt("likedCount", 0),
                    liked = c.optBoolean("liked"),
                    userId = u.optLong("userId"),
                    nickname = u.optString("nickname"),
                    avatar = httpsUrl(u.optString("avatarUrl")),
                    quoted = replied?.let {
                        QuotedComment(
                            nickname = it.optJSONObject("user")?.optString("nickname").orEmpty(),
                            content = it.optString("content"),
                        )
                    }?.takeIf { it.content.isNotEmpty() },
                ),
            )
        }
        return out
    }

    /** 评论点赞 / 取消。点赞与取消是两个不同路径。 */
    suspend fun commentLike(threadId: String, commentId: Long, like: Boolean = true): Boolean {
        val uri = if (like) "/v1/comment/like" else "/v1/comment/unlike"
        val j = Client.tryPost(uri, JSONObject().put("threadId", threadId).put("commentId", commentId.toString()).put("type", if (like) 0 else 1))
        return j.optInt("code", 500) == 200
    }

    // ------------------------------------------------------------ 搜索

    /**
     * 搜索。type：1 单曲 / 10 专辑 / 100 歌手 / 1000 歌单 / 1009 电台。
     *
     * 走 `/api/cloudsearch/pc` 而不是老接口 `/api/search/get/web`：后者返回的
     * 专辑对象只带 `picId` 不带 `picUrl`，于是**每一行歌曲都没有封面**；
     * cloudsearch 回的是现代形状（`al.picUrl` / `ar` / `dt`），其它类型也有真封面。
     */
    suspend fun search(keyword: String, type: Int = 1, limit: Int = 30, offset: Int = 0): JSONObject =
        Client.tryGet(
            "/api/cloudsearch/pc",
            mapOf("s" to keyword, "type" to type, "limit" to limit, "offset" to offset),
        )

    suspend fun searchSongs(keyword: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val j = search(keyword, 1, limit, offset)
        return parseSongs(j.optJSONObject("result")?.optJSONArray("songs"))
    }

    suspend fun searchPlaylists(keyword: String, limit: Int = 30, offset: Int = 0): List<Playlist> {
        val j = search(keyword, 1000, limit, offset)
        return j.optJSONObject("result")?.array("playlists")?.objects()?.mapNotNull { parsePlaylist(it) } ?: emptyList()
    }

    /**
     * 搜索歌手 / 专辑。桌面版的搜索页有四个 tab（歌曲 / 歌单 / 歌手 / 专辑），
     * 安卓版 m41817 第③点之前只有前两个 —— 接口一直是同一个
     * `/api/cloudsearch/pc`，只是没人拿 type 100 / 10 去问。
     */
    suspend fun searchArtists(keyword: String, limit: Int = 30, offset: Int = 0): List<ArtistProfile> {
        val j = search(keyword, 100, limit, offset)
        val arr = j.optJSONObject("result")?.optJSONArray("artists")
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { parseArtistProfile(arr.optJSONObject(it)) }
    }

    /**
     * 搜索用户（用户 m03593 第②点）：搜索页新增的「用户」一栏。
     *
     * cloudsearch 把用户放在 `result.userprofiles`（计数字段是 `userprofileCount`），
     * 每条用户的字段与关注 / 粉丝列表完全同一套（`userId` / `nickname` / `avatarUrl` /
     * `signature` / `follows` / `followeds`），所以直接复用 [parseUser]，
     * 界面那边也能直接用现成的用户行。
     */
    suspend fun searchUsers(keyword: String, limit: Int = 30, offset: Int = 0): List<User> {
        val j = search(keyword, 1002, limit, offset)
        val arr = j.optJSONObject("result")?.optJSONArray("userprofiles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parseUser(arr.optJSONObject(it)) }
    }

    suspend fun searchAlbums(keyword: String, limit: Int = 30, offset: Int = 0): List<Album> {
        val j = search(keyword, 10, limit, offset)
        val arr = j.optJSONObject("result")?.optJSONArray("albums")
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            Album(
                id = a.optLong("id", 0L),
                name = a.optString("name"),
                // cloudsearch 的专辑封面在 artist 同级字段 picUrl 上
                cover = httpsUrl(a.optString("picUrl")),
                artistText = a.array("artists").objects().joinToString("/") { x -> x.optString("name") },
                publishTime = a.optLong("publishTime"),
                size = a.optInt("size", 0),
            )
        }
    }

    /**
     * 歌手详情（头像 / 别名 / 作品数）。
     *
     * 桌面版歌手页的页头有头像和「单曲 N · 专辑 N · MV N」；Android 版原来只有
     * `Artist(id, "")`，页头就剩「歌手」两个字。这里补上，失败返回 null 由调用方回落。
     */
    suspend fun artistDetail(id: Long): ArtistProfile? {
        val j = Client.tryGet("/api/v1/artist/$id")
        val base = parseArtistProfile(j) ?: return null
        // 第⑭点：`/api/v1/artist/<id>` 的 briefDesc 恒为空串，真简介在另一个接口。
        // 单独再请求一次而不是并行：只有歌手页会用到简介，而歌手页本来就要等
        // 专辑墙与热歌，多一次串行请求不影响观感，代码却简单得多。
        return base.copy(briefDesc = artistDesc(id))
    }

    /**
     * 歌手简介（第⑭点）。
     *
     * 走 `/api/artist/head/info/get`，与桌面版 `electron/api/endpoints.js:740` 同源。
     *
     * 两个坑，先后踩过：
     *  1. **必须走明文 GET**。`Client.post` 内部会拼 `/weapi` 前缀，`/api/artist/...`
     *     就变成 `/weapi/api/artist/...`，服务端回「404 接口未找到」—— 桌面版的
     *     `artistDesc` 用的也是 `tryGet` 而不是 `tryPost`。
     *  2. `briefDesc` **不在顶层**，而在 `data.artist.briefDesc` 里（顶层只有
     *     videoCount / identify / artist / blacklist …）。按顶层读同样拿到空串。
     * 两处任缺一处，界面都会显示「这位歌手还没有填写简介」—— 而接口其实回了
     * 200 且正文齐全。仍保留 `introduction` 数组作回落：少数歌手只填分段简介。
     * 失败一律返回空串：简介是锦上添花，不该让整个歌手页报错。
     */
    suspend fun artistDesc(id: Long): String {
        val j = Client.tryGet("/api/artist/head/info/get", mapOf("id" to id))
        val data = j.optJSONObject("data")
        val direct = data?.optJSONObject("artist")?.optString("briefDesc")?.trim().orEmpty()
        if (direct.isNotEmpty()) return direct
        val json = data ?: j
        val intro = json.optJSONArray("introduction") ?: return ""
        val parts = (0 until intro.length()).mapNotNull { i ->
            intro.optJSONObject(i)?.optString("txt")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return parts.joinToString("\n\n")
    }

    /**
     * 关注 / 取消关注歌手（第⑭点）。
     *
     * 与关注用户是两套接口。坑：**取关必须带 `artistIds`（JSON 数组字符串）**，
     * 只给 `artistId` 会回 400 —— 桌面版 `electron/api/endpoints.js:764` 踩过同一个。
     */
    suspend fun artistSub(id: Long, sub: Boolean = true): Boolean? {
        val j = if (sub) {
            Client.tryPost("/artist/sub", JSONObject().put("artistId", id.toString()), lenient = true)
        } else {
            Client.tryPost(
                "/artist/unsub",
                JSONObject()
                    .put("artistId", id.toString())
                    .put("artistIds", JSONArray(listOf(id)).toString()),
                lenient = true,
            )
        }
        if (j.optInt("code", 500) != 200) return null
        // 服务端不一定回当前状态，取不到就按意图返回，让界面先动起来。
        return j.optJSONObject("data")?.optBoolean("followed") ?: sub
    }

    /** 歌手的专辑列表，歌手页的「专辑」区块用。 */
    suspend fun artistAlbums(id: Long, limit: Int = 30, offset: Int = 0): List<Album> {
        val j = Client.tryGet("/api/artist/albums/$id", mapOf("limit" to limit, "offset" to offset))
        val arr = j.optJSONArray("hotAlbums") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            Album(
                id = a.optLong("id", 0L),
                name = a.optString("name"),
                cover = httpsUrl(a.optString("picUrl").ifEmpty { a.optString("blurPicUrl") }),
                artistText = a.optJSONObject("artist")?.optString("name") ?: "",
                publishTime = a.optLong("publishTime"),
                size = a.optInt("size", 0),
            )
        }
    }

    /** 播客节目。节目本身不能播，真正的音源是 `mainSong`，所以这里直接返回 [Song]。 */
    suspend fun podcastPrograms(radioId: Long, limit: Int = 30, offset: Int = 0): Pair<Radio?, List<Song>> {
        val j = Client.tryGet(
            "/api/dj/program/byradio",
            mapOf("radioId" to radioId, "limit" to limit, "offset" to offset, "asc" to "false"),
        )
        val programs = j.optJSONArray("programs") ?: return null to emptyList()
        val radio = programs.optJSONObject(0)?.optJSONObject("radio")?.let { parseRadio(it) }
        val songs = (0 until programs.length()).mapNotNull { i ->
            val p = programs.optJSONObject(i) ?: return@mapNotNull null
            val song = parseSong(p.optJSONObject("mainSong")) ?: return@mapNotNull null
            // 节目封面比 mainSong 的专辑图更准，用它覆盖；主播当作歌手展示，
            // id 归零 —— 点进去不是歌手页，渲染层据此不把名字做成链接。
            val cover = httpsUrl(p.optString("coverUrl").ifEmpty { p.optString("blurCoverUrl") })
            val djName = p.optJSONObject("radio")?.optJSONObject("dj")?.optString("nickname") ?: ""
            song.copy(
                cover = cover.ifEmpty { song.cover },
                duration = if (p.has("duration")) p.optLong("duration", 0L) else song.duration,
                name = p.optString("name").ifEmpty { song.name },
                artists = listOf(Artist(0, djName.ifEmpty { "播客" })),
            )
        }
        return radio to songs
    }

    /** 听歌等级。用户页的「听歌排行 / 等级」区块用。 */
    suspend fun userLevel(uid: Long): Map<String, Any> {
        val j = Client.tryPost("/user/level", JSONObject().put("uid", uid.toString()))
        val d = j.optJSONObject("data") ?: JSONObject()
        return mapOf(
            "level" to d.optInt("level", 0),
            "info" to d.optString("info"),
            "progress" to d.optDouble("progress", 0.0),
            "nowPlayCount" to d.optInt("nowPlayCount", 0),
            "nextPlayCount" to d.optInt("nextPlayCount", 0),
            "nowLoginCount" to d.optInt("nowLoginCount", 0),
            "nextLoginCount" to d.optInt("nextLoginCount", 0),
        )
    }

    /**
     * 每日签到。
     *
     * 只有 `type=1`（pc 端）真的能签到；`type=0`（app 端）回
     * `{code:200, msg:"功能暂不支持"}`，按 code 判断会误判成成功。
     * 今日已签时服务端回 `code:-2 "重复签到"`，用这个区分「刚签上」与「已签过」。
     */
    suspend fun dailyTask(): Triple<Boolean, Boolean, Int> {
        val j = Client.tryPost("/point/dailyTask", JSONObject().put("type", 1))
        val code = j.optInt("code", 0)
        // 第一个布尔是「签到成功」，第二个是「今天已经签过」，第三个是得到的积分。
        return Triple(code == 200, code == -2, j.optInt("point", 0))
    }

    /**
     * 关注 / 取消关注。
     *
     * 走 APP 方言（`postApp`）：`/api/user/follow/<uid>` 在 weapi 下不回 `followed`，
     * 拿不到最终状态就只能乐观假设，取消关注成功后按钮还亮着。
     */
    suspend fun userFollow(uid: Long, follow: Boolean = true): Boolean? {
        val verb = if (follow) "follow" else "delfollow"
        val j = Client.tryPost("/api/user/$verb/$uid", JSONObject(), lenient = true)
        if (j.optInt("code", 500) != 200) return null
        val u = j.optJSONObject("user")
        return u?.optBoolean("followed") ?: follow
    }

    /** 关注 / 粉丝列表。用户页的两个入口。 */    suspend fun follows(uid: Long, limit: Int = 30, offset: Int = 0): Pair<List<User>, Int> {
        val j = Client.tryGet("/api/user/getfollows/$uid", mapOf("offset" to offset, "limit" to limit))
        val arr = j.optJSONArray("follow") ?: return emptyList<User>() to 0
        val users = (0 until arr.length()).mapNotNull { parseUser(arr.optJSONObject(it)) }
        return users to j.optInt("touchCount", users.size)
    }

    suspend fun followeds(uid: Long, limit: Int = 30, offset: Int = 0): Pair<List<User>, Int> {
        val j = Client.tryPost(
            "/user/getfolloweds",
            JSONObject().put("userId", uid.toString()).put("offset", offset.toString())
                .put("limit", limit.toString()).put("time", "0"),
        )
        val arr = j.optJSONArray("followeds") ?: return emptyList<User>() to 0
        val users = (0 until arr.length()).mapNotNull { parseUser(arr.optJSONObject(it)) }
        return users to j.optInt("newCount", users.size)
    }

    // ------------------------------------------------------------ 歌手 / 专辑

    suspend fun artistSongs(id: Long, limit: Int = 50, offset: Int = 0): List<Song> {
        val j = Client.tryGet(
            "/api/v1/artist/songs",
            mapOf("id" to id, "limit" to limit, "offset" to offset, "order" to "hot"),
        )
        return parseSongs(j.optJSONArray("songs"))
    }

    /**
     * 歌手全部作品的一页：`songs` 是这一页，另外把服务端的 `total` / `more` 也带回来。
     *
     * 与上面的 [artistSongs] 是同一条接口，区别只在要不要「总共多少首、还有没有下一页」——
     * 歌手页的「全部歌曲」要能一路翻到底（有的歌手两三千首），没有这两个字段就没法
     * 画「已加载 200 / 共 2573 首」，也不知道该不该继续给「加载更多」。
     */
    suspend fun artistSongPage(id: Long, limit: Int = 100, offset: Int = 0): SongPage {
        val j = Client.tryGet(
            "/api/v1/artist/songs",
            mapOf("id" to id, "limit" to limit, "offset" to offset, "order" to "hot"),
        )
        val songs = parseSongs(j.optJSONArray("songs"))
        // `more` 在部分响应里没有：这时用「这一页拿满了就假定还有下一页」兜底，
        // 多请求一次、但不会把明明还有的歌藏起来。
        val more = if (j.has("more")) j.optBoolean("more") else songs.size >= limit
        return SongPage(songs, j.optInt("total", songs.size), more)
    }

    suspend fun artistHotSongs(id: Long): List<Song> {
        val j = Client.tryGet("/api/v1/artist/$id")
        return parseSongs(j.optJSONArray("hotSongs"))
    }

    /**
     * 播客推荐（电台列表）。
     *
     * `/api/djradio/recommend` 会回 `code:400`（该接口只认网页端票据），
     * `/api/personalized/djprogram` 才是能用的那条 —— 它返回的是**节目**，
     * 电台要从 `program.radio` 反查，并按 radioId 去重（一个电台常有好几期在列表里）。
     * 桌面版 `electron/api/endpoints.js` 的 `podcasts()` 一直是这么取的。
     */
    suspend fun djRecommend(limit: Int = 30): List<Playlist> {
        val j = Client.tryGet("/api/personalized/djprogram", mapOf("limit" to limit))
        val arr = j.optJSONArray("result") ?: return emptyList()
        val seen = LinkedHashMap<Long, Playlist>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i)?.optJSONObject("program")?.optJSONObject("radio") ?: continue
            val p = parsePlaylist(r) ?: continue
            if (p.id != 0L && !seen.containsKey(p.id)) seen[p.id] = p
        }
        return seen.values.toList()
    }

    suspend fun album(id: Long): Pair<Album?, List<Song>> {
        val j = Client.tryGet("/api/v1/album/$id")
        val a = j.optJSONObject("album")
        val album = a?.let {
            Album(
                id = it.optLong("id"),
                name = it.optString("name"),
                cover = httpsUrl(it.optString("picUrl")),
                artistText = it.array("artists").objects().joinToString("/") { x -> x.optString("name") },
                publishTime = it.optLong("publishTime"),
                size = it.optInt("size", 0),
                // 第⑮点：专辑页要显示发行时间与简介。`description` 只在这个接口上
                // 有值（列表接口 `/api/artist/albums` 不带），所以这里单独取。
                description = it.optString("description"),
            )
        }
        return album to parseSongs(j.optJSONArray("songs"))
    }

    // ------------------------------------------------------------ 收藏 / 歌单增删

    /**
     * 红心 / 取消红心。
     *
     * 走 `/song/like` 而不是老的 `/radio/like`：真实账号上 `radio/like` 对每种
     * 参数形状都回 `-460 检测到您的网络环境存在风险`，而 `song/like` 在同一个
     * Cookie 下回 `{playlistId, code:200}`，歌真的进了「我喜欢的音乐」。
     * `radio/like` 留作兜底，防 `song/like` 哪天被下线。
     */
    suspend fun like(trackId: Long, like: Boolean = true): Boolean {
        val want = like.toString()
        val j = Client.tryPost("/song/like", JSONObject().put("trackId", trackId.toString()).put("like", want))
        if (j.optInt("code", 500) == 200) return true
        val fallback = Client.tryPost(
            "/radio/like",
            JSONObject().put("trackId", trackId.toString()).put("like", want).put("alg", "itembased").put("time", "3"),
        )
        return fallback.optInt("code", 500) == 200
    }

    suspend fun playlistAdd(pid: Long, trackIds: List<Long>): Boolean {
        val j = Client.tryPost(
            "/playlist/manipulate/tracks",
            JSONObject()
                .put("op", "add")
                .put("pid", pid.toString())
                .put("trackIds", JSONArray(trackIds.map { it }).toString())
                .put("imme", "true"),
        )
        return j.optInt("code", 500) == 200
    }

    suspend fun playlistRemove(pid: Long, trackIds: List<Long>): Boolean {
        val j = Client.tryPost(
            "/playlist/manipulate/tracks",
            JSONObject()
                .put("op", "del")
                .put("pid", pid.toString())
                .put("trackIds", JSONArray(trackIds.map { it }).toString())
                .put("imme", "true"),
        )
        return j.optInt("code", 500) == 200
    }

    suspend fun playlistCreate(name: String, privacy: Int = 0): Long {
        val j = Client.tryPost(
            "/playlist/create",
            JSONObject().put("name", name).put("privacy", privacy.toString()).put("type", "NORMAL"),
        )
        return j.optLong("id").takeIf { it != 0L } ?: j.optJSONObject("playlist")?.optLong("id") ?: 0L
    }

    suspend fun playlistDelete(pid: Long): Boolean {
        val j = Client.tryPost("/playlist/delete", JSONObject().put("pid", pid.toString()))
        return j.optInt("code", 500) == 200
    }

    /**
     * 改歌单名（第⑫点「编辑名称」）。
     *
     * 桌面版走 `/playlist/update`（`electron/api/endpoints.js:606`）。这个接口
     * 有个坑：`name` 必须同时放在顶层与 `data` 里 —— 只放顶层时服务端回
     * `code 200` 但名字不变，看着像成功。
     */
    suspend fun playlistUpdate(pid: Long, name: String): Boolean {
        val data = JSONObject().put("name", name).put("id", pid.toString()).put("desc", "")
        val j = Client.tryPost(
            "/playlist/update",
            JSONObject()
                .put("id", pid.toString())
                .put("name", name)
                .put("desc", "")
                .put("data", data.toString()),
        )
        return j.optInt("code", 500) == 200
    }

    /**
     * 收藏 / 取消收藏歌单。
     *
     * 取消收藏必须走 `/playlist/unsubscribe`：`/playlist/subscribe` 只认 t=1，
     * 带 `t:0` 过去会被判参数非法并回 `code 501`，界面上就是「点取消收藏报错」。
     */
    suspend fun subscribePlaylist(id: Long, subscribe: Boolean = true): Boolean {
        val j = if (!subscribe) {
            Client.tryPost("/playlist/unsubscribe", JSONObject().put("id", id.toString()))
        } else {
            Client.tryPost("/playlist/subscribe", JSONObject().put("id", id.toString()).put("t", 1))
        }
        return j.optInt("code", 500) == 200
    }

    /** 上报播放（计入听歌排行）。失败无所谓，所以是 tryPost。 */
    suspend fun scrobble(id: Long, sourceId: Long, time: Int = 0): JSONObject =
        Client.tryPost(
            "/scrobble",
            JSONObject()
                .put("id", id.toString())
                .put("sourceid", sourceId.toString())
                .put("time", time.toString())
                .put("total", "0"),
        )

    /**
     * 用户主页详情。
     *
     * `/api/v1/user/detail/<uid>` 把**等级 / 听歌数 / 今日是否签到**放在顶层，
     * 个人资料放在 `profile` 里 —— 两者要合并成一个对象，否则调用方从
     * `profile` 上取 `level` / `listenSongs` 会永远拿到 0。
     */
    suspend fun userDetail(uid: Long): User? {
        val j = Client.tryGet("/api/v1/user/detail/$uid")
        val p = j.optJSONObject("profile") ?: return null
        val base = parseUser(p) ?: return null
        return base.copy(
            follows = p.optInt("follows", 0),
            followeds = p.optInt("followeds", 0),
            playlistCount = p.optInt("playlistCount", 0),
            createTime = p.optLong("createTime"),
            level = j.optInt("level", 0),
            listenSongs = j.optInt("listenSongs", 0),
            // 网易云默认对陌生人隐藏听歌排行，页面据此提示而不是显示空列表
            recordVisible = j.optBoolean("peopleCanSeeMyPlayRecord", true),
            signedToday = j.optBoolean("pcSign"),
            followed = p.optBoolean("followed"),
        )
    }

    // ------------------------------------------------------------ 登录

    /**
     * 二维码登录：拿 key 与二维码内容。
     *
     * unikey **必须**从加密接口取，走明文 GET 拿到的 key 在轮询时一律回 800。
     */
    suspend fun qrCreate(): Pair<String, String> {
        val j = Client.tryPost("/login/qrcode/unikey", JSONObject().put("type", 1))
        val key = j.optString("unikey")
        return key to "https://music.163.com/login?codekey=$key"
    }

    /**
     * 轮询二维码状态。返回 code：
     *   800 过期 / 801 待扫描 / 802 待确认 / 803 成功（此时 Cookie 已进来）
     */
    suspend fun qrPoll(key: String): Triple<Int, String, String> {
        val j = Client.tryPost("/login/qrcode/client/login", JSONObject().put("key", key).put("type", 1))
        val nickname = j.optJSONObject("profile")?.optString("nickname") ?: ""
        return Triple(j.optInt("code", 801), j.optString("message"), nickname)
    }

    /**
     * 登录结果。
     *
     * 第⑤点报的是「密码是对的也提示登录失败」—— 根因就在这里：原来的
     * `loginPhone` 返回 `Boolean`，而 `Client.tryPost` 会把**网络异常**压成
     * `{code:-1}`、把**参数错误**压成 `{code:400}`，几种完全不同的失败在
     * 布尔值里长得一模一样，界面只能笼统地说「请检查账号密码」。密码明明
     * 是对的，用户就被这句话劝回去反复改密码。
     *
     * 所以这里把 code 与 message 原样带出来，由界面按真实原因说话。
     * code 约定：200 成功 / -1 网络或请求层失败（`__transport`）/ 其余为服务端码。
     */
    data class LoginResult(val code: Int, val message: String, val verify: RiskVerify? = null) {
        val ok: Boolean get() = code == 200

        /** 给人看的一句话。非 200 时优先用服务端 message，没有才回落。 */
        val hint: String
            get() = when {
                ok -> "登录成功"
                code == -1 -> "网络连接失败，请检查网络后重试"
                // -462 是账号级风控门（新设备登录时网易云回的就是「请确认是否本人账号」）。
                // 服务端那句话本身没告诉用户下一步怎么做，这里给一句能操作的；
                // 真正能做的动作由 [verify] 交给界面弹安全验证。
                code == -462 -> "网易云要求先完成安全验证（请确认是否本人账号）"
                message.isNotBlank() -> message
                code == 400 -> "手机号或密码格式不对"
                code == 502 -> "账号或密码错误"
                code == 509 -> "登录过于频繁，请稍后再试"
                code == 8810 || code == 8811 -> "需要完成安全验证，请稍后再试"
                else -> "登录失败（错误码 $code）"
            }
    }

    /**
     * 手机号 + 密码登录。
     *
     * `password` 必须发 **MD5 之后**的那串。服务端在 /weapi/login/cellphone 里
     * 只看得到 `md5_password`，它要是空才自己 `MD5(password)` —— 我们发明文过去，
     * 服务端会拿明文再算一次，出来是另一个哈希，于是永远回 502「账号或密码错误」。
     * 桌面版踩过同一个坑（`netease-lite\electron\api\endpoints.js` 的 `loginPhone`）。
     */
    suspend fun loginPhone(phone: String, password: String, countrycode: String = "86"): LoginResult {
        return loginGated(
            JSONObject()
                .put("phone", phone)
                .put("password", md5Hex(password))
                .put("countrycode", countrycode)
                .put("rememberLogin", "true"),
        )
    }

    /** 手机号 + 验证码登录（先 [sendCaptcha]）。 */
    suspend fun loginCaptcha(phone: String, captcha: String, countrycode: String = "86"): LoginResult {
        return loginGated(
            JSONObject()
                .put("phone", phone)
                .put("captcha", captcha)
                .put("countrycode", countrycode)
                .put("rememberLogin", "true"),
        )
    }

    /**
     * `/login/cellphone` 的统一出口：把 `-462`（账号级安全验证门）连同它带回的
     * 一整套 `verifyType / verifyId / verifyToken / event_id / sign` 一起交给界面。
     *
     * 为什么不能继续用 `Client.tryPost`：它把非 200 压成 `{code, message}`，
     * **payload 被丢掉**，而换二维码要的正是 payload 里那一包参数（桌面版
     * `followOutcome` 踩过同一个坑，见 `netease-lite\electron\api\endpoints.js`）。
     * 所以这里直接调 [Client.post] 并显式接住 [ApiException]。
     */
    private suspend fun loginGated(data: JSONObject): LoginResult = try {
        val j = Client.post("/login/cellphone", data)
        LoginResult(j.optInt("code", 500), j.optString("message"))
    } catch (e: ApiException) {
        val v = riskVerifyFrom(e.payload)
        if (e.code == -462 && v != null) {
            LoginResult(-462, e.message ?: "需要安全验证", v)
        } else {
            LoginResult(e.code, e.message ?: "登录失败")
        }
    } catch (e: TransportException) {
        LoginResult(-1, e.message ?: "网络连接失败")
    }

    /** 从 `-462` 的响应体里抽出安全验证参数；形状不对时返回 null。 */
    private fun riskVerifyFrom(payload: JSONObject?): RiskVerify? {
        val d = payload?.optJSONObject("data") ?: return null
        val token = d.optString("verifyToken")
        if (token.isEmpty()) return null
        val params = d.optJSONObject("params")
        return RiskVerify(
            verifyType = d.optString("verifyType"),
            verifyId = d.optString("verifyId"),
            verifyToken = token,
            blockText = d.optString("blockText"),
            eventId = params?.optString("event_id") ?: "",
            sign = params?.optString("sign") ?: "",
        )
    }

    /**
     * 用 `-462` 带回的参数换一张安全验证二维码（桌面版 `riskQr` 的移植）。
     *
     * 风控接口把成功写成**字符串** `"200"`，所以必须 `lenient = true` 走宽松解析，
     * 否则成功会被当失败抛出。
     */
    suspend fun riskQr(v: RiskVerify): RiskQr {
        val params = JSONObject().put("event_id", v.eventId).put("sign", v.sign).toString()
        val j = Client.tryPost(
            "/frontrisk/verify/getqrcode",
            JSONObject()
                .put("verifyConfigId", v.verifyId)
                .put("verifyType", v.verifyType)
                .put("token", v.verifyToken)
                .put("params", params)
                .put("size", 150),
            lenient = true,
        )
        val d = j.optJSONObject("data") ?: JSONObject()
        val qrCode = d.optString("qrCode")
        if (qrCode.isEmpty()) {
            val msg = j.optString("message").ifEmpty { j.optString("msg") }.ifEmpty { "二维码获取失败" }
            return RiskQr(ok = false, message = msg)
        }
        // 官方 H5 验证页：手机上直接打开即可完成验证（与扫码等价），
        // 而用户此刻正站在这台手机上 —— 这比二维码更实用。
        val url = "https://st.music.163.com/encrypt-pages" +
            "?qrCode=" + urlEncode(qrCode) +
            "&verifyToken=" + urlEncode(v.verifyToken) +
            "&verifyId=" + urlEncode(v.verifyId) +
            "&verifyType=" + urlEncode(v.verifyType) +
            "&params=" + urlEncode(params)
        return RiskQr(ok = true, qrCode = qrCode, imageUrl = d.optString("imageUrl"), verifyUrl = url)
    }

    /** 轮询扫码 / H5 验证结果。[RiskQrStatus.done] 为 true 就可以重试登录了。 */
    suspend fun riskQrStatus(qrCode: String): RiskQrStatus {
        val j = Client.tryPost("/frontrisk/verify/qrcodestatus", JSONObject().put("qrCode", qrCode), lenient = true)
        val d = j.optJSONObject("data") ?: JSONObject()
        val raw = d.opt("qrCodeStatus")
        val status = raw?.toString()?.toIntOrNull() ?: -1
        return RiskQrStatus(done = status >= 2, status = status, message = j.optString("message"))
    }

    /** 把一张图片抓成字节（安全验证二维码用：CDN 有防盗链，Coil 裸取会被 403）。 */
    suspend fun imageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", Http.UA)
                .header("Referer", Http.BASE + "/")
                .get()
                .build()
            Client.http.newCall(req).execute().use { res -> res.body?.bytes() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 发短信验证码。返回 `null` 表示成功，否则是一句能直接显示的失败原因
     * —— 第④点的冷却要按真实结果决定「能不能再点」，所以不能只给布尔。
     */
    suspend fun sendCaptcha(phone: String, countrycode: String = "86"): String? {
        val j = Client.tryPost("/sms/captcha/sent", JSONObject().put("cellphone", phone).put("ctcode", countrycode))
        val code = j.optInt("code", 500)
        if (code == 200) return null
        return when (code) {
            -1 -> "网络连接失败，请检查网络"
            400 -> "手机号格式不对"
            501 -> "该手机号尚未注册"
            // 服务端自己也会限频（同一号码 60 秒一条），撞上了就把它的原话给用户，
            // 比我们编一句「发送太频繁」更准。
            else -> j.optString("message").ifBlank { "验证码发送失败（错误码 $code）" }
        }
    }
}

/**
 * 一页歌（歌手「全部歌曲」在用）。
 *
 * [total] 是服务端说的总首数，[more] 是「还有下一页」；这两个字段决定了
 * 页头「共 N 首」和底部那颗「加载更多」要不要出现。
 */
data class SongPage(val songs: List<Song>, val total: Int, val more: Boolean)

/**
 * `-462` 带回的一包安全验证参数（用户 m03593 第⑥点）。
 *
 * 服务端在这个门里会附带 `verifyType / verifyId / verifyToken` 和一组
 * `params.event_id + params.sign`，拿它们去 [Endpoints.riskQr] 换二维码或
 * H5 验证链接。[blockText] 是网易云自己写的那句拦截说明（通常就是
 * 「请确认是否本人账号」），没有就用界面上的默认文案。
 */
data class RiskVerify(
    val verifyType: String,
    val verifyId: String,
    val verifyToken: String,
    val blockText: String = "",
    val eventId: String = "",
    val sign: String = "",
)

/** 一张安全验证二维码 / 一条等价的 H5 验证链接。 */
data class RiskQr(
    val ok: Boolean = false,
    val qrCode: String = "",
    val imageUrl: String = "",
    val verifyUrl: String = "",
    val message: String = "",
)

/**
 * 轮询结果。[status] 是服务端的 `qrCodeStatus`：0 未扫 / 1 已扫未确认 /
 * 2 已完成 —— 所以 `>= 2` 才算 [done]。
 */
data class RiskQrStatus(val done: Boolean = false, val status: Int = -1, val message: String = "")

/** H5 验证链接里的参数要百分号编码，和 [Client] 内部同一套规则。 */
private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
