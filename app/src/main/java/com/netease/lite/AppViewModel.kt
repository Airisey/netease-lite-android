package com.netease.lite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netease.lite.api.Client
import com.netease.lite.api.CookieJar
import com.netease.lite.api.Endpoints
import com.netease.lite.api.Http
import com.netease.lite.api.TransportException
import com.netease.lite.data.Downloader
import com.netease.lite.data.LocalIndex
import com.netease.lite.data.Prefs
import com.netease.lite.model.Album
import com.netease.lite.model.Artist
import com.netease.lite.model.ArtistProfile
import com.netease.lite.model.Comment
import com.netease.lite.model.Lyrics
import com.netease.lite.model.Playlist
import com.netease.lite.model.Radio
import com.netease.lite.model.Song
import com.netease.lite.model.SongUrl
import com.netease.lite.model.User
import com.netease.lite.model.songsFromJsonArray
import com.netease.lite.model.toJsonArray
import com.netease.lite.play.LocalSource
import com.netease.lite.play.Playback
import com.netease.lite.play.PlayerController
import com.netease.lite.play.SessionUiState
import com.netease.lite.play.TrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 「全部歌曲」页一次拉多少首。
 *
 * 100 是实测一次能拿满的页大小（artist 6452 按 100 翻页：100/100/…/79，
 * 最后一页 `more=false`）。分页本身靠 offset，页大小只影响请求数。
 */
private const val ALL_SONGS_PAGE = 100

