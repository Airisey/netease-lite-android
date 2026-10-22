package com.netease.lite.data

import android.content.Context
import androidx.core.content.edit

/**
 * 设置项。对应桌面版 `renderer\js\settings.js` 的 `DEFAULTS` + `data\config.json`。
 *
 * Android 侧用 SharedPreferences 而不是 JSON 文件：设置是「小而频繁改」的数据，
 * 系统已经提供了带原子写和变更监听的容器，自己再写一份 JSON 落盘只会多一份
 * 崩溃窗口（写一半被杀 → 配置文件变半截 JSON，下次启动全部设置丢失）。
 *
 * 登录票据（Cookie 串）单独放一份，因为它比设置敏感得多，而且要能一键清掉。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("netease-lite", Context.MODE_PRIVATE)

    /** 音质档。四档与桌面版一致（Hi-Res 已并入无损）。 */
    var quality: String
        get() = sp.getString(K_QUALITY, "exhigh") ?: "exhigh"
        set(v) = sp.edit { putString(K_QUALITY, v) }

    var skin: String
        get() = sp.getString(K_SKIN, "classic") ?: "classic"
        set(v) = sp.edit { putString(K_SKIN, v) }

    /**
     * 重启后接着上次的位置继续。**默认开**。
     *
     * 桌面版这个开关原来默认 `false`，用户遇到的表现就是「进度条永远从开头开始」，
     * 排查半天才发现功能藏在设置里没打开。移动端更明显 —— 手机上的播放器
     * 本来就该接着放，所以这里默认就开着，不想接续的人可以自己关掉。
     */
    var rememberProgress: Boolean
        get() = sp.getBoolean(K_REMEMBER, true)
        set(v) = sp.edit { putBoolean(K_REMEMBER, v) }

    /** 上次播放的歌与位置。position 是毫秒。 */
    var lastPlayId: Long
        get() = sp.getLong(K_LAST_ID, 0L)
        set(v) = sp.edit { putLong(K_LAST_ID, v) }

    var lastPlayPosition: Long
        get() = sp.getLong(K_LAST_POS, 0L)
        set(v) = sp.edit { putLong(K_LAST_POS, v) }

    var lastPlayAt: Long
        get() = sp.getLong(K_LAST_AT, 0L)
        set(v) = sp.edit { putLong(K_LAST_AT, v) }

    /**
     * 播放页显示歌词还是封面大图。手机上竖屏空间紧张，默认歌词。
     */
    var showLyrics: Boolean
        get() = sp.getBoolean(K_LYRICS, true)
        set(v) = sp.edit { putBoolean(K_LYRICS, v) }

    /** 本地音乐扫描到的目录（SAF 树 URI 字符串）。 */
    var localTreeUri: String
        get() = sp.getString(K_LOCAL_TREE, "") ?: ""
        set(v) = sp.edit { putString(K_LOCAL_TREE, v) }

    /** 本地索引：songId -> content URI。用 JSON 存，条目量不大（几百首）。 */
    var localIndex: String
        get() = sp.getString(K_LOCAL_INDEX, "{}") ?: "{}"
        set(v) = sp.edit { putString(K_LOCAL_INDEX, v) }

    /** 最近播放。存的是完整的歌曲 JSON 数组，<= 200 条，理由见 AppViewModel。 */
    var recent: String
        get() = sp.getString(K_RECENT, "[]") ?: "[]"
        set(v) = sp.edit { putString(K_RECENT, v) }

    /**
     * 播放队列（用户 m03123 第②点：关掉应用不能把队列清掉）。
     *
     * 存的是**当前队列顺序**的完整歌曲 JSON 数组，不是「队列来源的歌单 id」——
     * 队列可能被用户手动加过歌、删过歌、调过序，重放歌单等于把那些改动全丢了。
     * 随机模式下这个顺序已经是洗过的，所以另外还要存一份 [queueOrigin]。
     */
    var queue: String
        get() = sp.getString(K_QUEUE, "[]") ?: "[]"
        set(v) = sp.edit { putString(K_QUEUE, v) }

    /** 队列的原始顺序，退出随机时用它还原。非随机模式下与 [queue] 相同。 */
    var queueOrigin: String
        get() = sp.getString(K_QUEUE_ORIGIN, "[]") ?: "[]"
        set(v) = sp.edit { putString(K_QUEUE_ORIGIN, v) }

    /** 队列里正在播的是第几首。 */
    var queueIndex: Int
        get() = sp.getInt(K_QUEUE_INDEX, 0)
        set(v) = sp.edit { putInt(K_QUEUE_INDEX, v) }

    /** 队列来源（歌单/专辑/歌手 id 与名字），恢复后「查看来源」还能用。 */
    var queueContextId: Long
        get() = sp.getLong(K_QUEUE_CTX_ID, 0L)
        set(v) = sp.edit { putLong(K_QUEUE_CTX_ID, v) }

    var queueContextName: String
        get() = sp.getString(K_QUEUE_CTX_NAME, "") ?: ""
        set(v) = sp.edit { putString(K_QUEUE_CTX_NAME, v) }

    /**
     * 队列当时的循环模式。**必须跟着队列一起存**：随机模式的队列顺序是洗过的，
     * 只恢复顺序不恢复模式的话，队列页显示的和迷你条上那颗图标就对不上了。
     *
     * 缺省必须是三档之一（用户 m00001 第④点）。这里原先是 `"list"` —— 第四档
     * 「列表循环」之所以还能在界面上冒出来，就是因为全新安装读到的就是这个值。
     * 老存档里的 `"list"` 不在这里折，交给 [com.netease.lite.play.Playback.normalize]
     * 在恢复队列那一步统一处理（Prefs 不依赖 play 包，保持单向）。
     */
    var queueRepeat: String
        get() = sp.getString(K_QUEUE_REPEAT, "order") ?: "order"
        set(v) = sp.edit { putString(K_QUEUE_REPEAT, v) }

    /** 登录 Cookie 串。 */
    var cookie: String
        get() = sp.getString(K_COOKIE, "") ?: ""
        set(v) = sp.edit { putString(K_COOKIE, v) }

    /**
     * 侧栏歌单缩略图的缓存：`id\u0002cover\u0001id\u0002cover…`。
     *
     * 用分隔符拼而不是 JSON：只有两列、量很小（几十条），引 JSON 库反而多一份
     * 要维护的序列化；分隔符取的是控制字符，歌单 id 是数字、封面是 URL，
     * 都不可能撞上。
     */
    var sideCovers: String
        get() = sp.getString(K_SIDE_COVERS, "") ?: ""
        set(v) = sp.edit { putString(K_SIDE_COVERS, v) }

    /**
     * 界面动效：页面切换的过渡、封面淡入。
     *
     * 桌面版设置页有同一个开关（`settings.js` 的 `animations`），关掉时它会给
     * `body` 挂 `no-anim`。手机上开关的意义更实在 —— 低端机每换一页都做一次
     * 滑入会明显掉帧。
     */
    var animations: Boolean
        get() = sp.getBoolean(K_ANIM, true)
        set(v) = sp.edit { putBoolean(K_ANIM, v) }

    /** 侧栏歌单显示封面（第①点）。关掉就退回统一的唱片图标。 */
    var sideCover: Boolean
        get() = sp.getBoolean(K_SIDE_COVER, true)
        set(v) = sp.edit { putBoolean(K_SIDE_COVER, v) }

    /** 歌曲列表显示封面。 */
    var listCover: Boolean
        get() = sp.getBoolean(K_LIST_COVER, true)
        set(v) = sp.edit { putBoolean(K_LIST_COVER, v) }

    /** 启动时自动签到。 */
    var autoCheckin: Boolean
        get() = sp.getBoolean(K_AUTO_CHECKIN, false)
        set(v) = sp.edit { putBoolean(K_AUTO_CHECKIN, v) }

    /** 播放失败自动跳到下一首。 */
    var autoSkip: Boolean
        get() = sp.getBoolean(K_AUTO_SKIP, true)
        set(v) = sp.edit { putBoolean(K_AUTO_SKIP, v) }

    /** 启动时打开的页面。空串 = 上次离开的那一页。 */
    var startPage: String
        get() = sp.getString(K_START_PAGE, "daily") ?: "daily"
        set(v) = sp.edit { putString(K_START_PAGE, v) }

    /** 缓存上限（MB）。0 表示不限制。 */
    var cacheMax: Int
        get() = sp.getInt(K_CACHE_MAX, 1024)
        set(v) = sp.edit { putInt(K_CACHE_MAX, v) }

    /**
     * 默认下载音质。与播放音质分开：播放要快、下载要无损，是两种需求。
     *
     * 默认「极高」而不是「无损」，与桌面版同口径。无损单曲 40MB 量级，
     * 手机上一个不注意就是几百 MB 流量；真想要无损的人在设置里改一次就够了
     * （这一档的选项本身就在，不是被砍掉）。
     */
    var downloadQuality: String
        get() = sp.getString(K_DL_QUALITY, "exhigh") ?: "exhigh"
        set(v) = sp.edit { putString(K_DL_QUALITY, v) }

    /** 下载分类方式：none（平铺）/ artist（按歌手）/ album（按专辑）。 */
    var downloadOrganize: String
        get() = sp.getString(K_DL_ORGANIZE, "none") ?: "none"
        set(v) = sp.edit { putString(K_DL_ORGANIZE, v) }

    /** 下载文件命名模板。与桌面版同一套占位符。 */
    var downloadTemplate: String
        get() = sp.getString(K_DL_TEMPLATE, DEFAULT_DL_TEMPLATE) ?: DEFAULT_DL_TEMPLATE
        set(v) = sp.edit { putString(K_DL_TEMPLATE, v) }

    /**
     * 下载基目录（用户 m48583 第①点：要能完全自定义）。
     *
     * 存的是相对内部存储根的路径，例如 `Music/网易云音乐 Lite` 或
     * `Music/我的歌/2024`。首段必须落在 `Downloader.PUBLIC_ROOTS` 里，
     * 否则 `MediaStore.Audio` 会直接拒绝写入 —— 规范化在 `Downloader.parseBase()`
     * 里做。
     *
     * **读的时候也规范化一遍**，不只是写的时候：老版本（以及任何手改过
     * SharedPreferences 的情况）可能存着 `Download/xxx` 这种对音频表非法的路径，
     * 只做写时校验的话，这类值会一直原样读出来，症状是「设置里显示得好好的，
     * 下载却一首也下不来」。getter 里过一道，等于给存量配置做了一次静默迁移。
     */
    var downloadDir: String
        get() = Downloader.normalizeBase(
            sp.getString(K_DL_DIR, Downloader.DEFAULT_BASE_DIR) ?: Downloader.DEFAULT_BASE_DIR,
        )
        set(v) = sp.edit { putString(K_DL_DIR, Downloader.normalizeBase(v)) }

    /**
     * 用户用 SAF 选的下载目录树（`content://…`），空串表示没选。
     *
     * 非空时**优先于** [downloadDir]：那是真正「任意位置」的一条路，不受
     * MediaStore 那几棵标准子树的限制。
     */
    var downloadTree: String
        get() = sp.getString(K_DL_TREE, "") ?: ""
        set(v) = sp.edit { putString(K_DL_TREE, v) }

    /**
     * 侧栏里被隐藏的条目，逗号分隔。
     *
     * 存「隐藏了哪些」而不是「显示了哪些」：新增条目默认可见，老配置不用迁移。
     * 键与桌面版 `settings.js` 的 `SIDE_ITEMS` 同一套。
     */
    var sideHidden: String
        get() = sp.getString(K_SIDE_HIDDEN, "") ?: ""
        set(v) = sp.edit { putString(K_SIDE_HIDDEN, v) }

    /**
     * 侧栏里被**折叠**起来的歌单分组（用户 m08537 第③点）。
     *
     * 与 [sideHidden] 分开存：那个是「整组不显示」（设置页里勾掉），这个是
     * 「组标题还在、内容收起」。存「折叠了哪些」，新分组默认展开 —— 与
     * [sideHidden] 同一个理由，老配置不用迁移。
     */
    var sideFolded: String
        get() = sp.getString(K_SIDE_FOLDED, "") ?: ""
        set(v) = sp.edit { putString(K_SIDE_FOLDED, v) }

    /** 上次离开的顶层页面标签，供「启动时打开上次离开的页面」用。 */
    var lastRoute: String
        get() = sp.getString(K_LAST_ROUTE, "daily") ?: "daily"
        set(v) = sp.edit { putString(K_LAST_ROUTE, v) }

    /**
     * 断点续传账本（m00001 第①点）。
     *
     * 键是「任务 key + 目标文件名」，值是 JSON：已写音频长度、标签长度、源流里
     * 被跳过的 ID3 长度、总长、最后更新时间。暂停 / 进程被杀之后靠它算出
     * `Range: bytes=` 的起点，接着写同一个文件。
     *
     * 为什么不能只看「文件有多大」：MP3 前面压着我们自己写的 ID3，源流开头
     * 还可能有一段 CDN 写的 ID3 —— 两者都不属于音频载荷，直接拿文件长度当
     * 偏移会从半个帧中间开始接，拼出来的文件能播但会「咔」一下甚至整段错位。
     */
    var downloadParts: String
        get() = sp.getString(K_DL_PARTS, "{}") ?: "{}"
        set(v) = sp.edit { putString(K_DL_PARTS, v) }

    /** 「已下载音乐」每首歌的落盘时间（songId → 毫秒），用来按下载时间倒序（m00001 第④点）。 */
    var downloadTimes: String
        get() = sp.getString(K_DL_TIMES, "{}") ?: "{}"
        set(v) = sp.edit { putString(K_DL_TIMES, v) }

    /** 当前登录的用户 id（Cookie 里不带，登录成功后自己记）。 */
    var uid: Long
        get() = sp.getLong(K_UID, 0L)
        set(v) = sp.edit { putLong(K_UID, v) }

    /** 退出登录：票据和 uid 一起清，设置保留。 */
    fun clearAccount() {
        sp.edit {
            remove(K_COOKIE)
            remove(K_UID)
        }
    }

    companion object {
        private const val K_QUALITY = "quality"
        private const val K_SKIN = "skin"
        private const val K_REMEMBER = "remember_progress"
        private const val K_LAST_ID = "last_play_id"
        private const val K_LAST_POS = "last_play_position"
        private const val K_LAST_AT = "last_play_at"
        private const val K_LYRICS = "show_lyrics"
        private const val K_LOCAL_TREE = "local_tree"
        private const val K_LOCAL_INDEX = "local_index"
        private const val K_RECENT = "recent"
        private const val K_QUEUE = "queue"
        private const val K_QUEUE_ORIGIN = "queue_origin"
        private const val K_QUEUE_INDEX = "queue_index"
        private const val K_QUEUE_CTX_ID = "queue_ctx_id"
        private const val K_QUEUE_CTX_NAME = "queue_ctx_name"
        private const val K_QUEUE_REPEAT = "queue_repeat"
        private const val K_COOKIE = "cookie"
        private const val K_UID = "uid"
        private const val K_SIDE_COVERS = "side_covers"
        private const val K_ANIM = "animations"
        private const val K_SIDE_COVER = "side_cover"
        private const val K_LIST_COVER = "list_cover"
        private const val K_AUTO_CHECKIN = "auto_checkin"
        private const val K_AUTO_SKIP = "auto_skip"
        private const val K_START_PAGE = "start_page"
        private const val K_CACHE_MAX = "cache_max"
        private const val K_DL_QUALITY = "dl_quality"
        private const val K_DL_ORGANIZE = "dl_organize"
        private const val K_DL_TEMPLATE = "dl_template"
        private const val K_DL_DIR = "dl_dir"
        private const val K_DL_TREE = "dl_tree"
        private const val K_SIDE_HIDDEN = "side_hidden"
        private const val K_SIDE_FOLDED = "side_folded"
        private const val K_LAST_ROUTE = "last_route"
        private const val K_DL_PARTS = "dl_parts"
        private const val K_DL_TIMES = "dl_times"

        /** 侧栏可开关的条目。与桌面版 `settings.js` 的 `SIDE_ITEMS` 一一对应。 */
        val SIDE_ITEMS = linkedMapOf(
            "daily" to "每日推荐",
            "radio" to "推荐歌单",
            "charts" to "榜单",
            "podcast" to "播客",
            "liked" to "我喜欢的音乐",
            "recent" to "最近播放",
            "downloads" to "已下载音乐",
            "own" to "创建的歌单",
            "sub" to "收藏的歌单",
        )

        /**
         * 默认命名格式。
         *
         * 用户 m03577 第②/⑦点：从「{首歌手名} - {歌曲名}」改成「{歌手名} - {歌曲名}」。
         * 首歌手名会把合唱曲目的其余歌手整个丢掉，而文件名是用户事后在文件管理器里
         * 认歌的唯一线索。真值放在 [Downloader.DEFAULT_TEMPLATE]，这里只是转一手，
         * 免得两处字面量漂移。桌面版 `electron/download.js` 与
         * `renderer/js/settings.js` 里的同一常量必须一起改。
         */
        const val DEFAULT_DL_TEMPLATE = Downloader.DEFAULT_TEMPLATE

        /** 启动页候选。与桌面版 `settings.js` 的 `START_PAGES` 同一组。 */
        val START_PAGES = linkedMapOf(
            "radio" to "推荐歌单",
            "daily" to "每日推荐",
            "charts" to "榜单",
            "podcast" to "播客",
            "liked" to "我喜欢的音乐",
            "recent" to "最近播放",
            "downloads" to "已下载音乐",
            "last" to "上次离开的页面",
        )

        /** 音质档与显示名。与桌面版 `QUALITY_LABEL` 一致。 */
        val QUALITY_LABEL = linkedMapOf(
            "standard" to "标准",
            "higher" to "较高",
            "exhigh" to "极高",
            "lossless" to "无损",
        )
    }
}