/**
 * 全应用的状态中枢。
 *
 * 桌面版把所有状态摊在 `store.js` 的裸对象上、事件用 `store.emit` 广播；
 * Compose 的做法反过来：状态就是 `mutableStateOf`，改了谁读谁重组，
 * 不需要手动发事件。所以这里没有 `emit`，只有属性。
 *
 * 一处刻意的例外：[toast] 和 [error] 是「一次性」的，用完之后界面要清掉，
 * 不然转屏之后就又弹一遍。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = Prefs(app)
    val local = LocalIndex(app, prefs)
    val player = PlayerController(viewModelScope)

    // ------------------------------------------------------------ 全局状态
    var skin: String by mutableStateOf(prefs.skin)
        private set
    var quality: String by mutableStateOf(prefs.quality)
        private set

    /**
     * 界面动效开关。**必须是 Compose 状态而不是直接读 prefs**：设置页里一拨开关，
     * 外壳的 `AnimatedContent` 得当场知道，读 prefs 不会触发重组。
     */
    var animations: Boolean by mutableStateOf(prefs.animations)
        private set
    var sideCover: Boolean by mutableStateOf(prefs.sideCover)
        private set
    var listCover: Boolean by mutableStateOf(prefs.listCover)
        private set
    var autoCheckin: Boolean by mutableStateOf(prefs.autoCheckin)
        private set
    var autoSkip: Boolean by mutableStateOf(prefs.autoSkip)
        private set
    var startPage: String by mutableStateOf(prefs.startPage)
        private set
    var cacheMax: Int by mutableStateOf(prefs.cacheMax)
        private set
    var downloadQuality: String by mutableStateOf(prefs.downloadQuality)
        private set
    var downloadOrganize: String by mutableStateOf(prefs.downloadOrganize)
        private set
    var downloadTemplate: String by mutableStateOf(prefs.downloadTemplate)
        private set

    /**
     * 默认下载基目录（用户没选文件夹时用它）。
     *
     * 存相对内部存储根的路径，首段必须是公共音乐目录
     * （[Downloader.PUBLIC_ROOTS]）—— 规范化在 `Downloader.parseBase()`。
     *
     * 用户 m00932 第①点之后界面上**不再**让用户改这个值（原来那套「输入相对路径」
     * 受媒体库首段白名单限制，填 `Download/` 会下不下来）；现在唯一的口子是下面的
     * [downloadTree]，也就是系统文件夹选择器。这个字段因此退化成常量式的默认值。
     */
    var downloadDir: String by mutableStateOf(prefs.downloadDir)
        private set

    /**
     * 用户用 SAF 选的下载目录树（空串表示没选 = 用 [downloadDir]）。
     *
     * 非空时优先于 [downloadDir]，而且不受 MediaStore 标准子树的限制 ——
     * 用户 m00932 第①点要的「自行选择下载到的文件夹」就是这条路。
     */
    var downloadTree: String by mutableStateOf(prefs.downloadTree)
        private set

    /**
     * 侧栏隐藏了哪些条目（键见 [Prefs.SIDE_ITEMS]）。
     *
     * 作为 `Set` 存进状态而不是每次现切字符串：侧栏一条一条地读 `!hidden.contains(key)`，
     * 每次重组都 split 一遍字符串没必要。
     */
    var sideHidden: Set<String> by mutableStateOf(
        prefs.sideHidden.split(',').filter { it.isNotBlank() }.toSet(),
    )
        private set

    /**
     * 侧栏里被**折叠**起来的歌单分组（用户 m08537 第③点）。
     *
     * 与 [sideHidden] 分开：那个是「整组不显示」（设置页里勾掉），这个是
     * 「组标题还在、内容收起」。合成一个集合会让设置页的勾选与侧栏的折叠
     * 互相打架 —— 用户点标题想看里面，结果把整组从设置里关掉了。
     */
    var sideFolded: Set<String> by mutableStateOf(
        prefs.sideFolded.split(',').filter { it.isNotBlank() }.toSet(),
    )
        private set

    var me: User? by mutableStateOf(null)
        private set
    var loggedIn: Boolean by mutableStateOf(false)
        private set

    /**
     * 票据是否还在。
     *
     * 和 [loggedIn] 的区别：`loggedIn` 是界面状态，登录时那一次 `account()` 请求失败
     * 就会被压成 false；票据是本地 cookie，它是真的就算登录着（用户 m03593 第①点，
     * 侧栏那行「点这里重试」按这个判断该不该出现）。
     */
    val hasTicket: Boolean get() = CookieJar.isLoggedIn

    /** 我喜欢的音乐的 id 集合，红心状态用它。 */
    val likedIds = mutableStateMapOf<Long, Boolean>()

    /** 我创建/收藏的歌单（侧栏 + 「添加到歌单」用）。 */
    val playlists = mutableStateListOf<Playlist>()

    /** 正在拉歌单列表。[ensureLibrary] 用它防重入。 */
    private var libraryLoading = false

    /**
     * 歌单列表最后一次拉取是否失败（用户 m03593 第①点）。
     *
     * 原来 [refreshLibrary] 把异常静默吞掉，失败之后**再没有任何重试入口** ——
     * 登录时那一次请求要是赶上网络抖动，侧栏就一直是空的，除非用户退出重进。
     * 现在失败会置这个标记，侧栏据此显示一行「点这里重试」。
     */
    var libraryFailed: Boolean by mutableStateOf(false)
        private set

    /**
     * 侧栏歌单的缩略图：歌单 id → 第一首歌的封面。
     *
     * 为什么不能直接用 `Playlist.cover`：歌单自己的封面是用户上传的图（很多歌单
     * 干脆用默认灰图），而用户认一个歌单靠的是「里面第一首歌的封面」——桌面版
     * 就是这么做的。拉取时机与并发控制见 [loadSidebarCovers]。
     */
    var sideCovers: Map<Long, String> by mutableStateOf(loadSideCovers())
        private set
    private var coverToken = 0

    private fun loadSideCovers(): Map<Long, String> {
        val raw = prefs.sideCovers
        if (raw.isEmpty()) return emptyMap()
        return raw.split('\u0001').mapNotNull { rec ->
            val parts = rec.split('\u0002')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            id to parts[1]
        }.toMap()
    }

    /** 最近播放。落盘到 SharedPreferences，关掉应用也不丢。 */
    val recent = mutableStateListOf<Song>()

    /**
     * 下载队列。
     *
     * 第②点新增。落盘位置固定是公共音乐目录（见 [Downloader] 的注释），下完之后
     * 直接登记进 [local] 索引 —— 这样「已下载音乐」页、行内 √、本地优先播放全都
     * 自动生效，不需要另开一套「下载过的歌」数据。
     */
    val downloader = Downloader(app, viewModelScope, prefs = prefs) {
        // 每次落盘现取设置：用户在设置页改了文件名模板或分类/地址，正在排队的歌也照新规则走。
        Downloader.Options(
            organize = downloadOrganize,
            template = downloadTemplate,
            baseDir = downloadDir,
            treeUri = downloadTree,
        )
    }

    /** 下载队列快照（界面读这个）。 */
    var downloads: List<Downloader.Task> by mutableStateOf(emptyList())
        private set

    /** 「已下载音乐」页的列表：本地索引里那些歌的完整信息。 */
    val downloadSongs = mutableStateListOf<Song>()
    var localDir: String by mutableStateOf("")
        private set
    var localCount: Int by mutableStateOf(0)
        private set

    /** 收藏的歌曲（我喜欢的音乐）完整列表，歌单页首屏用。 */
    val likedSongs = mutableStateListOf<Song>()

    var toast: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /**
     * 界面要提示一句话时用这个。
     *
     * [toast] 本身是 `private set` —— 让界面能直接赋值会让「一次性提示」变成
     * 谁都能改的公共变量，关掉对话框该清空的约定就守不住了。这里只开一个写入口。
     */
    fun notify(msg: String) {
        toast = msg
    }

    /** 正在加载的提示；null 表示不显示。 */
    var loading: String? by mutableStateOf(null)
        private set

    // ------------------------------------------------------------ 导航
    var route: Route by mutableStateOf(Route.Startup)
        private set
    private val back = ArrayList<Route>()

    /** 搜索页的当前关键词。 */
    var searchKeyword: String by mutableStateOf("")
        private set

    /** 各路由的数据。用 map 而不是给每个路由一个字段 —— 路由有十来个。 */
    val dailySongs = mutableStateListOf<Song>()
    val recommend = mutableStateListOf<Playlist>()
    val charts = mutableStateListOf<Playlist>()
    val podcasts = mutableStateListOf<Playlist>()
    /** 音乐云盘（用户 m05071 第④点）。 */
    val cloudSongs = mutableStateListOf<Song>()
    var cloudCount: Int by mutableStateOf(0)
    var cloudSize: Long by mutableStateOf(0L)
    var cloudMaxSize: Long by mutableStateOf(0L)
    val searchSongs = mutableStateListOf<Song>()
    val searchPlaylists = mutableStateListOf<Playlist>()
    val searchArtists = mutableStateListOf<ArtistProfile>()
    val searchAlbums = mutableStateListOf<Album>()
    /** 搜索「用户」一栏的结果（用户 m03593 第②点）。 */
    val searchUsers = mutableStateListOf<User>()
    /** 搜索页当前 tab：1 单曲 / 1000 歌单 / 100 歌手 / 10 专辑（与服务端 type 一致）。 */
    var searchType: Int by mutableStateOf(1)
    val playlistSongs = mutableStateListOf<Song>()
    var playlistDetail: Playlist? by mutableStateOf(null)
        private set
    var albumDetail: Album? by mutableStateOf(null)
        private set
    val albumSongs = mutableStateListOf<Song>()
    var artistDetail: ArtistProfile? by mutableStateOf(null)
        private set
    val artistSongs = mutableStateListOf<Song>()
    /** 歌手页的「热门 50 首」，与全部作品分开列（桌面版也是这么分的）。 */
    val artistHot = mutableStateListOf<Song>()

    /**
     * 「全部歌曲」页（[Route.ArtistAll]）已经拉回来的歌，按服务端给的顺序。
     *
     * 一页 100 首：作品多的歌手动辄两三千首，一次全拉回来既慢又会把列表撑成长条。
     * 已加载多少 = 这个 list 的 size，下一页的 offset 就是它。
     */
    val artistAllSongs = mutableStateListOf<Song>()

    /** 服务端说的该歌手总曲数（页头「共 N 首」用）。0 = 还不知道。 */
    var artistAllTotal: Int by mutableStateOf(0)
        private set

    /** 还有下一页 —— 决定底部那颗「加载更多」显不显示。 */
    var artistAllMore: Boolean by mutableStateOf(true)
        private set

    var artistAllLoading: Boolean by mutableStateOf(false)
        private set
    /** 歌手的专辑墙（桌面版歌手页底部那一片）。 */
    var artistAlbums: List<Album> by mutableStateOf(emptyList())
    /**
     * 第⑬点：播放页点歌手名、而这首歌不止一位歌手时，从下方升起的选择面板。
     *
     * 空列表 = 面板关着。先用 [Song.artists] 的 id/name 立刻把面板立起来，
     * 头像随后补（见 [openArtistChoice]）—— 让人等三个网络往返才看到弹窗，
     * 比先看到名字、半秒后头像落位要难受得多。
     */
    var artistChoices: List<ArtistProfile> by mutableStateOf(emptyList())
        private set
    var userDetail: User? by mutableStateOf(null)
        private set
    val userSongs = mutableStateListOf<Song>()
    /** 用户主页上的歌单（桌面版右上角那一片卡片）。 */
    var userPlaylists: List<Playlist> by mutableStateOf(emptyList())
    /** 用户的关注 / 粉丝列表，[userListKind] 决定当前看的是哪一个。 */
    val userListUsers = mutableStateListOf<User>()
    var userListKind: String by mutableStateOf("follows")
        private set
    /** 播客节目的电台信息与节目列表（节目已被解析成可播放的歌曲）。 */
    var podcastRadio: Radio? by mutableStateOf(null)
        private set
    val podcastSongs = mutableStateListOf<Song>()
    /** 听歌等级的读数，桌面版用户页那块「Lv.N」用的就是它。 */
    var userLevel: Map<String, Any>? by mutableStateOf(null)
    var pageLoading: Boolean by mutableStateOf(false)
        private set
    var pageError: String? by mutableStateOf(null)
        private set

    /**
     * 本地扫描的进度（m00001 第②点）。null = 没在扫。
     *
     * 放在 ViewModel 而不是页面里：扫描从 SAF 选完目录那一刻才开始，而那一步是
     * 由「已下载音乐」页发起的 —— 但用户完全可能中途切到别的页面，进度浮层要
     * 继续跟着走、不能因为换页就消失。壳层（AppShell）统一画它。
     */
    var scanProgress: ScanProgress? by mutableStateOf(null)
        private set

    /**
     * 同时进行中的 [page] 层数。
     *
     * 只在 [page] 里、且只在主线程上增减（见那里的 NonCancellable 说明）。
     * 骨架屏要等最后一层结束才关：`scanLocal` 会在自己的 page 里调
     * `loadDownloads()`，而后者又起一个 page，两层叠着同一个 pageLoading。
     */
    private var pageDepth = 0

    // ------------------------------------------------------------ 歌词
    var lyrics: Lyrics by mutableStateOf(Lyrics())
        private set
    var lyricsLoading: Boolean by mutableStateOf(false)
        private set

    init {
        restoreAccount()
        recent.addAll(songsFromJsonArray(prefs.recent))
        // 本地索引是上次退出时留下的，启动就把它读进来（用户 m05071 第②点）：
        // 侧栏「已下载音乐」的角标不用等进过那一页才显示，进页面时 loadDownloads
        // 再按索引补齐歌曲详情。
        localCount = local.count
        localDir = local.dirs.firstOrNull().orEmpty()
        bindPlayer()
        // 下载完成的歌登记进本地索引：落盘目录是公共音乐目录，不在用户 SAF 选的
        // 那棵树里，扫描永远扫不到，只能在这里显式登记。
        downloader.onDone = { t ->
            if (t.uri.isNotEmpty()) {
                // 连音质一起记：切档时要靠它判断本地这份够不够（用户 m11461 第③点）。
                local.adopt(t.songId, t.uri, t.quality)
                // 记下落盘时间（m00001 第④点）：「已下载音乐」要按下载时间从新到旧排。
                noteDownloadTime(t.songId)
                localCount = local.count
            }
            viewModelScope.launch { loadDownloads() }
        }
        viewModelScope.launch {
            downloader.tasks.collect { downloads = it }
        }
    }

    /**
     * 冷启动的第一屏。
     *
     * 界面把 [Route.Startup] 画成「每日推荐」，但没有任何地方给它拉过数据 ——
     * 结果冷启动永远停在「今天的推荐还没出来」，要手动下拉一次才有内容。
     * 必须等票据真的落到 [CookieJar] 之后再调，否则第一次请求是匿名的。
     */
    private fun enterHomeIfPossible() {
        if (route != Route.Startup) return
        // 第⑥点「启动时打开的页面」：桌面的设置里也是这一组八项。
        // `last` 表示「上次离开的那一页」，读的是 go() 里记下的 lastRoute。
        val first = when (startPage) {
            "radio" -> Route.Recommend
            "charts" -> Route.Charts
            "podcast" -> Route.Podcasts
            "liked" -> Route.Liked
            "recent" -> Route.Recent
            "downloads" -> Route.Downloads
            "last" -> routeFromTag(prefs.lastRoute)
            else -> Route.Daily
        }
        go(first)
    }

    /** 把 [prefs.lastRoute] 记下的标签还原成路由。只覆盖侧栏里那几个顶层页。 */
    private fun routeFromTag(tag: String): Route = when (tag) {
        "radio" -> Route.Recommend
        "charts" -> Route.Charts
        "podcast" -> Route.Podcasts
        "liked" -> Route.Liked
        "recent" -> Route.Recent
        "downloads" -> Route.Downloads
        else -> Route.Daily
    }

    /**
     * 记住当前这一页，供「启动时打开上次离开的页面」使用。
     *
     * 只记顶层页：从「某个歌单」冷启动没有意义（参数得一起序列化，
     * 而用户说「上次那页」时想的通常是侧栏里那几项）。
     */
    private fun rememberRoute(r: Route) {
        val tag = when (r) {
            is Route.Daily, is Route.Startup -> "daily"
            is Route.Recommend -> "radio"
            is Route.Charts -> "charts"
            is Route.Podcasts -> "podcast"
            is Route.Liked -> "liked"
            is Route.Recent -> "recent"
            is Route.Downloads -> "downloads"
            else -> return
        }
        prefs.lastRoute = tag
    }

    // ------------------------------------------------------------ 账号
    private fun restoreAccount() {
        val cookie = prefs.cookie
        if (cookie.isNotEmpty()) CookieJar.setCookieString(cookie)
        if (!CookieJar.isLoggedIn) {
            // 没票据就停在 Startup 让登录页出现。
            return
        }
        // 票据是同步写进 CookieJar 的，所以这里进首页时第一个请求就带着登录态。
        enterHomeIfPossible()
        viewModelScope.launch {
            try {
                val u = Endpoints.account()
                if (u != null) {
                    me = u
                    loggedIn = true
                    prefs.uid = u.id
                    refreshLibrary()
                    // 「启动时自动签到」：只在确实还没签的时候打一次接口，
                    // 已经签过的（signedToday）连请求都省掉。
                    if (autoCheckin && !u.signedToday) checkin()
                } else {
                    // Cookie 过期了：清掉，别让界面一直显示「已登录」但什么都拉不到。
                    prefs.clearAccount()
                    CookieJar.setCookieString("")
                }
            } catch (e: Exception) {
                // 网络不好时**不**清 Cookie —— 下次有网还能用。
                me = prefs.uid.takeIf { it != 0L }?.let { User(id = it, nickname = "我") }
                loggedIn = me != null
            }
        }
    }

    /** 登录成功后调用：写票据、拉资料与歌单。 */
    suspend fun onLoginSuccess(cookie: String) {
        prefs.cookie = cookie
        CookieJar.setCookieString(cookie)
        // 用户 m03593 第①点：这里原来只问一次 account()，失败就走 me=null、loggedIn=false、
        // refreshLibrary() 直接 return 的路径 —— 用户看到的是「明明登录成功了，侧栏却
        // 一个歌单都没有」。票据此刻已经写进 CookieJar，所以先退避重试两轮再认输；
        // 真认输也只是资料没拿到，不能把刚登录的人打回登录页（CookieJar.isLoggedIn 为真
        // 就照样进首页，后面侧栏打开时 ensureLibrary() 会自动再补齐）。
        var u: User? = null
        for (attempt in 0..2) {
            u = runCatching { Endpoints.account() }.getOrNull()
            if (u != null) break
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        withContext(Dispatchers.Main) {
            me = u
            loggedIn = u != null || CookieJar.isLoggedIn
            if (u != null) prefs.uid = u.id
            toast = if (u != null) "登录成功" else "登录成功，资料没拉到，进侧栏会自动重试"
        }
        enterHomeIfPossible()
        refreshLibrary()
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { Endpoints.logout() }
            prefs.clearAccount()
            CookieJar.setCookieString("")
            withContext(Dispatchers.Main) {
                me = null
                loggedIn = false
                playlists.clear()
                likedIds.clear()
                likedSongs.clear()
                toast = "已退出登录"
            }
        }
    }

    /**
     * 侧栏歌单缩略图：换成「这个歌单第一首歌」的封面。
     *
     * 与桌面版 `App.loadSidebarCovers()` 同一套做法，理由也一样：
     *   - 几十个歌单逐个拉详情会把接口打爆，所以只拉 `limit: 1`（只回一首）；
     *   - 并发上限 4，其余排队；
     *   - 结果记在 [sideCovers] 里，重启后先用缓存那份，等新的回来再换；
     *   - 任何一个失败都不影响其余，静默保留歌单自己的封面。
     */
    fun loadSidebarCovers() {
        val list = playlists.filter { !it.isLiked }
        if (list.isEmpty()) return
        val token = ++coverToken
        viewModelScope.launch {
            val sem = Semaphore(4)
            val jobs = list.map { p ->
                if (sideCovers[p.id] != null) return@map run { null as kotlinx.coroutines.Job? }
                viewModelScope.launch {
                    sem.withPermit {
                        val cover = runCatching {
                            Endpoints.playlistDetail(p.id, limit = 1).second.firstOrNull()?.cover
                        }.getOrNull()
                        if (!cover.isNullOrEmpty() && token == coverToken) {
                            sideCovers = sideCovers + (p.id to cover)
                        }
                    }
                }
            }
            jobs.forEach { it?.join() }
            if (token == coverToken) {
                // 整份 JSON 落盘：几十个歌单几十条短字符串，SharedPreferences 撑得住，
                // 下次冷启动侧栏不用再空一轮。
                prefs.sideCovers = sideCovers.entries.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
            }
        }
    }

    /** 拉「我喜欢的音乐」的 id 与歌单列表。登录后与写操作后都要调。 */
    suspend fun refreshLibrary() {
        // `me` 为空但票据还在（登录时 account() 抖了一下）：先把资料补回来，
        // 否则下面拿不到 uid，侧栏就一直是空的。
        if (me == null) {
            val u = runCatching { Endpoints.account() }.getOrNull() ?: return
            withContext(Dispatchers.Main) {
                me = u
                loggedIn = true
                prefs.uid = u.id
            }
        }
        val uid = me?.id ?: return
        if (libraryLoading) return
        libraryLoading = true
        try {
            var ps: List<Playlist>? = null
            var likes: Set<Long>? = null
            // 用户 m03593 第①点：这两个请求失败一次，侧栏就空着不再自己好过来。
            // 登录那一刻刚好是「换完票据、连接刚复用」的时候，偶发失败并不罕见，
            // 所以自己退避重试两次，别把第一次的抖动直接变成「侧栏没有歌单」。
            var lastErr: Exception? = null
            for (attempt in 0..2) {
                try {
                    ps = Endpoints.userPlaylists(uid)
                    likes = Endpoints.likedIds(uid)
                    lastErr = null
                    break
                } catch (e: Exception) {
                    lastErr = e
                    if (attempt < 2) delay(400L * (attempt + 1))
                }
            }
            if (ps == null || likes == null) throw lastErr ?: IllegalStateException("歌单列表拉取失败")
            withContext(Dispatchers.Main) {
                playlists.clear(); playlists.addAll(ps!!)
                likedIds.clear(); for (id in likes!!) likedIds[id] = true
                libraryFailed = false
                // 红心列表刚到，通知栏那颗按钮的实心/空心得按新数据重画一次。
                syncSessionUi()
            }
        } catch (e: Exception) {
            // 拉不到歌单不该打断其它功能 —— 但要把失败记下来，让侧栏给一个重试入口。
            withContext(Dispatchers.Main) { libraryFailed = true }
        } finally {
            libraryLoading = false
        }
        // 歌单列表到手之后再去补缩略图：这一步要逐个歌单发请求，比列表本身慢得多，
        // 不能挡住侧栏先画出来。
        loadSidebarCovers()
    }

    /**
     * 「按需补齐」歌单列表（用户 m03593 第①点）。
     *
     * 侧栏每次打开都会调一次：`playlists` 已经有内容时它什么都不做（不会白发请求），
     * 只有「已登录但列表是空的」这种情况才真的去拉一次 —— 正好覆盖用户说的
     * 「登录后刷新一下侧边栏才出来」。
     *
     * @param force 忽略「已有内容」判断，强制重拉（侧栏那行「点这里重试」用它）。
     */
    fun ensureLibrary(force: Boolean = false) {
        // 判断用「票据在不在」而不是 `loggedIn`：登录那一刻 account() 失败会把 loggedIn
        // 压成 false，但票据是好的，这种情况照样该补拉一次。
        if (!loggedIn && !CookieJar.isLoggedIn) return
        if (libraryLoading) return
        if (!force && playlists.isNotEmpty()) return
        viewModelScope.launch { refreshLibrary() }
    }

    // ------------------------------------------------------------ 皮肤 / 音质
    fun useSkin(id: String) {
        skin = id
        prefs.skin = id
    }

    /**
     * 切换音质档（用户 m08537 第④点）。
     *
     * 除了落盘，还要**当场把正在放的那首重新加载一遍** —— 否则切完档什么都没变，
     * 用户以为没生效（原来的实现就是这样：`quality` 只影响下一次起播）。
     *
     * 重载走 [PlayerController.reloadCurrent]，它按「当前曲 → 原位置 → 原播放状态」
     * 换掉同一个 mediaItem 的 URI，进度不变、播放不中断。URI 仍然由
     * [resolveById] 决定，所以还是「本地优先，本地不行才在线」。
     */
    fun useQuality(q: String) {
        if (q == quality) return
        quality = q
        prefs.quality = q
        // 没有正在播的歌（冷启动、队列空）就只落盘，别去动播放器。
        // 把档位一起传下去：不传的话数据源层会去读 `Prefs.quality`，而本地
        // 索引里若有这首歌，`resolveById` 会直接返回本地文件 —— 声音一点变化
        // 都没有（用户 m09508 第②点）。传了档位就绕过本地优先，真的去取新流。
        if (player.currentSong != null) player.reloadCurrent(q)
    }

    // 设置页的开关。每个都是「先写内存储存的状态、再落 SharedPreferences」——
    // 反过来写的话开关要等下次冷启动才生效，等于拨了一下没反应。
    fun updateAnimations(v: Boolean) { animations = v; prefs.animations = v }
    fun updateSideCover(v: Boolean) { sideCover = v; prefs.sideCover = v }
    fun updateListCover(v: Boolean) { listCover = v; prefs.listCover = v }
    fun updateAutoCheckin(v: Boolean) { autoCheckin = v; prefs.autoCheckin = v }
    fun updateAutoSkip(v: Boolean) { autoSkip = v; prefs.autoSkip = v }
    fun updateStartPage(v: String) { startPage = v; prefs.startPage = v }
    fun updateCacheMax(v: Int) { cacheMax = v; prefs.cacheMax = v }
    fun updateDownloadQuality(v: String) { downloadQuality = v; prefs.downloadQuality = v }
    fun updateDownloadOrganize(v: String) { downloadOrganize = v; prefs.downloadOrganize = v }
    fun updateDownloadTemplate(v: String) { downloadTemplate = v; prefs.downloadTemplate = v }

    /**
     * 用户 m00932 第①点：设置页只留一个「选择文件夹…」按钮。
     *
     * 这里原来还有一个 `updateDownloadDir(v)`（让用户手输相对路径），已经删掉：
     * 那条路受系统媒体库首段白名单限制（`Download/` 之类会下不下来，见
     * `Downloader.PUBLIC_ROOTS` 的注释），而 SAF 选目录哪儿都能落，留着两条路
     * 只会让人挑到坏的那条。[downloadDir] 因此恒为 [Prefs.downloadDir] 的默认值。
     *
     * 传空串 = 恢复到默认位置。
     */
    fun updateDownloadTree(uri: String) {
        downloadTree = uri
        prefs.downloadTree = uri
        toast = if (uri.isEmpty()) "已恢复到默认下载位置" else "下载地址已改到所选文件夹"
    }

    fun toggleSideItem(key: String) {
        sideHidden = if (key in sideHidden) sideHidden - key else sideHidden + key
        prefs.sideHidden = sideHidden.joinToString(",")
    }

    /**
     * 「创建的歌单」「收藏的歌单」两组是否折叠（用户 m08537 第③点）。
     *
     * 与 [toggleSideItem] 共用 `sideHidden` 这一份存储、但用**不同的键**
     * （`own` / `sub`）：那两个键原本的语义是「这一组整个不显示」，而折叠
     * 是「组标题还在、里面收起来」。混用会让用户在设置里关掉分组之后，
     * 再点标题反而把它「展开」出来 —— 所以折叠单独记一组键。
     *
     * 落盘用 `side_folded`，与 `side_hidden` 并列，互不干扰。
     */
    fun toggleSideGroup(key: String) {
        sideFolded = if (key in sideFolded) sideFolded - key else sideFolded + key
        prefs.sideFolded = sideFolded.joinToString(",")
    }

    /** 恢复默认设置。皮肤与账号不动 —— 「恢复默认」不该把人踢下线。 */
    fun resetSettings() {
        updateAnimations(true)
        updateSideCover(true)
        updateListCover(true)
        updateAutoCheckin(false)
        updateAutoSkip(true)
        updateStartPage("daily")
        updateCacheMax(1024)
        updateDownloadQuality("lossless")
        updateDownloadOrganize("none")
        updateDownloadTemplate(Prefs.DEFAULT_DL_TEMPLATE)
        useQuality(Endpoints.DEFAULT_QUALITY)
        prefs.rememberProgress = true
        sideHidden = emptySet()
        prefs.sideHidden = ""
        toast = "已恢复默认设置"
    }

    // ------------------------------------------------------------ 播放接线
    private fun bindPlayer() {
        // 取流注册到 TrackSource 而不是 PlayerController：真正需要地址的是数据源层
        // （见 PlaceholderDataSourceFactory），只有在那儿才能同时覆盖界面点歌、
        // 通知栏下一首、蓝牙按键三条入口。
        TrackSource.install { id, q -> resolveById(id, q) }
        // 第⑫点：通知栏上那两颗自定义按钮的动作交回这里执行。红心要发网络请求、
        // 循环要重排队列，这些逻辑本来就在 ViewModel，在服务里再写一份必然走样。
        SessionUiState.onLikeRequested = { player.currentSong?.let { toggleLike(it) } }
        SessionUiState.onRepeatRequested = { cycleRepeat() }
        player.onSongStarted = { song ->
            pushRecent(song)
            // 通知栏/锁屏的封面靠 MediaMetadata，播起来之后补一次元数据，
            // 这样切歌瞬间通知不会还是上一首的封面。
            viewModelScope.launch { loadLyrics(song.id) }
            syncSessionUi()
        }
        player.onProgress = { song, pos ->
            if (prefs.rememberProgress && song.duration > 0 && pos > 5000 && pos < song.duration - 5000) {
                prefs.lastPlayId = song.id
                prefs.lastPlayPosition = pos
                prefs.lastPlayAt = System.currentTimeMillis()
            }
        }
        // 用户 m03123 第②点：队列本身也要跨重启保留。只在用户真的动了队列时落盘
        // （PlayerController 那边调 persist()），不是每次 tick 都写。
        player.onQueueChanged = { persistQueue() }
        player.onFailure = { song, why ->
            // 自动跳过与手动切歌走同一条路：让 PlayerController 里那段
            // `seekToNextMediaItem()` 的结果与菜单里的「下一首」完全一致。
            if (autoSkip && player.queueSize > 1) player.next() else error = if (song != null) "「${song.name}」$why" else why
        }
        // 用户 m48583 第②点：本地文件打不开/解不开时，明确告知「已转为在线资源」。
        // 只在真的从本地回落到在线时弹，纯在线播放失败仍走 [player.onFailure]。
        LocalSource.onFallback = { id ->
            // 两条触发路径的线程不一样：取流那层是在 ExoPlayer 的加载线程上经
            // PlaceholderDataSource 的 runBlocking 调过来的，解码那层才在主线程；
            // 而下面这几行写的全是 Compose 状态。所以统一 post 回主线程再动状态。
            viewModelScope.launch {
                val name = player.currentSong?.takeIf { it.id == id }?.name
                    ?: downloadSongs.firstOrNull { it.id == id }?.name
                toast = if (name != null) "「$name」本地无法播放，已转为在线资源" else "本地无法播放，已转为在线资源"
                // 索引已经摘掉了这一条：重画「已下载音乐」与行内 √，别让用户以为
                // 文件还在（下一次点开又会走一遍在线）。
                localCount = local.count
                // 用户 m00932 第②点：这是**回退触发的后台刷新**，不是用户要的页面
                // 加载，必须走 quiet —— 否则每次回退都把全屏换成骨架屏，一旦这次
                // 网络请求慢或失败，用户看到的就是「所有界面都在加载」。
                loadDownloads(quiet = true)
            }
        }
    }

    /**
     * 按 id 取流。数据源层手上只有一个 songId（`placeholder:<id>`），拿不到完整
     * 的 Song，所以这里只做「本地索引 → 按音质档回退」这件与歌曲内容无关的事。
     *
     * 退档的原因：无损档对未登录/非会员会回 `url: null`，但从 exhigh 退到 higher
     * 往往就能拿到试听或 128k。桌面版 `download.js` 的 `levelsFor()` 是同一思路。
     */
    suspend fun resolveById(id: Long, qualityOverride: String? = null): SongUrl {
        // 没有指定档位（正常起播、下一首、耳机按键）时保持「本地优先」。
        if (qualityOverride == null) localUri(id)?.let { return it }
        // 指定了档位（用户刚在播放页切了音质）：本地这份够这一档就直接用本地的，
        // 一个字节都不下（用户 m11461 第③点）。够不够由 [LocalIndex.satisfies]
        // 判断 —— 本地无损而用户要 320k 也算够，本地 128k 而用户要无损则不够。
        if (qualityOverride != null && local.satisfies(id, qualityOverride)) {
            localUri(id)?.let { return it }
        }
        LocalSource.noteRemote(id)
        val levels = levelsFrom(qualityOverride ?: quality)
        var last = SongUrl(id = id, failed = true, message = "没有可用音质")
        for (lv in levels) {
            val r = Endpoints.resolveUrl(id, lv)
            if (r.ok) return r
            last = r
            // 网络层就没通，换档再试也是白试，直接返回让上层提示「网络连接失败」。
            if (r.failed && r.code == -1) return r
        }
        // 在线一档都拿不到（断网、下架）时才退回本地：这时「音质不对」总好过
        // 「歌不能响」。
        if (qualityOverride != null) localUri(id)?.let { return it }
        return last
    }

    /**
     * 本地索引里那份还能用就返回它的 uri，否则把索引摘干净并返回 `null`。
     *
     * 索引里有、文件却没了（用户删了文件、SD 卡拔了、权限被回收），直接返回
     * 这个 uri 会让播放器报一个「网络连接失败」——既对不上事实，也把一首本来
     * 能在线听的歌变成不能听。所以先探一下，还在才用本地。
     *
     * 用户 m48583 第②点：探不通的时候要明确告诉用户「本地无法播放，已转为在线
     * 资源」，而不是让他以为这首没版权。判定与提示都在 [LocalSource] 里（两层：
     * 这里的「打不开」+ 播放器解码失败的「解不开」）。
     */
    private fun localUri(id: Long): SongUrl? {
        val uri = local.uriOf(id) ?: return null
        val usable = !LocalSource.isBroken(id) && local.exists(uri) &&
            LocalSource.isReadable(getApplication(), uri)
        if (usable) {
            LocalSource.noteLocal(id)
            return SongUrl(id = id, url = uri, level = "local", type = "local", playable = true)
        }
        // 走到这里说明本地这份指望不上：从索引摘掉、标成坏文件、通知界面
        // 弹提示，然后继续往下取在线流 —— 一次点击之内就完成回退，
        // 用户要的只是「歌能响」，不该让他再点一次。
        local.remove(id)
        LocalSource.failBeforeOpen(id)
        return null
    }

    /** 从用户选的档往回退到 standard。 */
    private fun levelsFrom(q: String): List<String> {
        val order = listOf("lossless", "exhigh", "higher", "standard")
        val at = order.indexOf(q).let { if (it < 0) 1 else it }
        return order.drop(at)
    }

    fun playSongs(songs: List<Song>, fromIndex: Int = 0, contextId: Long = 0, contextName: String = "") {
        if (songs.isEmpty()) return
        player.setRepeat(prefs.let { player.repeatMode })
        player.playList(songs, fromIndex, contextId, contextName)
    }

    fun playSong(song: Song) = playSongs(listOf(song), 0)

    /**
     * 随机播放（第⑪点的「随机播放」按钮）。
     *
     * 与桌面版 `views.playlist` 的 `#plShuffle` 同一语义：**只把这一个列表洗开、
     * 从第一首播**，不把全局循环模式切成随机。两者不是一回事 ——
     * 切模式会改掉迷你条上的循环图标，用户下次进别的歌单还以为自己在随机模式里。
     */
    fun playShuffle(songs: List<Song>, contextId: Long = 0, contextName: String = "") {
        if (songs.isEmpty()) return
        val shuffled = songs.shuffled()
        player.setRepeat(prefs.let { player.repeatMode })
        player.playList(shuffled, 0, contextId, contextName)
    }

    fun next() = player.next()
    fun previous() = player.previous()
    fun toggle() = player.toggle()
    fun seekTo(ms: Long) = player.seekTo(ms)

    fun cycleRepeat() {
        player.cycleRepeat()
        // 循环档位变了，通知栏那颗按钮的图标（列表/单曲/随机）要跟着换。
        syncSessionUi()
    }

    /**
     * 把「当前这首有没有红心」「循环在哪一档」推给通知栏。
     *
     * 这两件事服务进程自己看不见：`Player.repeatMode` 只有列表/单曲两档（随机是
     * 重排队列，见 [Playback.applyRepeat]），红心更是纯界面状态。所以每次可能变化的
     * 地方（切歌、点心、切循环、加载完红心列表）都戳一下这里。
     */
    private fun syncSessionUi() {
        val cur = player.currentSong
        SessionUiState.update(
            newLiked = cur != null && likedIds[cur.id] == true,
            newRepeat = player.repeatMode,
        )
    }

    fun toggleLike(song: Song) {
        val now = likedIds[song.id] == true
        val want = !now
        // 乐观更新：红心要立刻响应，失败再回滚。桌面版第①点是同一套做法。
        likedIds[song.id] = want
        syncSessionUi()
        viewModelScope.launch {
            try {
                Endpoints.like(song.id, want)
                if (want) {
                    if (likedSongs.none { it.id == song.id }) likedSongs.add(0, song)
                } else {
                    likedSongs.removeAll { it.id == song.id }
                }
                // 侧栏「我喜欢的音乐」的曲目数跟着变 —— 桌面版第④点就是漏了这一步。
                bumpLikedCount(if (want) 1 else -1)
            } catch (e: Exception) {
                likedIds[song.id] = now
                syncSessionUi()
                error = explain(e)
            }
        }
    }

    private fun bumpLikedCount(delta: Int) {
        val i = playlists.indexOfFirst { it.isLiked }
        if (i >= 0) {
            val p = playlists[i]
            playlists[i] = p.copy(trackCount = (p.trackCount + delta).coerceAtLeast(0))
        }
    }

    /** 把一首歌加进歌单，成功后刷新侧栏计数。 */
    fun addToPlaylist(pid: Long, songs: List<Song>) {
        viewModelScope.launch {
            try {
                Endpoints.playlistAdd(pid, songs.map { it.id })
                val i = playlists.indexOfFirst { it.id == pid }
                if (i >= 0) {
                    val p = playlists[i]
                    playlists[i] = p.copy(trackCount = p.trackCount + songs.size)
                }
                toast = "已添加到歌单"
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    fun removeFromPlaylist(pid: Long, songs: List<Song>) {
        viewModelScope.launch {
            try {
                Endpoints.playlistRemove(pid, songs.map { it.id })
                val i = playlists.indexOfFirst { it.id == pid }
                if (i >= 0) {
                    val p = playlists[i]
                    playlists[i] = p.copy(trackCount = (p.trackCount - songs.size).coerceAtLeast(0))
                }
                playlistSongs.removeAll { s -> songs.any { it.id == s.id } }
                toast = "已从歌单删除"
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                val id = Endpoints.playlistCreate(name)
                refreshLibrary()
                toast = "歌单「$name」已创建"
                if (id != 0L) go(Route.Playlist(id, name))
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    fun setPlaylistSubscribed(id: Long, subscribed: Boolean) {
        viewModelScope.launch {
            try {
                Endpoints.subscribePlaylist(id, subscribed)
                val i = playlists.indexOfFirst { it.id == id }
                if (i >= 0) playlists[i] = playlists[i].copy(subscribed = subscribed)
                toast = if (subscribed) "已收藏歌单" else "已取消收藏"
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    /**
     * 改歌单名（第⑫点长按菜单里的「编辑名称」）。
     *
     * 只改本地状态是在骗自己：刷新一次侧栏就变回旧名字了。所以先等服务端回 200，
     * 再改 `playlists` 里那一项。
     */
    fun renamePlaylist(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                if (!Endpoints.playlistUpdate(id, trimmed)) {
                    error = "改名失败，请稍后再试"
                    return@launch
                }
                val i = playlists.indexOfFirst { it.id == id }
                if (i >= 0) playlists[i] = playlists[i].copy(name = trimmed)
                // 当前正停在这个歌单页时，页头标题也要跟着变。
                val cur = route
                if (cur is Route.Playlist && cur.id == id) route = cur.copy(name = trimmed)
                toast = "已改名为「$trimmed」"
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    /**
     * 删除自己创建的歌单（第⑫点）。
     *
     * 服务端删成功后本地也要清两处：`playlists` 里那一项、以及侧栏封面缓存 ——
     * 后者按 id 存，不清的话下次建一个同 id 的歌单会带着旧封面。
     */
    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            try {
                if (!Endpoints.playlistDelete(id)) {
                    error = "删除失败，请稍后再试"
                    return@launch
                }
                playlists.removeAll { it.id == id }
                sideCovers = sideCovers - id
                prefs.sideCovers = sideCovers.entries.joinToString("\u0001") { "${it.key}\u0002${it.value}" }
                val cur = route
                if (cur is Route.Playlist && cur.id == id) goBack()
                toast = "已删除歌单"
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    /**
     * 把一个歌单整队塞进播放队列（第⑫点长按菜单的「添加到播放队列」）。
     *
     * 与「播放」的区别：不改当前正在放的那首、不动队列里的顺序，只往后追加。
     * 歌单动辄几百首，所以这里只拉 id 列表再批量取详情（与 [loadPlaylist] 同一条路）。
     */
    fun appendPlaylistToQueue(pid: Long, name: String) {
        viewModelScope.launch {
            try {
                val songs = Endpoints.playlistDetail(pid).second
                if (songs.isEmpty()) {
                    toast = "这个歌单里还没有歌"
                    return@launch
                }
                player.appendToQueue(songs)
                toast = "已把「$name」的 ${songs.size} 首加入队列"
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    /** 播放整个歌单（第⑫点长按菜单的「播放」）。 */
    fun playPlaylist(pid: Long, name: String) {
        viewModelScope.launch {
            try {
                val songs = Endpoints.playlistDetail(pid).second
                if (songs.isEmpty()) {
                    toast = "这个歌单里还没有歌"
                    return@launch
                }
                playSongs(songs, 0, pid, name)
            } catch (e: Exception) {
                error = explain(e)
            }
        }
    }

    // ------------------------------------------------------------ 最近播放
    /**
     * 加入最近播放。去重后插到最前，最多留 200 条。
     *
     * 每次变更都整体落盘一次：一秒钟写十几次 SharedPreferences 是浪费，
     * 但切歌频率本来就只有几十秒一次，简单直接比做批量提交更不容易出错。
     */
    fun pushRecent(song: Song) {
        val i = recent.indexOfFirst { it.id == song.id }
        if (i >= 0) recent.removeAt(i)
        recent.add(0, song)
        while (recent.size > 200) recent.removeAt(recent.size - 1)
        prefs.recent = recent.toList().toJsonArray()
    }

    fun clearRecent() {
        recent.clear()
        prefs.recent = "[]"
    }

    // ------------------------------------------------------------ 歌词
    private var lyricsFor = 0L

    suspend fun loadLyrics(id: Long) {
        lyricsFor = id
        withContext(Dispatchers.Main) {
            lyricsLoading = true
            lyrics = Lyrics()
        }
        try {
            val l = Endpoints.lyrics(id)
            withContext(Dispatchers.Main) {
                // 取的过程里用户可能已经切歌了，别把旧歌词糊到新歌上。
                if (lyricsFor == id) lyrics = l
                lyricsLoading = false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (lyricsFor == id) lyrics = Lyrics()
                lyricsLoading = false
            }
        }
    }

    // ------------------------------------------------------------ 导航
    /**
     * 第⑬点：播放页点了歌手名。
     *
     * 一位歌手直接进主页（和以前一样）；**多位歌手先弹一个从下方升起的面板**让人
     * 挑 —— 合唱曲目以前只能进第一位歌手的主页，另外几位在界面上等于不存在。
     *
     * 头像要单独问一次接口：`Song.artists` 里只有 id 和名字（`Artist` 故意不带头像，
     * 见 `ArtistProfile` 的注释 —— 最近播放要把整首歌落盘，多背几个永远为空的字段
     * 不划算）。所以先把面板用 id/name 立起来，再并发补头像，谁回来填谁。
     */
    fun openArtistChoice(song: Song) {
        val list = song.artists.filter { it.id > 0 }
        when {
            list.isEmpty() -> return
            list.size == 1 -> {
                go(Route.Artist(list[0].id, list[0].name))
                return
            }
        }
        artistChoices = list.map { ArtistProfile(it.id, it.name) }
        viewModelScope.launch {
            val filled = list.map { a ->
                async { runCatching { Endpoints.artistDetail(a.id) }.getOrNull() }
            }.awaitAll()
            withContext(Dispatchers.Main) {
                artistChoices = list.mapIndexed { i, a ->
                    filled[i]?.copy(name = a.name.ifEmpty { filled[i]!!.name })
                        ?: ArtistProfile(a.id, a.name)
                }
            }
        }
    }

    fun closeArtistChoice() {
        artistChoices = emptyList()
    }

    fun go(r: Route) {
        // 重复点同一个入口不该在回退栈里堆一串自己，否则要按好几次返回才动。
        if (r == route) return
        if (route != Route.Startup) back.add(route)
        route = r
        rememberRoute(r)
        when (r) {
            is Route.Daily -> loadDaily()
            is Route.Recommend -> loadRecommend()
            is Route.Charts -> loadCharts()
            is Route.Podcasts -> loadPodcasts()
            // 音乐云盘（m05071 第④点）：内容随时可能在手机上变，进页面就拉一次。
            is Route.Cloud -> loadCloud()
            is Route.Liked -> loadLiked()
            is Route.Recent -> Unit
            is Route.Downloads -> Unit
            // 下载队列的数据是 downloader.tasks 的 StateFlow 直接推过来的，
            // 进页面不需要再请求一次（只有本机索引要扫）。
            is Route.Downloading -> Unit
            is Route.Search -> Unit
            is Route.Playlist -> loadPlaylist(r.id)
            is Route.Album -> loadAlbum(r.id)
            is Route.Artist -> loadArtist(r.id)
            is Route.ArtistAll -> loadArtistAll(r.id)
            is Route.User -> loadUser(r.id)
            // 等级页要的数据（userLevel + userDetail）本来就由 fetchUser 一起取，
            // 单开一个加载器只会把同一份东西请求两遍。
            is Route.UserLevel -> loadUser(r.id)
            is Route.UserList -> loadUserList(r.id, r.kind)
            is Route.Podcast -> loadPodcast(r.id)
            is Route.Comments -> loadComments(r.type, r.id)
            is Route.Settings -> Unit
            is Route.NowPlaying -> Unit
            is Route.Queue -> Unit
            is Route.Startup -> Unit
        }
    }

    fun goBack() {
        val prev = back.removeLastOrNull() ?: return
        route = prev
        // 回退不重新拉数据：上一页的数据还在，重拉一次会让界面闪一下。
    }

    val canGoBack: Boolean get() = back.isNotEmpty()

    fun search(kw: String, type: Int = 1) {
        searchKeyword = kw
        route = Route.Search
        searchType = type
        viewModelScope.launch {
            page(hint = "搜索中") {
                when (type) {
                    1 -> {
                        searchSongs.clear(); searchSongs.addAll(Endpoints.searchSongs(kw, 50))
                    }
                    1000 -> {
                        searchPlaylists.clear(); searchPlaylists.addAll(Endpoints.searchPlaylists(kw, 50))
                    }
                    100 -> {
                        searchArtists.clear(); searchArtists.addAll(Endpoints.searchArtists(kw, 50))
                    }
                    10 -> {
                        searchAlbums.clear(); searchAlbums.addAll(Endpoints.searchAlbums(kw, 50))
                    }
                    1002 -> {
                        searchUsers.clear(); searchUsers.addAll(Endpoints.searchUsers(kw, 50))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 各页加载
    /**
     * 一次页面加载的公共外壳。
     *
     * [quiet] = true 时**不置 pageLoading、不清 pageError** —— 下拉刷新专用。
     * 用户手上已经有一屏歌，刷新时把内容换成骨架屏会让人以为「刚才那些没了」；
     * 刷新只该在顶部转一个小圈。
     */
    private suspend fun page(quiet: Boolean = false, hint: String? = null, block: suspend () -> Unit) {
        if (!quiet) {
            withContext(Dispatchers.Main) {
                pageDepth++
                pageLoading = true
                pageError = null
                if (hint != null) loading = hint
            }
        }
        try {
            block()
            if (quiet) withContext(Dispatchers.Main) { pageError = null }
        } catch (e: CancellationException) {
            // 取消不是「加载失败」。以前它会被下面的 catch (e: Exception) 当成普通
            // 错误吞掉 —— CancellationException 在 Kotlin 里继承 IllegalStateException，
            // 于是「已取消」被当成「出错了」，紧接着又在已取消的协程上 withContext，
            // 控制流连 finally 都走不干净。这里原样抛出去，复位交给 finally。
            throw e
        } catch (e: Exception) {
            // 静默刷新失败**不清空已有内容**，只在顶部提示一句 ——
            // 网络抖一下就把整页替换成错误页，比不刷新还糟。
            // 这里的 withContext 必须 NonCancellable，理由见 finally。
            withContext(NonCancellable + Dispatchers.Main) {
                if (quiet) toast = explain(e) else pageError = explain(e)
            }
        } finally {
            if (!quiet) {
                // 用户 m00932 第②点（本地放不了 → 弹提示 → 整个应用卡死、所有界面
                // 都在加载）的结构性成因就在这一句：
                //
                // pageLoading 是**全局**的，`ui/Pages.kt:86` 只要看到它就把整页换成
                // 骨架屏。而这里的 withContext 原来没有 NonCancellable —— 协程一旦
                // 被取消（Endpoints 里 `coroutineScope { async { … }.map { it.await() } }`
                // 这种扇出，一个子任务失败会连带取消兄弟，await() 抛的正是
                // CancellationException；切歌、连点导航也会取消上一个 page），
                // withContext 会**立刻抛出而不执行块内代码**，于是 pageLoading 永久
                // 停在 true：每个页面都变成「加载中」，看起来就是整个应用卡死。
                //
                // 用 pageDepth 计数而不是直接置 false，是因为扫描目录那条路
                // （scanLocal → loadDownloads）会套出两个并发的 page；没有计数时
                // 先结束的那个会把还在加载的那个的骨架屏提前关掉。
                withContext(NonCancellable + Dispatchers.Main) {
                    pageDepth = (pageDepth - 1).coerceAtLeast(0)
                    if (pageDepth == 0) {
                        pageLoading = false
                        loading = null
                    }
                }
            }
        }
    }

    fun loadDaily() = viewModelScope.launch { fetchDaily(false) }
    private suspend fun fetchDaily(quiet: Boolean) = page(quiet) {
        val s = Endpoints.dailyRecommend()
        withContext(Dispatchers.Main) { dailySongs.clear(); dailySongs.addAll(s) }
    }

    fun loadRecommend() = viewModelScope.launch { fetchRecommend(false) }
    private suspend fun fetchRecommend(quiet: Boolean) = page(quiet) {
        val p = Endpoints.recommendPlaylists(18)
        withContext(Dispatchers.Main) { recommend.clear(); recommend.addAll(p) }
    }

    fun loadCharts() = viewModelScope.launch { fetchCharts(false) }
    private suspend fun fetchCharts(quiet: Boolean) = page(quiet) {
        val p = Endpoints.toplists()
        withContext(Dispatchers.Main) { charts.clear(); charts.addAll(p) }
    }

    fun loadPodcasts() = viewModelScope.launch { fetchPodcasts(false) }
    private suspend fun fetchPodcasts(quiet: Boolean) = page(quiet) {
        val p = Endpoints.djRecommend()
        withContext(Dispatchers.Main) { podcasts.clear(); podcasts.addAll(p) }
    }

    // ------------------------------------------------------------------ 音乐云盘

    /**
     * 云盘上传的当前阶段（「校验」/「准备」/「上传」/「入库」），null = 没在传。
     * 界面拿它显示按钮上的进度文案，同时禁用按钮防重复点击。
     */
    var cloudUploading: String? by mutableStateOf(null)
        private set

    /** 云盘上传的结果提示（成功一首 / 失败原因），显示在底部上传条上。 */
    var cloudUploadNote: String by mutableStateOf("")

    /**
     * 上传一个本地音频到云盘（用户 m05718）。
     *
     * 文件从 SAF 的 Uri 读，整块交给 [Endpoints.cloudUpload]；成功后顺手刷新
     * 列表，让新歌马上出现在页面上。同一时刻只允许一首在传 —— 并发上传会被
     * NOS 按账号限速，反而更慢。
     */
    fun uploadToCloud(uri: android.net.Uri) {
        if (!loggedIn) {
            notify("登录后才能上传到云盘")
            return
        }
        if (cloudUploading != null) {
            notify("正在上传，等这一首传完")
            return
        }
        // 云盘里已经有「标题 + 歌手」都一样的歌就不再传一遍（用户 m05942 第②点）。
        // 判断放在读文件之前：撞车时连字节都不用读，省一次 20MB 的内存拷贝。
        val dupName = runCatching {
            val n = Endpoints.displayName(getApplication(), uri) ?: ""
            n.substringBeforeLast('.')
        }.getOrNull().orEmpty()
        if (dupName.isNotEmpty() && cloudHasSong(dupName)) {
            val msg = "有 1 首歌在云盘歌单中已存在，上传时已跳过"
            cloudUploadNote = msg
            notify(msg)
            return
        }
        viewModelScope.launch {
            cloudUploading = "校验"
            cloudUploadNote = ""
            val r = Endpoints.cloudUpload(getApplication(), uri) { stage ->
                cloudUploading = stage
            }
            cloudUploading = null
            if (r.ok) {
                cloudUploadNote = "已上传《${r.name}》"
                notify("已上传《${r.name}》")
                fetchCloud(true)
            } else {
                cloudUploadNote = "上传失败：${r.error}"
                notify("上传失败：${r.error}")
            }
        }
    }

    /**
     * 音乐云盘（用户 m05071 第④点）。
     *
     * 未登录时直接给空列表：接口会回 301，报错弹「请先登录」很吵，而这一页自己
     * 已经有「登录后才能看」的空态文案，交给它更清楚。
     */
    fun loadCloud() = viewModelScope.launch { fetchCloud(false) }

    private suspend fun fetchCloud(quiet: Boolean) {
        if (!loggedIn) {
            withContext(Dispatchers.Main) { cloudSongs.clear(); cloudCount = 0 }
            return
        }
        page(quiet) {
            val p = Endpoints.cloudSongs()
            withContext(Dispatchers.Main) {
                cloudSongs.clear(); cloudSongs.addAll(p.songs)
                cloudCount = p.count
                cloudSize = p.size
                cloudMaxSize = p.maxSize
            }
        }
    }

    fun loadLiked() = viewModelScope.launch { fetchLiked(false) }

    private suspend fun fetchLiked(quiet: Boolean) {
        val uid = me?.id ?: return
        page(quiet) {
            val (pl, songs) = Endpoints.likedSongs(uid)
            withContext(Dispatchers.Main) {
                likedSongs.clear(); likedSongs.addAll(songs)
                if (pl != null) {
                    val i = playlists.indexOfFirst { it.isLiked }
                    if (i >= 0) playlists[i] = pl
                }
                likedIds.clear(); for (s in songs) likedIds[s.id] = true
            }
        }
    }

    fun loadPlaylist(id: Long) = viewModelScope.launch { fetchPlaylist(id, false) }
    private suspend fun fetchPlaylist(id: Long, quiet: Boolean) = page(quiet) {
        // `playlistDetail` 内部已经处理了「v6 对别人的歌单只内联 20 首」这件事：
        // 曲目多的时候它会拿 trackIds 走 `/v3/song/detail` 补全并按顺序重排。
        // 这正是桌面版 m34266 修的 bug（`playlistTrackIds`）。
        val (pl, full) = Endpoints.playlistDetail(id)
        withContext(Dispatchers.Main) {
            playlistDetail = pl
            playlistSongs.clear(); playlistSongs.addAll(full)
        }
    }

    private fun loadAlbum(id: Long) = viewModelScope.launch { fetchAlbum(id, false) }
    private suspend fun fetchAlbum(id: Long, quiet: Boolean) = page(quiet) {
        val (a, songs) = Endpoints.album(id)
        withContext(Dispatchers.Main) {
            albumDetail = a
            albumSongs.clear(); albumSongs.addAll(songs)
        }
    }

    private fun loadArtist(id: Long) = viewModelScope.launch { fetchArtist(id, false) }

    /**
     * 歌手「全部歌曲」页：先拉第一页。
     *
     * 重新进这一页（或从歌手页点第二次）要重置，否则会看到上一次剩在列表里的
     * 几十首、offset 也已经跑到几百，越翻越乱。
     */
    private fun loadArtistAll(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.Main) {
            artistAllSongs.clear()
            artistAllTotal = 0
            artistAllMore = true
            artistAllLoading = true
        }
        try {
            val r = Endpoints.artistSongPage(id, ALL_SONGS_PAGE, 0)
            withContext(Dispatchers.Main) {
                artistAllSongs.addAll(r.songs)
                // 接口有时不给 total（只给 more）：退化成「已加载多少就显示多少」。
                artistAllTotal = if (r.total > 0) r.total else r.songs.size
                artistAllMore = r.more && r.songs.isNotEmpty()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast = "歌曲加载失败：${e.message ?: "网络错误"}" }
        } finally {
            withContext(Dispatchers.Main) { artistAllLoading = false }
        }
    }

    /**
     * 「加载更多」：接在当前列表后面。
     *
     * 用列表当前的 size 当 offset（服务端按 0/100/200… 分页），不另存页码 ——
     * 页码和列表长度一旦各记一份，中间漏掉一页就再也对不上了。
     * 翻页边界偶尔会重复给同一首，按 id 去重后再加，否则列表里会出现两行一样的歌。
     */
    fun loadMoreArtistSongs() {
        val offset = artistAllSongs.size
        if (artistAllLoading || !artistAllMore || offset == 0) return
        val id = (route as? Route.ArtistAll)?.id ?: return
        viewModelScope.launch {
            artistAllLoading = true
            try {
                val r = Endpoints.artistSongPage(id, ALL_SONGS_PAGE, offset)
                withContext(Dispatchers.Main) {
                    val seen = artistAllSongs.map { it.id }.toHashSet()
                    artistAllSongs.addAll(r.songs.filter { !seen.contains(it.id) })
                    if (r.total > 0) artistAllTotal = r.total
                    artistAllMore = r.more && r.songs.isNotEmpty()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast = "加载失败：${e.message ?: "网络错误"}" }
            } finally {
                withContext(Dispatchers.Main) { artistAllLoading = false }
            }
        }
    }
    private suspend fun fetchArtist(id: Long, quiet: Boolean) = page(quiet) {
        // 桌面版歌手页有头像、简介、粉丝数；这里原来只传了 id、名字留空 ——
        // 于是页头永远是「歌手」两个字。补一次 artistDetail。
        val d = runCatching { Endpoints.artistDetail(id) }.getOrNull()
        val albums = runCatching { Endpoints.artistAlbums(id, 30) }.getOrDefault(emptyList())
        val songs = Endpoints.artistSongs(id, 100)
        val hot = runCatching { Endpoints.artistHotSongs(id) }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) {
            artistDetail = d ?: ArtistProfile(id = id)
            artistAlbums = albums
            artistSongs.clear(); artistSongs.addAll(songs)
            artistHot.clear(); artistHot.addAll(hot)
        }
    }

    private fun loadUser(id: Long) = viewModelScope.launch { fetchUser(id, false) }
    private suspend fun fetchUser(id: Long, quiet: Boolean) = page(quiet) {
        val u = Endpoints.userDetail(id)
        val rec = runCatching { Endpoints.playRecord(id, 0) }.getOrDefault(emptyList())
        val lists = runCatching { Endpoints.userPlaylists(id) }.getOrDefault(emptyList())
        val lv = runCatching { Endpoints.userLevel(id) }.getOrNull()
        withContext(Dispatchers.Main) {
            userDetail = u
            userSongs.clear(); userSongs.addAll(rec)
            userPlaylists = lists
            userLevel = lv
        }
    }

    /**
     * 每日签到。只有登录后自己主页上的那颗按钮会调它。
     *
     * 服务端回 `-2` 表示今天已经签过 —— 这不是失败，要把按钮切成「今日已签到」
     * 而不是弹一句“签到失败”。
     */
    fun checkin() = viewModelScope.launch {
        val (ok, already, point) = runCatching { Endpoints.dailyTask() }
            .getOrElse { Triple(false, false, 0) }
        // 用户页读的是 userDetail、抽屉读的是 me，两处都要切，否则按钮看着像没点上。
        fun markSigned() {
            val m = me
            if (m != null) me = m.copy(signedToday = true)
            val u = userDetail
            if (u != null) userDetail = u.copy(signedToday = true)
        }
        when {
            ok -> {
                markSigned()
                toast = if (point > 0) "签到成功，获得 $point 积分" else "签到成功"
            }
            already -> {
                markSigned()
                toast = "今天已经签到过了"
            }
            else -> toast = "签到失败，请稍后再试"
        }
    }

    /** 关注 / 取消关注。[followed] 是界面已经切好的目标状态。 */
    fun followUser(uid: Long, follow: Boolean) = viewModelScope.launch {
        // 先切界面，失败再回滚 —— 网络往返 300ms 里按钮不动会让人以为没点上。
        val u = userDetail
        val prev = u?.followed
        if (u != null) userDetail = u.copy(followed = follow)
        val real = runCatching { Endpoints.userFollow(uid, follow) }.getOrNull()
        if (real == null) {
            if (u != null) userDetail = u.copy(followed = prev == true)
            toast = if (follow) "关注失败，请稍后再试" else "取消关注失败，请稍后再试"
        } else {
            val cur = userDetail
            if (cur != null) {
                userDetail = cur.copy(
                    followed = real,
                    followeds = (cur.followeds + if (real) 1 else -1).coerceAtLeast(0),
                )
            }
            toast = if (real) "已关注" else "已取消关注"
        }
    }

    /**
     * 关注 / 取消关注歌手（第⑭点）。
     *
     * 与 [followUser] 同一套乐观更新：先切界面，失败回滚并说清楚。
     * 歌手页只读 [artistDetail]，所以这里只动它。
     */
    fun followArtist(aid: Long, follow: Boolean) = viewModelScope.launch {
        val a = artistDetail
        val prev = a?.followed
        if (a != null) artistDetail = a.copy(followed = follow)
        val real = runCatching { Endpoints.artistSub(aid, follow) }.getOrNull()
        if (real == null) {
            if (a != null) artistDetail = a.copy(followed = prev == true)
            toast = if (follow) "关注失败，请稍后再试" else "取消关注失败，请稍后再试"
        } else {
            val cur = artistDetail
            if (cur != null) artistDetail = cur.copy(followed = real)
            toast = if (real) "已关注" else "已取消关注"
        }
    }

    /** 用户页的「关注 / 粉丝」列表。 */    fun loadUserList(uid: Long, kind: String) = viewModelScope.launch {
        userListKind = kind
        page(hint = "读取中") {
            val (users, _) = if (kind == "followeds") Endpoints.followeds(uid, 60) else Endpoints.follows(uid, 60)
            withContext(Dispatchers.Main) {
                userListUsers.clear(); userListUsers.addAll(users)
            }
        }
    }

    /** 播客节目页：一个电台的所有节目（已解析成可播放的歌曲）。 */
    fun loadPodcast(radioId: Long) = viewModelScope.launch {
        page {
            val (radio, songs) = Endpoints.podcastPrograms(radioId, 100)
            withContext(Dispatchers.Main) {
                podcastRadio = radio
                podcastSongs.clear(); podcastSongs.addAll(songs)
            }
        }
    }

    // ------------------------------------------------------------ 评论
    /** 评论页的数据。热评与最新分开存，界面按「热评在前」的顺序拼。 */
    val commentsHot = mutableStateListOf<Comment>()
    val commentsNew = mutableStateListOf<Comment>()
    var commentsTotal: Int by mutableStateOf(0)
        private set
    var commentThread: String by mutableStateOf("")
        private set

    /**
     * 拉一页评论。
     *
     * 不给全文缓存：评论是「点进去看一眼」的内容，几十条正文比一屏歌重得多，
     * 缓存它反而拖慢每次返回。切页时清空重拉即可。
     */
    fun loadComments(type: String, id: Long) = viewModelScope.launch {
        // 点赞要 threadId，接口里它是「类型前缀 + 资源 id」，与评论列表同一个来源。
        commentThread = threadPrefix(type) + id
        page {
            val (total, hot, new) = Endpoints.comments(type, id, 30, 0)
            withContext(Dispatchers.Main) {
                commentsTotal = total
                commentsHot.clear(); commentsHot.addAll(hot)
                commentsNew.clear(); commentsNew.addAll(new)
            }
        }
    }

    /** 评论点赞：先动界面再落库，失败回滚（与红心同一个套路）。 */
    fun likeComment(c: Comment, like: Boolean, hot: Boolean) = viewModelScope.launch {
        val list = if (hot) commentsHot else commentsNew
        val i = list.indexOfFirst { it.id == c.id }
        if (i < 0) return@launch
        val base = list[i]
        list[i] = base.copy(liked = like, likedCount = base.likedCount + if (like) 1 else -1)
        val ok = runCatching { Endpoints.commentLike(commentThread, c.id, like) }.getOrDefault(false)
        if (!ok) {
            list[i] = base
            toast = "点赞失败，请稍后再试"
        }
    }

    private fun threadPrefix(type: String): String = when (type) {
        "playlist" -> "A_PL_0_"
        "album" -> "R_AL_3_"
        "dj" -> "R_DJ_3_"
        "mv" -> "R_MV_5_"
        else -> "R_SO_4_"
    }

    /** 下拉刷新（第②点）：重新拉当前页，保留已有内容。 */
    var refreshing: Boolean by mutableStateOf(false)
        private set

    fun refresh() = viewModelScope.launch {
        if (refreshing) return@launch
        refreshing = true
        try {
            when (val r = route) {
                // 冷启动停在 Startup，而界面把它画成了「每日推荐」——
                // 这里不接住的话，那一页下拉刷新永远落到 else、什么也不拉。
                is Route.Startup -> fetchDaily(true)
                is Route.Daily -> fetchDaily(true)
                is Route.Recommend -> fetchRecommend(true)
                is Route.Charts -> fetchCharts(true)
                is Route.Podcasts -> fetchPodcasts(true)
                is Route.Liked -> fetchLiked(true)
                is Route.Playlist -> fetchPlaylist(r.id, true)
                is Route.Album -> fetchAlbum(r.id, true)
                is Route.Artist -> fetchArtist(r.id, true)
                is Route.User -> fetchUser(r.id, true)
                is Route.UserList -> loadUserList(r.id, r.kind)
                is Route.Podcast -> loadPodcast(r.id)
                is Route.Comments -> loadComments(r.type, r.id)
                is Route.Downloads -> loadDownloads()
                is Route.Recent -> Unit
                else -> Unit
            }
        } finally {
            refreshing = false
        }
    }

    // ------------------------------------------------------------ 本地 / 已下载
    /**
     * 刷新「已下载音乐」页的数据。
     *
     * 桌面版这一页的数据来自下载队列的历史（`download-history.json`）；Android 侧
     * 没有下载队列，这一页表达的是**「本机有哪些歌」**，所以数据源是 SAF 本地索引：
     * 索引里只有 id 和 content URI，歌名/歌手/封面要另外从服务端按 id 取回。
     * 索引为空时直接清空列表，不发请求。
     */
    fun loadDownloads(quiet: Boolean = false) = viewModelScope.launch {
        localCount = local.count
        localDir = local.dirs.firstOrNull().orEmpty()
        val ids = local.ids().toList()
        if (ids.isEmpty()) {
            downloadSongs.clear()
            return@launch
        }
        // quiet = true 走静默：不置全局 pageLoading。用户 m00932 第②点报的
        // 「所有界面都在加载」就是这里引起的 —— 本地放不出来回落到在线时，
        // LocalSource.onFallback 会调本函数重画「已下载音乐」，如果它顺手把
        // 全局骨架屏打开、而这次网络请求又慢/失败，用户看到的就是每个页面
        // 都在转圈。回退只是个后台刷新，不该动整屏。
        page(quiet) {
            // songDetail 一次别塞太多 id：索引可能有几千首，分批取。
            val out = ArrayList<Song>()
            ids.chunked(200).forEach { out.addAll(Endpoints.songDetail(it)) }
            // 按下载时间从新到旧（m00001 第④点）。没有时间记录的（老版本下好的、
            // 从未登记过的）排在最后，内部仍按服务端顺序，不会塌成随机。
            val times = downloadTimes()
            val sorted = out.sortedWith(compareByDescending<Song> { times[it.id] ?: 0L })
            withContext(Dispatchers.Main) {
                downloadSongs.clear(); downloadSongs.addAll(sorted)
            }
        }
    }

    // ------------------------------------------------------------ 下载时间
    private fun timeMap(): org.json.JSONObject = try {
        org.json.JSONObject(prefs.downloadTimes)
    } catch (e: Exception) {
        org.json.JSONObject()
    }

    /** songId → 落盘时间（毫秒）。 */
    private fun downloadTimes(): Map<Long, Long> {
        val o = timeMap()
        val out = HashMap<Long, Long>(o.length())
        for (k in o.keys()) {
            val id = k.toLongOrNull() ?: continue
            val v = o.optLong(k, 0L)
            if (v > 0) out[id] = v
        }
        return out
    }

    /** 记一首歌的落盘时间。同一首重新下会覆盖成新时间（「最新的排最前」）。 */
    private fun noteDownloadTime(id: Long) {
        if (id <= 0) return
        try {
            val o = timeMap()
            o.put(id.toString(), System.currentTimeMillis())
            prefs.downloadTimes = o.toString()
        } catch (e: Exception) {
            // 记不住时间最多排不进去，不该影响下载本身
        }
    }

    /**
     * 用户用 SAF 选了一个目录之后调用。
     *
     * 第⑧点：原来字典只有「我喜欢的音乐 + 播放记录」，用户在搜索页/每日推荐/歌单里
     * 看到的歌**根本不在字典里** —— 文件名一字不差也匹配不上，所以他觉得扫描是坏的。
     * 现在字典改成「当前进程见过的所有歌」：喜欢的 + 播放记录 + 每日推荐 + 推荐的歌单
     * 内容 + 搜索结果 + 正在看的歌单/专辑/歌手/用户页 + 最近播放。宁可多比对几千个 key
     * （本地几十毫秒的事），也不能让用户明明有文件却说没找到。
     */
    fun scanLocal(treeUri: android.net.Uri) = viewModelScope.launch {
        prefs.localTreeUri = treeUri.toString()
        val dict = knownSongs()
        // 不套 page()：骨架屏会把整页盖住，进度浮层就看不见了（m00001 第②点）。
        scanProgress = ScanProgress(title = "正在扫描本地文件", file = "正在准备…")
        val r = try {
            local.scan(treeUri, dict) { tick -> publishScan(tick) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            toast = "扫描失败：${e.message ?: e.javaClass.simpleName}"
            null
        } finally {
            scanProgress = null
        }
        if (r == null) return@launch
        toast = if (r.matched == 0) {
            "扫了 ${r.scanned} 个文件，没有匹配上；字典里只有 ${dict.size} 首，先把相关的歌单/搜索页打开一次再扫描"
        } else {
            "扫了 ${r.scanned} 个文件，匹配上 ${r.matched} 首"
        }
        loadDownloads()
    }

    /**
     * 把一次扫描进度落到界面状态。
     *
     * [LocalIndex.scan] 的进度回调在 IO 线程，而 `scanProgress` 是 Compose 状态 ——
     * 跨线程直接赋值会让快照系统收到乱序写入（进度条偶尔往回跳）。这里投到主线程，
     * 并且**只接受前进**的进度：文件系统返回顺序不保证，但进度条倒退一定是错的。
     */
    private fun publishScan(tick: LocalIndex.ScanTick) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val cur = scanProgress ?: return@launch
            val pct = tick.percent.coerceIn(cur.percent, 100)
            scanProgress = cur.copy(percent = pct, file = tick.file, detail = tick.detail)
        }
    }

    /** 这个进程里见过的所有歌（按 id 去重）。给本地扫描当匹配字典用。 */
    private fun knownSongs(): List<Song> {
        val seen = LinkedHashMap<Long, Song>()
        fun add(list: List<Song>) {
            for (s in list) if (s.name.isNotBlank()) seen.putIfAbsent(s.id, s)
        }
        add(likedSongs); add(dailySongs); add(searchSongs)
        add(playlistSongs); add(albumSongs); add(artistSongs); add(userSongs)
        add(recent.map { it }); add(downloadSongs)
        return seen.values.toList()
    }

    fun forgetLocal() {
        local.forget()
        downloadSongs.clear()
        localCount = 0
        localDir = ""
        // 索引清空之后「下载时间」也没有意义了，一起清掉 —— 否则下次下载同一首，
        // 页面会拿一条几年前的时间去排序（m00001 第④点）。
        runCatching { prefs.downloadTimes = "{}" }
        toast = "已清空本地索引"
    }

    // ------------------------------------------------------------ 下载
    /**
     * 下载一首歌（第②点）。
     *
     * 已经在本地的（索引里有、文件也在）直接提示，不再下一次 —— 用户点了下载
     * 却发现「又在下一遍」是不能接受的；但索引里登记着、文件其实没了的那种
     * （用户手动删了）要放行，否则那一首永远下不回来。
     *
     * [force] 是给菜单里那行「重新下载」用的：它在 `local.has()` 为真时才出现，
     * 结果点下去却被这里拦成「已经在本地了」—— 菜单说一套、动作做另一套。
     * 传 `true` 就先把索引里那条摘掉再排队，真的重下一份。
     */
    fun download(song: Song, force: Boolean = false) {
        val uri = local.uriOf(song.id)
        if (uri != null && local.exists(uri) && !force) {
            toast = "「${song.name}」已经在本地了"
            return
        }
        if (uri != null) local.remove(song.id)
        LocalSource.reset(song.id)
        downloader.enqueue(song, downloadQuality)
        toast = if (song.fee == 1) "已加入下载（VIP 音质）" else "已加入下载"
    }

    fun downloadMany(songs: List<Song>) {
        if (songs.isEmpty()) return
        var n = 0
        var skipped = 0
        for (s in songs) {
            val uri = local.uriOf(s.id)
            if (uri != null && local.exists(uri)) { skipped++; continue }
            if (uri != null) local.remove(s.id)
            downloader.enqueue(s, downloadQuality)
            n++
        }
        // 用户 m05942 第①点：已存在的歌不重复下，并且把「去哪儿补下」说清楚。
        toast = when {
            skipped > 0 ->
                "有 $skipped 首歌在歌单中已存在，若仍要下载，请点击顶部提示的文件夹按钮到已下载音乐中右键点击歌曲下载无损"
            n == 0 -> "这些歌都已经在本地了"
            else -> "已加入 $n 首到下载队列"
        }
        if (n > 0) watchNoCopyright(songs)
    }

    /**
     * 盯一批刚入队的歌，等它们全部落地后统计「没版权 / 只有试听」的条数
     * （用户 m05942 第④点）。
     *
     * 只在整批都不再排队/下载时才说话：用户自己按的暂停不算「下不了」。
     */
    private fun watchNoCopyright(songs: List<Song>) {
        val want = songs.map { it.id }.toSet()
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 30 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(1500)
                val mine = downloader.tasks.value.filter { it.songId in want }
                if (mine.isEmpty()) return@launch
                if (mine.any { it.state == Downloader.State.QUEUED || it.state == Downloader.State.RUNNING }) continue
                val bad = mine.count {
                    it.state == Downloader.State.ERROR &&
                        Regex("无版权|下架|试听|会员|付费").containsMatchIn(it.error)
                }
                if (bad > 0) toast = "$bad 首因无版权无法下载"
                return@launch
            }
        }
    }

    // ------------------------------------------------------------ 云盘去重（m05942 ②）
    /** 大小写/空白/常见标点差异不算不同：`^^` 这类符号要保留，不能被抹成空串。 */
    private fun cloudNorm(s: String): String =
        s.lowercase().replace(Regex("\\s+"), "")
            .replace(Regex("[._\\-–—~～!！?？@#$%^&*()+=\\[\\]{};:'\",<>/\\\\|`]"), "")

    /** `歌手 - 歌名` 拆开；拆不出来就当整串是歌名。括号后缀不剥（Live 版算另一首）。 */
    private fun splitCloudName(base: String): Pair<String, String> {
        val s = base.trim()
        val i = s.indexOf(" - ")
        return if (i > 0) s.substring(0, i).trim() to s.substring(i + 3).trim() else "" to s
    }

    /** 云盘里是否已有这一首（标题 + 歌手都对上才算）。 */
    private fun cloudHasSong(baseNoExt: String): Boolean {
        val (artist, title) = splitCloudName(baseNoExt)
        val t = cloudNorm(title)
        if (t.isEmpty()) return false
        val a = cloudNorm(artist)
        return cloudSongs.any { s ->
            val ct = cloudNorm(s.name)
            val ca = cloudNorm(s.artists.joinToString(" ") { it.name })
            // ① 标题一致且歌手一致
            (ct == t && ca == a) ||
                // ② Android 传上去的条目，歌名就是文件名本身（没有拆歌手）
                (ct == t && a.isEmpty()) ||
                // ③ 文件名没拆出歌手时，按「云盘歌名 == 文件名」兜底
                (a.isEmpty() && ct == cloudNorm(baseNoExt))
        }
    }

    fun cancelDownload(key: String) = downloader.cancel(key)
    fun removeDownload(key: String) = downloader.remove(key)
    fun clearFinishedDownloads() = downloader.clearFinished()

    // 用户 m03577 第①点：点任务本身暂停/继续，顶部还有「全部暂停/全部开始」。
    fun pauseDownload(key: String) = downloader.pause(key)
    fun resumeDownload(key: String) = downloader.resume(key)
    fun pauseAllDownloads() = downloader.pauseAll()
    fun resumeAllDownloads() = downloader.resumeAll()
    fun clearAllDownloads() = downloader.clearAll()

    /** 正在下载/等待的数量，MiniPlayer 和设置页的角标用。 */
    val downloading: Int get() = downloads.count { it.active }

    private suspend fun resolveTree(): android.net.Uri? = null

    // ------------------------------------------------------------ 启动恢复
    /**
     * 把当前队列整个写进 prefs（用户 m03123 第②点）。
     *
     * 存**当前顺序**而不是「来源歌单 id」：队列可能被用户手动加过、删过、调过序，
     * 按歌单重放等于把那些改动全丢了。随机模式下顺序已经是洗过的，所以原始顺序
     * 另存一份，退出随机时才有东西可还原。
     *
     * 队列为空时**不清**已存的：`connect()` 之后、真正起播之前队列本来就是空的，
     * 那时候清掉，用户只是切个后台再回来就发现队列没了。
     */
    private fun persistQueue() {
        val songs = player.queueSongsSnapshot()
        if (songs.isEmpty()) return
        prefs.queue = songs.toJsonArray()
        prefs.queueOrigin = player.originalSnapshot().toJsonArray()
        prefs.queueIndex = player.queueIndex.coerceAtLeast(0)
        prefs.queueContextId = player.contextId
        prefs.queueContextName = player.contextName
        prefs.queueRepeat = player.repeatMode
    }

    /**
     * 冷启动时把上次没听完的那首放回播放条。
     *
     * **不自动播放** —— 打开应用就突然出声很讨厌，桌面版也是这个取舍。
     * 进度条要跟着走：桌面版第③点的 bug 就是「数据恢复了但没广播进度」，
     * 界面上表现为进度条停在 00:00。
     *
     * 用户 m03123 第②点：优先恢复**整个队列**；老版本只存了单曲
     * （[Prefs.queue] 为空但 [Prefs.lastPlayId] 有值），退回按单曲恢复，
     * 这样升级上来的用户也不会一打开就发现队列空了。
     */
    fun restoreLastPlay() = viewModelScope.launch {
        if (!prefs.rememberProgress) return@launch
        val pos = prefs.lastPlayPosition
        // 超过 3 天就不接了：隔了一周打开还想接着听的场景不存在。
        val fresh = System.currentTimeMillis() - prefs.lastPlayAt <= 3L * 24 * 3600 * 1000

        // 先试整队列。
        val queue = songsFromJsonArray(prefs.queue)
        if (queue.isNotEmpty() && fresh) {
            val origin = songsFromJsonArray(prefs.queueOrigin).ifEmpty { queue }
            withContext(Dispatchers.Main) {
                player.restoreQueue(
                    songs = queue,
                    index = prefs.queueIndex,
                    original = origin,
                    atMs = pos,
                    contextId = prefs.queueContextId,
                    contextName = prefs.queueContextName,
                    repeat = prefs.queueRepeat,
                )
                syncSessionUi()
            }
            return@launch
        }

        // 退回单曲恢复（老配置）。
        val id = prefs.lastPlayId
        if (id == 0L) return@launch
        if (!fresh) return@launch
        val song = runCatching { Endpoints.songDetail(listOf(id)).firstOrNull() }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main) {
            // autoPlay = false：摆好队列、seek 到上次的位置，但不自己出声。
            // 打开应用就突然放歌很讨厌，桌面版也是这个取舍。
            player.restore(song, pos)
        }
    }

    // ------------------------------------------------------------ 提示清理
    fun consumeToast() { toast = null }
    fun consumeError() { error = null }

    /**
     * 把异常翻译成人话。与桌面版 `api.js` 的 `explain()` 同一套口径。
     *
     * 用户 m07698 第②点：原来 `TransportException` 一律给「网络连接失败，请检查网络」，
     * 把 [Client] 里刚分好的「超时 / 域名解析不了 / 连接被拒 / 证书有问题」全盖掉了。
     * 现在网络异常直接用它自己的文案 —— 那句话本来就是给用户看的，
     * 再套一层只会让「超时」和「没网」长得一模一样，用户不知道该等还是该换网。
     */
    fun explain(e: Throwable): String = when {
        e is TransportException -> e.message?.takeIf { it.isNotBlank() } ?: "网络连接失败，请检查网络"
        e is com.netease.lite.api.ApiException && e.code == 301 -> "请先登录网易云账号"
        // 用户 m08537 第⑤点：8821 是网易云对该账号**密码登录**的风控限制
        // （同一个账号输错密码回的是 502「账号或密码错误」，只有密码正确才
        // 回 8821 —— 说明密码没错，是这条路被平台关了）。原样把服务端那句
        // 「请切换其他登录方式或升级新版本再试」丢给用户等于没说：应用里
        // 根本没有「升级新版本」这个动作。这里直接告诉他还能怎么进。
        e is com.netease.lite.api.ApiException && e.code == 8821 ->
            "网易云已限制这个账号用密码登录。请改用「短信验证码登录」，或在设置里粘贴 Cookie 登录"
        e is com.netease.lite.api.ApiException && e.code == -460 -> "操作过于频繁，被网易云风控拦截，请稍后再试"
        e is com.netease.lite.api.ApiException && e.code == -462 -> "需要先完成风控验证"
        e.message.isNullOrBlank() -> "操作失败，请稍后再试"
        else -> e.message!!
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

/** 一条下载记录。Android 侧不做下载队列，只记「哪些歌在本地」。 */
data class DownloadRecord(val songId: Long, val name: String, val path: String)

/**
 * 本地扫描进度（m00001 第②点）。
 *
 * [percent] 是 0~100 的整段进度；[file] 是「当前正在扫描 / 匹配的歌曲文件名」，
 * 界面把它显示在进度条**下方**；[detail] 是「已扫描 120 / 190 个文件」这类计数。
 */
data class ScanProgress(
    val title: String = "正在扫描本地文件",
    val percent: Int = 0,
    val file: String = "",
    val detail: String = "",
)

/** 路由。带参数的用 data class 而不是 map，编译器帮我们检查漏字段。 */
sealed interface Route {
    data object Startup : Route
    data object Daily : Route
    data object Recommend : Route
    data object Charts : Route
    data object Podcasts : Route
    data object Liked : Route
    data object Recent : Route
    data object Downloads : Route

    /**
     * 「正在下载」。
     *
     * 用户 m03577 第①点：从「已下载音乐」里拆出来单独一页。原来队列挂在「已下载」
     * 页顶部，下完之后那一行还在原地（只是变成「已完成」），跟下面的本机音乐列表
     * 混在一起，看着像「下完了却没进已下载」。拆开之后两边各管一件事：
     * 这里只有还在队列里的，下完就自动从这一页消失、出现在「已下载音乐」里。
     */
    data object Downloading : Route
    data object Settings : Route
    data object Search : Route

    /**
     * 音乐云盘（用户 m05071 第④点）。
     *
     * 侧栏里排在「播客」下面，和电脑端一致：云盘是「在线音乐」那一组里的第四个
     * 入口，不属于本机音乐。
     */
    data object Cloud : Route

    /**
     * 播放队列页。
     *
     * 原来迷你条上那颗队列图标接的是「切换循环模式」（`vm.cycleRepeat()`）——
     * 画的是队列、干的却是循环，用户点它当然觉得「队列打不开」。循环模式在
     * 「正在播放」页底部有文字按钮，队列页在这里补上。
     */
    data object Queue : Route

    /**
     * 「正在播放」整页。
     *
     * 它不进 [AppViewModel.back] 之外的特殊处理：进页面之前那一页会被压栈，
     * 所以按返回（或页面上的收起箭头）就回到原处 —— 与「从哪首歌点进来就回哪去」
     * 的预期一致。
     */
    data object NowPlaying : Route
    data class Playlist(val id: Long, val name: String = "") : Route
    data class Album(val id: Long, val name: String = "") : Route
    data class Artist(val id: Long, val name: String = "") : Route

    /**
     * 歌手的「全部歌曲」页。
     *
     * 桌面版是歌手页里多一个页签，Android 侧单独开一页：手机上歌手页的
     * 热门 50 首 + 专辑墙已经很长了，再插一个「全部歌曲」页签要来回滑。
     * 从歌手页热门歌曲最下方那一栏点进来。
     */
    data class ArtistAll(val id: Long, val name: String = "") : Route
    data class User(val id: Long, val name: String = "") : Route

    /** 用户的「关注 / 粉丝」列表页。[kind] = "follows" 或 "followeds"。 */
    data class UserList(val id: Long, val kind: String, val name: String = "") : Route

    /** 播客电台的节目列表页。 */
    data class Podcast(val id: Long, val name: String = "") : Route

    /**
     * 评论页。
     *
     * [type] 用接口自己的词：song / playlist / album / dj / mv；[id] 是资源 id。
     * 桌面版评论挂在歌单页与歌曲右键菜单两处，Android 侧同样这两个入口。
     */
    data class Comments(val type: String, val id: Long, val name: String = "") : Route

    /** 听歌等级详情页（桌面版 `views.userlevel`）。 */
    data class UserLevel(val id: Long, val name: String = "") : Route
}
