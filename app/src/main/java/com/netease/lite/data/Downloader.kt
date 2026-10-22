package com.netease.lite.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.netease.lite.api.Client
import com.netease.lite.api.Endpoints
import com.netease.lite.model.Artist
import com.netease.lite.model.Song
import com.netease.lite.model.SongUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载器。
 *
 * 与桌面版（`electron\download.js`）的取舍差异，先说清楚为什么：
 *
 *   - **不让用户挑下载目录**。桌面版可以指到任意路径；Android 要往任意目录写，
 *     要么申请 MANAGE_EXTERNAL_STORAGE（应用商店直接拒），要么每次走 SAF 弹目录
 *     选择。所以统一落到公共音乐目录 `Music/网易云音乐 Lite/`：API 29+ 走
 *     MediaStore（系统音乐库能直接扫到），更低版本自己建文件。用户拿数据线或
 *     任何文件管理器都看得见。
 *   - **不给用户挑目录之外的另一件事：断点续传**（m00001 第①点改了口径）。
 *     以前这里是「不做断点续传」，理由是「CDN 偶尔回半截响应，续传更容易拼出
 *     打不开的文件」。现在改成：先落到应用私有目录的 `.part`，暂停/断网/进程
 *     被杀之后用 `Range: bytes=` 从断点接着写**同一个**文件，下完才复制进
 *     公共音乐目录。CDN 给了半截响应时我们看 `Content-Range` / 206 才敢追加，
 *     回 200 就直接截断重来 —— 拼坏文件的风险由这条判定挡住，而用户看到的
 *     永远是「一个文件」。
 *   - **队列串行、只有一条**。省电、省并发，进度条之间也不会互抢带宽。桌面版的
 *     并发数可配，这里不暴露。
 *
 * 容器由档位决定：前三档 MP3、无损 FLAC。`encodeType` 会被服务端忽略，所以
 * 「要 MP3」这个请求本身就没意义 —— 与桌面版 m36467 的结论一致。
 */
class Downloader(
    private val context: Context,
    private val scope: CoroutineScope,
    /** 断点账本（m00001 第①点）。默认落 SharedPreferences，见 [Prefs.downloadParts]。 */
    private val prefs: Prefs = Prefs(context),
    /** 每次落盘时现取：用户在设置页改了模板/分类，正在排队的那些也该照新规则走。 */
    private val options: () -> Options = { Options() },
) {

    /**
     * 落盘规则。字段与桌面版 `download.js` 的 `config.download` 一一对应。
     *
     * @param organize `none` 平铺 / `artist` 按歌手 / `album` 按专辑
     * @param template 文件名模板，占位符见 [applyTemplate]
     * @param baseDir 下载基目录（相对内部存储根的路径，任意层级，例如
     *   `Music/网易云/我的歌`）。首段只能落在 [PUBLIC_ROOTS] 那六个里 ——
     *   音频表的 `RELATIVE_PATH` 只认它们，系统里的 `Download/`、`Documents/`
     *   反而会被顶回来（详见 [PUBLIC_ROOTS] 的注释）。
     * @param treeUri 用户用 SAF 选中的目录树（`content://…`）。**非空时优先于
     *   [baseDir]** —— 那是真正「完全自定义」的一条路：任何位置都能写，
     *   不受 `MediaStore.RELATIVE_PATH` 那六个首段的限制。
     */
    data class Options(
        val organize: String = "none",
        // 用户 m03577 第②点：默认命名格式改成「{歌手名} - {歌曲名}」。
        // 原来是「{首歌手名} - {歌曲名}」—— 首歌手名会把合唱曲目的其余歌手丢掉，
        // 而文件名是用户事后在文件管理器里认歌的唯一线索。
        val template: String = DEFAULT_TEMPLATE,
        val baseDir: String = DEFAULT_BASE_DIR,
        val treeUri: String = "",
    )

    data class Task(
        val key: String,
        val songId: Long,
        val name: String,
        val artistText: String,
        val cover: String,
        val quality: String,
        /** 专辑名，按专辑分类时用作文件夹名。 */
        val album: String = "",
        val state: State = State.QUEUED,
        val received: Long = 0L,
        val total: Long = 0L,
        /** 人能读的落盘位置，例如 `Music/网易云音乐 Lite/歌手 - 歌名.mp3`。 */
        val file: String = "",
        /** 真正的地址：API 29+ 是 content URI，更低版本是 file:// 绝对路径。 */
        val uri: String = "",
        val error: String = "",
        val vip: Boolean = false,
        /**
         * 写 ID3 用的曲目信息（用户 m07048 第④点）。
         *
         * 官方客户端下的文件带完整标签（封面、专辑、歌名、歌手、专辑歌手、
         * 碟号、音轨号），我们原来只有一个 `TSSE` 编码器帧 —— 拷进别的播放器
         * 就成了一堆「未知专辑 / 未知歌手」。这里把渲染标签需要的东西一起带上，
         * 落盘后由 [writeTags] 补写进文件头。
         */
        val trackNo: Int = 0,
        val discNo: Int = 0,
        /**
         * 碟名原文（`disc1 万籁拂旧尘`），写 `TPOS` 用（用户 m15041）。
         *
         * 多碟合集的 `cd` 字段是「碟号 + 碟名」，官方客户端的 `TPOS` 帧对这种情况
         * **原文照抄**，不是只写个碟号。单碟专辑这里为空，`Id3` 会退回补两位的碟号。
         */
        val discText: String = "",
        /**
         * `COMM` 帧要用的其余字段（用户 m11461 第①点）。
         *
         * 官方 app 判断「这个文件是不是我自己下的」只看 `COMM` 里那段加密 JSON 的
         * `musicId`；对不上就退化成按「歌手-歌名」模糊匹配，于是我们的文件在它的
         * 本地音乐里带着感叹号、点开显示「原 / 新」两串对不上。
         *
         * 这里带的都是那段 JSON 里除 id 之外的字段：歌手 id 数组、专辑 id、MV id、
         * 别名/译名、时长、码率。缺哪个都不致命（`musicId` 才是关键），但能填就填 ——
         * 用户是拿两份文件逐字节比的。
         */
        val artists: List<Artist> = emptyList(),
        val albumId: Long = 0L,
        val mvid: Long = 0L,
        val alias: List<String> = emptyList(),
        val duration: Long = 0L,
        /** 实际码率，取流响应里的 `br`（320002 这种，带帧头开销，官方也写这个值）。 */
        val br: Int = 0,
    ) {
        val percent: Int get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 100) else 0
        val active: Boolean get() = state == State.QUEUED || state == State.RUNNING || state == State.PAUSED
    }

    /**
     * 任务状态。
     *
     * [PAUSED] 是用户 m03577 第①点加的：原来只有「取消」，点一下任务就整条作废，
     * 想接着下只能重新入队（于是又从头下一遍）。暂停要能**原地续上**，所以它是
     * 一个独立状态，而不是「取消 + 记住进度」。
     */
    enum class State { QUEUED, RUNNING, PAUSED, DONE, CANCELED, ERROR }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    /** 一首歌真的落盘之后回调一次。ViewModel 用它把歌登记进本地索引。 */
    var onDone: ((Task) -> Unit)? = null

    private val jobs = ConcurrentHashMap<String, Job>()
    private val queue = Channel<Task>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (task in queue) runTask(task)
        }
    }

    /**
     * 入队。
     *
     * 同一首歌同一档位已经在队列里（等待/在下载/已完成）就不再排一次 —— 用户连点
     * 两下「下载」本来也不该得到两个文件；换档重下时则把同一首歌的旧记录顶掉，
     * 免得列表里躺着两条同名任务。
     */
    fun enqueue(song: Song, quality: String) {
        val key = "${song.id}@$quality"
        val cur = _tasks.value
        if (cur.any { it.key == key && it.state != State.CANCELED && it.state != State.ERROR }) return
        _tasks.value = cur.filterNot { it.songId == song.id } + Task(
            key = key,
            songId = song.id,
            name = song.name,
            artistText = song.artistText,
            cover = song.cover,
            quality = quality,
            album = song.albumName,
            vip = song.fee == 1,
            trackNo = song.trackNo,
            discNo = song.discNo,
            discText = song.discText,
            artists = song.artists,
            albumId = song.albumId,
            mvid = song.mvid,
            alias = song.alias,
            duration = song.duration,
        )
        queue.trySend(_tasks.value.last())
    }

    fun cancel(key: String) {
        jobs[key]?.cancel()
        update(key) { it.copy(state = State.CANCELED) }
        // 取消 = 不要这首歌了：把断点账本也清掉，`.part` 由 runTask 的 catch 删。
        clearPart(key)
    }

    /**
     * 暂停一首（用户 m03577 第①点：点任务本身暂停）。
     *
     * 正在下的立刻掐断，还在排队的直接标 PAUSED —— 两者对用户是同一个意思
     * 「先别下这首」，不该因为「运气好还没轮到」就点不动。
     */
    fun pause(key: String) {
        val t = _tasks.value.firstOrNull { it.key == key } ?: return
        if (!t.active || t.state == State.PAUSED) return
        jobs[key]?.cancel()
        update(key) { it.copy(state = State.PAUSED) }
    }

    /**
     * 继续一首（m00001 第①点改成真续传）。
     *
     * 重新丢回队列尾部，但**不再从头下**：`runTask` 会读到断点账本里记的
     * 已写长度，发一条 `Range: bytes=` 接着写同一个 `.part`，下完才复制到
     * 公共目录。所以暂停再继续得到的始终是一个文件，不会多出一份。
     *
     * 用户 m03593 第④点：失败的任务（ERROR）也走这里 —— 「文件不完整」那类错误
     * 的 `.part` 是特意留着的，重试就是接着下；原来这里只认 PAUSED，所以用户点
     * 失败的任务什么都不会发生。
     */
    fun resume(key: String) {
        val t = _tasks.value.firstOrNull { it.key == key } ?: return
        if (t.state != State.PAUSED && t.state != State.ERROR) return
        // 重试要把上一次的失败原因清掉，否则界面上会一直是那句旧错误。
        update(key) { it.copy(state = State.QUEUED, error = "") }
        queue.trySend(_tasks.value.first { it.key == key })
    }

    /** 全部暂停。正在下的那条会被掐断，队列里剩下的直接标暂停。 */
    fun pauseAll() {
        for (t in _tasks.value.filter { it.active }) pause(t.key)
    }

    /**
     * 全部继续：按列表顺序重新排队。
     *
     * 用户 m03593 第③点：「正在下载」页上，队列里的歌**全都失败**时也要给一个
     * 「全部开始」，点了把失败的歌全部重试（接着各自的断点下）。所以这里不只看
     * PAUSED，也带上 ERROR。
     */
    fun resumeAll() {
        for (t in _tasks.value.filter { it.state == State.PAUSED || it.state == State.ERROR }) resume(t.key)
    }

    fun clearFinished() {
        _tasks.update { list -> list.filter { it.active } }
    }

    /**
     * 清空整个队列（用户 m03577 第①点里「正在下载」页顶部那个「清空」）。
     *
     * 语义是「这些我都不下了」，所以进行中的任务会被取消、并从列表里拿掉；
     * 已完成的不动 —— 它们已经落在磁盘上，从列表里抹掉只会让「已下载音乐」看着
     * 少一首（索引还在，但用户会以为文件被删了）。
     */
    fun clearAll() {
        for (t in _tasks.value.filter { it.active }) jobs[t.key]?.cancel()
        // 已取消的也一起收掉（m07048 第③点）：「正在下载」页已经不显示它们了，
        // 留着只是占内存，下次点垃圾桶还是看不见任何变化。
        _tasks.update { list ->
            list.filterNot { it.active || it.state == State.CANCELED }
        }
    }

    fun remove(key: String) {
        jobs[key]?.cancel()
        _tasks.update { list -> list.filterNot { it.key == key } }
    }

    private fun update(key: String, f: (Task) -> Task) {
        _tasks.update { list -> list.map { if (it.key == key) f(it) else it } }
    }

    private fun state(key: String): State? = _tasks.value.firstOrNull { it.key == key }?.state

    private suspend fun runTask(task: Task) {
        // 入队后被立刻取消的（在还没轮到它的时候就点了 ×）不必再跑；
        // 还没轮到就被暂停的也一样 —— 继续时会被重新丢回队列。
        val cur = state(task.key)
        if (cur == State.CANCELED || cur == State.PAUSED) return
        update(task.key) { it.copy(state = State.RUNNING) }

        // 从用户选的档往回退：无损拿不到退极高，极高拿不到退较高……最后 128k。
        // 与桌面版 levelsFor() 同一套顺序，理由也一样：会员档对非会员回 url:null，
        // 而同一首歌在较低档往往仍然可播。
        val order = listOf("lossless", "exhigh", "higher", "standard")
        val at = order.indexOf(task.quality).let { if (it < 0) 1 else it }

        var stream: SongUrl? = null
        var lastMsg = ""
        for (lv in order.drop(at)) {
            val r = runCatching { Endpoints.resolveUrl(task.songId, lv) }.getOrNull()
            if (r == null) { lastMsg = "网络连接失败"; break }
            if (r.ok && (r.type == "mp3" || r.type == "flac")) { stream = r; break }
            // 私有容器（m4a / 加密档）不落盘：用户拿到的会是打不开的文件。
            lastMsg = when {
                r.failed -> r.message.ifEmpty { "取流失败" }
                // 取流成功但没有可下的容器，实际就是「这档没得下」。
                // 界面上原来显示的是「当前档位只给 null 容器，已跳过」——一句
                // 内部实现话，用户看不懂（用户 m03593 第⑤点）。这里和桌面版
                // `download.js` 的报错口径对齐：`fee == 1` 是会员/付费专享，
                // 其余就是没版权或下架。
                r.url == null || r.type == null ->
                    if (r.fee == 1) "需要会员或付费，无法下载" else "暂无版权或已下架"
                // `type` 已经由 Endpoints.textOrNull 归一化，不会再有字符串 "null"
                // 溜进来（用户 m04384 第②点：原来这里会印成「专属格式（.null）」）。
                else -> "只能取到专属格式（.${r.type ?: "未知"}），无法下载"
            }
            if (r.failed && r.code == -1) break
        }
        val s = stream
        val url = s?.url
        if (s == null || url == null) {
            update(task.key) { it.copy(state = State.ERROR, error = lastMsg.ifEmpty { "下载失败" }) }
            return
        }

        // 补音轨号 / 碟号。每日推荐、搜索、最近播放这些列表接口回的歌曲对象里
        // 没有 `no` / `cd`，只有 `/v3/song/detail` 有；缺了这两项标签里就没有
        // TRCK / TPOS，而官方客户端下的同名文件是带的（用户 m08537 第①点）。
        // 补不到就按未知处理、不写帧 —— 绝不因此让下载失败。
        var trackNo = task.trackNo
        var discNo = task.discNo
        var discText = task.discText
        if (trackNo <= 0 || discNo <= 0) {
            val d = runCatching { Endpoints.songDetail(listOf(task.songId)) }.getOrNull()?.firstOrNull()
            if (d != null) {
                if (trackNo <= 0) trackNo = d.trackNo
                if (discNo <= 0) discNo = d.discNo
                // 碟名只在详情接口里有（`cd = "disc1 万籁拂旧尘"`），见 Models.kt 的 discFields。
                if (discText.isEmpty()) discText = d.discText
            }
        }

        val ext = if (s.type == "flac") ".flac" else ".mp3"
        // 文件名模板与桌面版 `applyTemplate` 的占位符一致，见 companion 的 applyTemplate。
        val fileName = sanitize(
            applyTemplate(
                options().template,
                title = task.name,
                artist = task.artistText,
                firstArtist = task.artistText.substringBefore("/").trim(),
                album = task.album,
                quality = QUALITY_TAG[task.quality] ?: task.quality,
            ),
        ) + ext

        // 断点续传的落点：**应用私有目录**里的一份 .part（m00001 第①点）。
        //
        // 为什么不直接往 MediaStore / SAF 的目标文件里续写：
        //   - MediaStore 的输出流只有「截断重写」和「追加」两种模式，拿不到
        //     「现在写了多少」，而续传必须精确知道断点；
        //   - SAF 的 DocumentFile 没有随机写，不同 provider 的追加行为也不一致；
        //   - 半截文件留在公共音乐目录里会被系统媒体库扫到，用户在「网易云音乐
        //     Lite」文件夹里会看到一首打不开的歌 —— 这正是用户报的「出现两个文件」。
        // 私有目录里的 .part 对媒体库与第三方播放器都不可见，下完才一次性复制进
        // 目标位置，所以用户永远只会看到**一个**完整文件。
        val partFile = partFileFor(task, ext)
        val job = scope.launch(Dispatchers.IO) {
            // 这几项会在暂停时写进断点账本，供下次续传换算 Range 起点
            var tagSize = 0L
            var skip = 0L
            var received = 0L
            var total = 0L
            var fileNameForPart = fileName
            var lastSave = 0L
            // 非 null 表示这一步已经判死（HTTP 错、响应为空、416 不可信……）。
            // 有这个标记之后，下面的「收尾」一律不许跑 —— 见 m01537 第④点：
            // 原来出错只是 `return@use`，从 lambda 里退出来之后收尾代码照样执行，
            // 于是把半截 .part 当成品搬进公共目录，用户看到的就是「报错 + 多一个文件」。
            var stopReason: String? = null
            // 远端**源流**的总长度（含开头那段被丢掉的 CDN ID3），来自 206 的
            // Content-Range 或 416 的 `bytes */N`。它是唯一权威的长度：账本里的
            // `total` 可能是老版本写的、没减过 `skip`，拿它判「下全了没有」会永远
            // 差那几十字节（用户 m04384 第③点）。有它就以它为准：音频应得长度
            // = remoteTotal - skip。
            var remoteTotal: Long? = null
            // 断点被判定不可信（416 且 .part 里没有整首）：清干净后重新排一次队，
            // 下一轮从零开始下。只重排一次，不会原地打转。
            var restart = false
            try {
                // ---------------------------------------------------------- 断点
                val rec = readPart(task.key)
                var resume = false
                if (rec != null && rec.optString("name") == fileName && partFile.exists()) {
                    tagSize = rec.optLong("tag", 0L)
                    skip = rec.optLong("skip", 0L)
                    received = rec.optLong("received", 0L)
                    total = rec.optLong("total", 0L)
                    // 以磁盘上的真实长度为准（m02003 第④点）：账面值可能是上一次
                    // 尝试留下的、比落盘的多 —— `.part` 甚至可能已经被截成 0 字节
                    // （重来时先 `FileOutputStream(partFile, false)` 截断再写标签）。
                    // 旧代码只在 `onDisk > 0` 时才纠正，于是「空文件 + 账面大 received」
                    // 会被当成有效断点：Range 起点跑到远端之外（416），或者干脆把
                    // 空文件当成品搬走，用户看到的就是「跳过任务 + 一个 0B 文件」。
                    val onDisk = (partFile.length() - tagSize).coerceAtLeast(0L)
                    if (onDisk != received) received = onDisk
                    // 老账本里的 `total` 是**源流**长度（含开头那段要丢掉的 CDN ID3），
                    // 当年只有「从零下」一条路，没人管 skip。这种账本会让收尾判定
                    // 永远差 `skip` 个字节，而且重试也一样 —— 用户 m04384 第③点报的
                    // 「已下 12326400 / 12326445 字节」正是差那 45 字节的标签。
                    // 特征很明确：账面总长比盘上音频正好多出一个 skip。掰回来。
                    total = normalizeStoredTotal(skip, total, onDisk)
                    resume = received > 0
                }
                if (!resume) {
                    // 没有可续的断点：清掉残留，从零开始
                    runCatching { partFile.delete() }
                    clearPart(task.key)
                    tagSize = 0; skip = 0; received = 0; total = 0
                }
                if (received > 0) {
                    update(task.key) { it.copy(received = received, total = total) }
                }

                val reqBuilder = Request.Builder().url(url)
                    .header("User-Agent", com.netease.lite.api.Http.UA)
                // 远端偏移 = 源流里被我们跳过的 ID3 长度 + 已经写下的音频长度
                val startAt = if (resume) skip + received else 0L
                if (startAt > 0) reqBuilder.header("Range", "bytes=$startAt-")

                Client.http.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    val body = resp.body
                    if (code == 416) {
                        // 416 = 我们要的起点已经在远端长度之外。两种来路：
                        //   ① 暂停时其实已经把整首收完了（startAt 正好等于远端长度），
                        //      差最后一步「搬到公共目录」；
                        //   ② 远端换了资源、比断点短（少数 CDN 会这样）。
                        // 用 `Content-Range: bytes */N` 判：整首已经在 .part 里就收尾，
                        // 否则丢掉断点从零重来一次 —— 绝不把 416 甩到界面上。
                        val all = Regex("""bytes\s+\*/\s*(\d+)""", RegexOption.IGNORE_CASE)
                            .find(resp.header("Content-Range").orEmpty())
                            ?.groupValues?.get(1)?.toLongOrNull()
                        remoteTotal = all ?: remoteTotal
                        // `all` 是源流总长（含被丢掉的 CDN ID3），音频应得长度要减 skip。
                        // 这一句同时修掉老账本：那里存的 `total` 是没减过 skip 的源流长度，
                        // 只有它会让「其实已经下完」的任务报「文件不完整（已下 X / Y 字节）」
                        // 且重试永远一样（用户 m04384 第③点）。
                        if (all != null) total = (all - skip).coerceAtLeast(0L)
                        // `.part` 里必须真的有音频字节才算「整首已经在本地」——
                        // 空壳文件（0B）永远不该被当成成品（m02003 第④点）。
                        // 判据也用音频字节（不是 `tagSize + received` 那种混算）。
                        val audioOnDisk = (partFile.length() - tagSize).coerceAtLeast(0L)
                        val need = downloadNeedBytes(remoteTotal, skip, total)
                        // 长度完全未知时不认「整首已在本地」，按老口径清干净重下：
                        // 416 说明起点已经在远端之外，账本不可信才是常态。
                        val complete = need > 0 && downloadComplete(audioOnDisk, need)
                        if (complete) return@use
                        runCatching { partFile.delete() }
                        clearPart(task.key)
                        tagSize = 0; skip = 0; received = 0; total = 0
                        resume = false
                        update(task.key) { it.copy(received = 0, total = 0) }
                        restart = true
                        return@use
                    }
                    if (code != 200 && code != 206) {
                        update(task.key) { it.copy(state = State.ERROR, error = "HTTP $code") }
                        stopReason = "HTTP $code"
                        return@use
                    }
                    if (body == null) {
                        update(task.key) { it.copy(state = State.ERROR, error = "响应为空") }
                        stopReason = "响应为空"
                        return@use
                    }
                    // 服务端认 Range 才是真续传。回 200 说明它无视 Range，必须
                    // 截断重来 —— 否则会写出「前半首 + 整首」的文件，能播但乱序。
                    var append = false
                    if (resume && code == 206) {
                        val cr = resp.header("Content-Range").orEmpty()
                        val m = Regex("^bytes\\s+(\\d+)-", RegexOption.IGNORE_CASE).find(cr)
                        if (m == null || m.groupValues[1].toLongOrNull() == startAt) {
                            append = true
                            Regex("""/(\d+)\s*$""").find(cr)?.groupValues?.get(1)?.toLongOrNull()?.let { all ->
                                remoteTotal = all
                                total = (all - skip).coerceAtLeast(0)
                            }
                        }
                    }
                    if (resume && !append) {
                        runCatching { partFile.delete() }
                        clearPart(task.key)
                        tagSize = 0; skip = 0; received = 0
                        resume = false
                    }
                    val len = body.contentLength()

                    // ---------------------------------------------------- 标签
                    // 只有「从头下」才写标签；续传时标签早就在 .part 开头了。
                    // 标签必须落在文件最前面，所以只能一次成型（m07048 第④点）。
                    if (!resume) {
                        val tagBytes = if (Id3.isFlac(fileName)) {
                            null
                        } else {
                            runCatching {
                                val cover = fetchCover(task.cover)
                                Id3.build(
                                    title = task.name,
                                    artist = task.artistText,
                                    albumArtist = task.artistText,
                                    album = task.album,
                                    trackNo = trackNo,
                                    discNo = discNo,
                                    cover = cover,
                                    // COMM 帧：官方 app 靠这段认「这是我自己下的文件」。
                                    comm = runCatching { commOf(task, discNo, s.br)?.copy(discText = discText) }
                                        .getOrNull(),
                                    padding = 128,
                                )
                            }.getOrNull()
                        }
                        partFile.parentFile?.mkdirs()
                        java.io.FileOutputStream(partFile, false).use { out ->
                            if (tagBytes != null) out.write(tagBytes)
                        }
                        tagSize = if (tagBytes != null) tagBytes.size.toLong() else 0L
                        // 从零下：`Content-Length` 就是源流总长，先记下来当权威长度，
                        // 后面 `skipLeadingId3` 报出要跳多少时再把 total 换算成音频长度。
                        if (len > 0) {
                            total = len
                            remoteTotal = len
                        }
                    } else if (total <= 0) {
                        // 续传响应没有 Content-Range 总长时，用剩余长度 + 已写长度估
                        if (len > 0) total = received + len
                    }

                    // ---------------------------------------------------- 音频
                    var lastPush = 0L
                    java.io.FileOutputStream(partFile, true).use { out ->
                        // `total` 必须是**音频**长度：`Content-Length` 里还算上了开头
                        // 那段被我们丢掉的 ID3，不减掉的话进度条永远差一截走不到 100%，
                        // 暂停在末尾时 `skip + received` 正好顶到远端长度，续传就换来
                        // 一个 HTTP 416（m01537 第④点）。
                        skipLeadingId3(
                            body.byteStream(),
                            onSkip = { skipped ->
                                skip = skipped
                                if (!resume && len > 0) total = (len - skipped).coerceAtLeast(0L)
                            },
                        ).use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                // 暂停/取消时 `jobs[key].cancel()` 打断不了阻塞中的
                                // `input.read()`（OkHttp 的 socket 读是阻塞调用），
                                // 所以每块之间自己看一眼状态。
                                val st = state(task.key)
                                if (st == State.PAUSED || st == State.CANCELED) break
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                received += n
                                val now = System.currentTimeMillis()
                                if (now - lastPush > 300) {
                                    lastPush = now
                                    update(task.key) {
                                        it.copy(received = received, total = if (total > 0) total else it.total)
                                    }
                                    // 每 1 秒把断点落一次盘：进程被杀也能接上
                                    if (now - lastSave > 1000) {
                                        lastSave = now
                                        savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                                    }
                                }
                            }
                        }
                    }

                    // 暂停 / 取消：这里必须直接结束这次协程，**不能**只是 `return@use`
                    // —— 从 `use` 的 lambda 退出来之后，下面的「收尾」照样会执行，
                    // 于是半截 `.part` 被搬进公共目录、任务还被标成「已完成」。
                    // 用户视角就是「暂停后任务直接跳过，多出一个 0B（或半首）文件」
                    // （m02003 第④点：上一版只挡住了 HTTP 错误那条路，漏了暂停这条）。
                    val after = state(task.key)
                    if (after == State.PAUSED || after == State.CANCELED) {
                        if (after == State.PAUSED) {
                            // 暂停：断点留盘，下次从这儿接着下
                            savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                        } else {
                            // 取消：和 catch 里的口径一致，半截文件与账本一起清掉
                            runCatching { partFile.delete() }
                            clearPart(task.key)
                        }
                        return@launch
                    }
                }

                // 断点不可信（416 且 .part 里并没有整首）：清干净后重新排一次队，
                // 下一轮从零开始下。绝不把 416 这种内部细节甩到界面上。
                if (restart) {
                    update(task.key) { it.copy(state = State.QUEUED, received = 0, total = 0) }
                    queue.trySend(_tasks.value.first { it.key == task.key })
                    return@launch
                }
                // 上面的 error 分支都只是 `return@use`（从 lambda 里退出来），执行会
                // 接着往下走 —— 这里必须拦一道，否则半截 .part 会被当成成品搬进
                // 公共目录，用户看到的就是「报错 + 目录里多一个文件」（m01537 第④点）。
                if (stopReason != null) {
                    savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                    return@launch
                }

                // 最后一道闸（m02003 第④点）：确认音频真的下全了才搬文件。
                // 除 HTTP 错之外还有别的「跑到这儿但文件不全」的来路 —— 远端提前
                // 断开、响应被截断、`.part` 只写进一个标签头。这些都不该以 0B 或
                // 半首歌的形式变成「已下载」，就地标失败并留好断点，让用户点重试。
                //
                // 判据优先用远端给的权威长度（`remoteTotal - skip`）：账本里的 `total`
                // 可能是老版本写的源流长度，比音频多出一个 `skip`，用它判会永远差
                // 那几十字节 —— 这正是用户 m04384 第③点（Y - X 恰好 45）的来路。
                val audioOnDisk = (partFile.length() - tagSize).coerceAtLeast(0L)
                val need = downloadNeedBytes(remoteTotal, skip, total)
                if (!downloadComplete(audioOnDisk, need)) {
                    savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                    update(task.key) {
                        it.copy(
                            state = State.ERROR,
                            error = "文件不完整（已下 $audioOnDisk / $need 字节）",
                        )
                    }
                    return@launch
                }

                // ---------------------------------------------------- 收尾
                // 下完了才在公共目录里建文件并复制过去：用户全程只看得见一个
                // 完整文件，媒体库里也不会留下半截条目。
                //
                // 整段放进 [NonCancellable]：`openSink` 里有挂起点（内部的
                // `withContext`），用户偏偏在这时点暂停，协程就会在「已经 insert 进
                // 媒体库、还没写一个字节」之间被取消 —— 目录里留下一个 0B 文件
                // （m01537 第④点）。音频既然已经拿全，这一步就该不管暂停一路做完。
                withContext(NonCancellable + Dispatchers.IO) {
                    val sink = openSink(task, fileNameForPart)
                    if (sink == null) {
                        update(task.key) {
                            it.copy(state = State.ERROR, error = lastSinkError.ifEmpty { "无法创建文件" })
                        }
                        return@withContext
                    }
                    try {
                        java.io.FileInputStream(partFile).use { input ->
                            sink.stream.use { out -> input.copyTo(out, 64 * 1024) }
                        }
                    } catch (e: Exception) {
                        runCatching { sink.stream.close() }
                        // 没写完的公共文件必须删掉，包括「一个字节都没写进去」的 0B 空壳
                        runCatching { context.contentResolver.delete(Uri.parse(sink.uri), null, null) }
                        throw e
                    }
                    // 字节齐了才让这条记录对别的应用可见（MediaStore 的 IS_PENDING）
                    runCatching { sink.commit?.invoke() }
                    runCatching { partFile.delete() }
                    clearPart(task.key)
                    update(task.key) {
                        it.copy(
                            state = State.DONE,
                            received = if (total > 0) total else received,
                            total = if (total > 0) total else received,
                            file = sink.displayPath,
                            uri = sink.uri,
                        )
                    }
                    onDone?.invoke(_tasks.value.first { it.key == task.key })
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消与暂停都走这条：状态已经由 pause()/cancel() 置好了
                val cur = state(task.key)
                if (cur == State.PAUSED) {
                    savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                } else if (cur == State.CANCELED) {
                    runCatching { partFile.delete() }
                    clearPart(task.key)
                } else {
                    update(task.key) { it.copy(state = State.CANCELED) }
                    runCatching { partFile.delete() }
                    clearPart(task.key)
                }
            } catch (e: Exception) {
                // 已经被暂停/取消的任务不该再被异常改写成「失败」
                val cur = state(task.key)
                if (cur == State.PAUSED) {
                    savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                } else if (cur != State.CANCELED) {
                    // 失败也留着 .part：点重试能从断点接着下
                    savePart(task.key, fileNameForPart, tagSize, skip, received, total)
                    update(task.key) { it.copy(state = State.ERROR, error = friendlyError(e)) }
                }
            }
        }
        jobs[task.key] = job
        job.join()
        jobs.remove(task.key)
    }

    // ------------------------------------------------------------ 断点账本
    /**
     * 续传用的 .part 文件：放在应用私有目录，媒体库扫不到。
     *
     * 名字里带歌曲 id 与音质档，所以「同一首歌换档重下」会写到另一个文件、
     * 各续各的，绝不会把两种码率的音频拼进一份。
     */
    private fun partFileFor(task: Task, ext: String): File {
        val dir = File(context.filesDir, "parts")
        dir.mkdirs()
        return File(dir, "${task.songId}@${task.quality}$ext.part")
    }

    private fun partsJson(): org.json.JSONObject = try {
        org.json.JSONObject(prefs.downloadParts)
    } catch (e: Exception) {
        org.json.JSONObject()
    }

    private fun readPart(key: String): org.json.JSONObject? = try {
        partsJson().optJSONObject(key)
    } catch (e: Exception) {
        null
    }

    private fun savePart(key: String, name: String, tag: Long, skip: Long, received: Long, total: Long) {
        try {
            val o = partsJson()
            o.put(
                key,
                org.json.JSONObject()
                    .put("name", name)
                    .put("tag", tag)
                    .put("skip", skip)
                    .put("received", received)
                    .put("total", total)
                    .put("at", System.currentTimeMillis()),
            )
            prefs.downloadParts = o.toString()
        } catch (e: Exception) {
            // 记不住断点最多退化成重下一遍，不该让下载失败
        }
    }

    private fun clearPart(key: String) {
        try {
            val o = partsJson()
            if (o.has(key)) {
                o.remove(key)
                prefs.downloadParts = o.toString()
            }
        } catch (e: Exception) {
        }
    }

    /**
     * 落盘的抽象：两条路径（MediaStore / 直接建文件）在这里合流。
     *
     * @param relative 相对公共音乐目录的路径，例如 `网易云音乐 Lite/歌手/歌手 - 歌名.mp3`
     * @param commit 字节全部写完之后再执行的收尾动作；为 null 表示无需收尾。
     *   目前只有 MediaStore 那条路用得上（见 [openSink] 里的 `IS_PENDING`）。
     */
    private class Sink(
        val stream: OutputStream,
        val displayPath: String,
        val uri: String,
        val commit: (() -> Unit)? = null,
    )

    /**
     * 把异常翻成一句用户能看懂的话（用户 m07698 第②点）。
     *
     * 原来直接把 `e.message` 显示出来，实测断网时那一行是 `timeout` 四个字母 ——
     * 用户既不知道是网络问题、也不知道该等还是该重试。异常类型本身才是信息，
     * 消息往往只是 OkHttp 内部的一句短语。
     */
    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException ->
            "连接超时：网络不通或太慢，检查网络后重试"
        is java.net.UnknownHostException ->
            "无法解析服务器地址：当前没有网络"
        is java.net.ConnectException ->
            "连接被拒绝：服务器暂时不可达"
        is javax.net.ssl.SSLException ->
            "HTTPS 握手失败：可能是网络被劫持，换个网络再试"
        is java.io.IOException -> {
            val m = e.message ?: ""
            when {
                m.contains("ENOSPC", true) || m.contains("No space", true) ->
                    "存储空间不足"
                m.contains("EPERM", true) || m.contains("EACCES", true) ->
                    "没有写入权限：换一个下载文件夹试试"
                else -> "网络读写中断（${m.ifEmpty { "IO 错误" }}）"
            }
        }
        else -> e.message?.takeIf { it.isNotBlank() } ?: "下载失败"
    }

    /**
     * 把源流开头那段 ID3 标签吞掉，只留纯音频帧（用户 m07698 第③点）。
     *
     * CDN 返回的音频**自己就带着**一段 ID3v2.4 标签。实测两个例子都是 45 字节、
     * 里面只有一条 `TSSE Lavf58.76.100`（转码器签名），没有任何歌曲信息。
     * 我们刚往文件头写了自己的 ID3v2.3，再把它原样接上，文件里就有了两个 ID3：
     *
     * ```
     * [我们的 ID3v2.3] [CDN 的 ID3v2.4] [MP3 帧…]
     *                  ↑ 播放器读到第一个标签就停，这一段成了夹在中间的垃圾
     * ```
     *
     * 更糟的是官方客户端下的同一首歌只有一个标签，两边的文件因此永远差这几十字节 ——
     * 逐字节比对永远对不上。所以下载时把源流这一段跳过。
     *
     * 判定完全照 [Id3] 的 syncsafe 规则：`ID3` + 主版本 2/3/4 + 标签头里那个
     * 4×7bit 长度。不是 ID3 就一个字节都不动（用 `mark`/`reset` 还回去）。
     *
     * [onSkip] 会在**跳之前**先报一次要跳多少：断点续传要靠它把「已写音频长度」
     * 换算成源流里的字节偏移（`Range: bytes=` 的起点），晚一步报的话进程被杀
     * 就接不上了。
     */
    private fun skipLeadingId3(raw: InputStream, onSkip: ((Long) -> Unit)? = null): InputStream {
        val buf = java.io.BufferedInputStream(raw, 64 * 1024)
        buf.mark(16)
        val head = ByteArray(10)
        var read = 0
        while (read < 10) {
            val n = buf.read(head, read, 10 - read)
            if (n <= 0) break
            read += n
        }
        // 不足 10 字节、或者头三个字节不是 "ID3"、或者版本号不在 2~4：
        // 都不是我们要跳的标签，把读掉的还回去原样往下走。
        val looksId3 = read == 10 &&
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() &&
            head[3].toInt() in 2..4
        if (!looksId3) {
            buf.reset()
            onSkip?.invoke(0L)
            return buf
        }
        val size = ((head[6].toInt() and 0x7F) shl 21) or
            ((head[7].toInt() and 0x7F) shl 14) or
            ((head[8].toInt() and 0x7F) shl 7) or
            (head[9].toInt() and 0x7F)
        // 标签后面可能还有一段 footer（v2.4 的标志位 0x10），有就一并跳过。
        val hasFooter = head[3].toInt() == 4 && (head[5].toInt() and 0x10) != 0
        val total = size.toLong() + 10 + if (hasFooter) 10 else 0
        onSkip?.invoke(total)
        // reset 回到 0，然后连那 10 字节头一起跳过。
        buf.reset()
        var toSkip = total
        while (toSkip > 0) {
            val n = buf.skip(toSkip)
            if (n <= 0) break
            toSkip -= n
        }
        return buf
    }

    /**
     * 取封面原图，塞进 ID3 的 `APIC` 帧（用户 m07048 第④点）。
     *
     * 用 `param=1080y1080&quality=80` 而不是 500：用户 m08537 第①点把「我们下的」
     * 和「官方 app 下的」同一首歌逐字节比了一遍 —— 音频载荷完全相同
     * （`sha256` 一致），差的字节几乎全在封面上：官方是 1080×1080 的
     * 155,602 B，我们 500×500 只有 42,270 B。想要「和官方一样」就得按官方
     * 那个尺寸取。
     *
     * 用户 m09508 第③点又说「官方还是更大」，于是把 CDN 的 `quality` 参数
     * 从 74 逐档扫到 90：不带 `quality` 时 CDN 按 75 出图（这张封面 135,656 B），
     * 官方那份是 **quality=80** 出的（155,602 B）—— 两者 `sha1` 与官方
     * `APIC` 里的 JPEG **逐字节相同**。所以 `quality=80` 不是拍的，是比出来的。
     *
     * 上限是 2MB 而不是 1MB：1080 的 JPEG 一般 150~300KB，但**有些封面源图
     * 本身是 PNG**，同尺寸能到 1MB 出头（实测《バイバイ。》那张 1080×1080 的
     * PNG 是 1,036,379 B）。卡 1MB 会把这类封面整个丢掉，症状是「大部分歌
     * 有封面、个别歌没有」。再大（>2MB）说明源站回了原图，不该进标签。
     * 取不到（离线、CDN 404、图太大）就返回 `null`，只写文本帧 —— 标签少一张图
     * 远好过整个下载因为封面失败而报错。
     */
    private suspend fun fetchCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext null
        val big = Id3.coverUrl(url)
        runCatching {
            val req = Request.Builder().url(big)
                .header("User-Agent", com.netease.lite.api.Http.UA)
                .build()
            Client.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                // 太大的不要：标签里塞一张几 MB 的图会让播放器打开变慢。
                if (bytes.size > 2 * 1024 * 1024) null else bytes
            }
        }.getOrNull()
    }

    /**
     * 拼 `COMM` 帧要用的字段（用户 m11461 第①点）。
     *
     * 三个值的来源，都踩过一下：
     *
     *   - `albumPic` 官方写的是 **http**（不是 https），且**不带任何查询参数**。
     *     直接拿 `Song.cover` 不行 —— 它已经被 [com.netease.lite.model.httpsUrl]
     *     改写成 https 了，照抄会和官方差 1 个字符。
     *   - `albumPicDocId` 是封面 URL 末尾那个数字（`…/109951172148710780.jpg`），
     *     不是专辑 id。抠不出来就写 0。
     *   - `gain` 官方写的是 ReplayGain 值（-9.312800407409668 这种），**接口里恒为 0**，
     *     要么解码整首歌做 EBU R128 响度分析才能算出来。这里写 0 并在注释里认账：
     *     官方 app 靠 `musicId` 认文件，`gain` 只影响音量归一化，写 0 不会让它不认。
     */
    private fun commOf(task: Task, discNo: Int, br: Int, discText: String = ""): Id3.CommInfo {
        val pic = task.cover.replaceFirst(Regex("^https://"), "http://")
            .replace(Regex("\\?.*$"), "")
        val docId = Regex("/(\\d+)\\.[A-Za-z0-9]+$").find(pic)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Id3.CommInfo(
            musicId = task.songId,
            musicName = task.name,
            artists = task.artists.map { it.name to it.id },
            album = task.album,
            albumId = task.albumId,
            albumPicDocId = docId,
            albumPic = pic,
            mvid = task.mvid,
            bitrate = br,
            durationMs = task.duration,
            gain = 0.0,
            alias = task.alias,
            transNames = emptyList(),
            // 碟名原文给 TPOS 帧用（`disc1 万籁拂旧尘`）；单碟专辑这里是空串。
            discText = discText,
            padding = 128,
        )
    }

    /**
     * 上一次 [openSink] 失败的原因。
     *
     * 为什么要把失败原因带出来：原来 `openSink` 用 `catch (e: Exception) { null }`
     * 把所有异常都吃掉，界面只能显示一句「无法创建文件」。用户把下载地址改成
     * `Download/MyMusic/2026/Oct` 之后，看到的就是这句话 —— 它既不说哪个路径没建成、
     * 也不说是不是权限问题，等于让人自己猜。现在把 `insert()` 返回 null、
     * `openOutputStream()` 返回 null、以及日志里的异常文本都收进这个字段，
     * 由 [runTask] 显示到下载队列那一行上。
     */
    @Volatile
    private var lastSinkError: String = ""

    private suspend fun openSink(task: Task, fileName: String): Sink? = withContext(Dispatchers.IO) {
        lastSinkError = ""
        val opt = options()
        // 分类只影响子目录：整条相对路径仍然是「<基目录>/…」下的，
        // 用户在系统音乐库里看到的还是同一个来源目录。
        val sub = when (opt.organize) {
            "artist" -> task.artistText.substringBefore("/").trim().ifBlank { "未知歌手" }
            "album" -> task.album.ifBlank { "未知专辑" }
            else -> ""
        }.let { if (it.isEmpty()) "" else "${sanitize(it)}/" }
        // 用户 m48583 第①点：下载地址要能**完全自定义**。
        // 两条路：选了 SAF 目录树就用它（真·随便哪个位置），否则用可自由输入的
        // 相对路径（首段必须是公共目录，否则 MediaStore 会拒）。
        val tree = opt.treeUri.trim()
        if (tree.isNotEmpty()) {
            val sink = openTreeSink(tree, sub, fileName)
            if (sink != null) return@withContext sink
            // SAF 树失效（用户撤了授权、删了目录）就继续走下面的 MediaStore 分支，
            // 而不是直接把这次下载判死 —— 歌先落到默认位置总比丢掉好。
        }
        val base = normalizeBase(opt.baseDir)
        // MediaStore 的 RELATIVE_PATH **末尾必须带 `/`**：带斜杠它才当成目录，
        // 不带的话这一串会被当成「一个叫这名字的文件」，insert() 直接返 null
        // （而且不抛异常），症状就是下载队列里一句没头没脑的「无法创建文件」。
        // 拼空白子目录时必须补这一刀 —— `"$base/$sub"` 在 organize=none 时正好
        // 落在「以斜杠结尾」的形态上，所以老代码只在改过分类方式之后才炸。
        val relPath = ("$base/$sub").trimEnd('/') + "/"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                // 同名文件已经在媒体库里就先删掉（m00001 第①点）。
                //
                // MediaStore 的 `insert()` **不会**覆盖同名条目：它会自己改名成
                // `歌名 (1).mp3` 再插一条。用户「重新下载」/ 暂停后再下时看到目录里
                // 多出一个文件，根因就在这儿 —— 下载器本身只写一个文件，是媒体库
                // 帮我们「备份」了一份。删旧再插，目录里永远只有一个。
                runCatching {
                    resolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.RELATIVE_PATH}=?",
                        arrayOf(fileName, relPath),
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(
                        MediaStore.Audio.Media.MIME_TYPE,
                        if (fileName.endsWith(".flac")) "audio/flac" else "audio/mpeg",
                    )
                    put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    // 先挂成「待定」：写入过程中被杀进程 / 掉电，系统会把这条
                    // 没写完的条目自动收回，音乐库与文件管理器里**不会**出现一个
                    // 0 字节的空壳（m01537 第④点）。字节写全之后由 `commit` 转正。
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    lastSinkError = "无法在 $relPath 建文件（系统媒体库拒绝了这个路径）"
                    return@withContext null
                }
                val os = resolver.openOutputStream(uri)
                if (os == null) {
                    // 已经插进媒体库但打不开流：把半截条目删掉，别在系统音乐库里
                    // 留一条 0 字节的空记录。
                    runCatching { resolver.delete(uri, null, null) }
                    lastSinkError = "无法写入 $relPath（文件流打不开）"
                    return@withContext null
                }
                Sink(
                    os,
                    "$relPath$fileName",
                    uri.toString(),
                    commit = {
                        val done = ContentValues().apply {
                            put(MediaStore.Audio.Media.IS_PENDING, 0)
                        }
                        runCatching { resolver.update(uri, done, null, null) }
                        Unit
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStorageDirectory()
                val dir = File(root, relPath)
                if (!dir.exists() && !dir.mkdirs()) {
                    lastSinkError = "无法创建目录 $relPath"
                    return@withContext null
                }
                val f = File(dir, fileName)
                // 同上：已有同名文件先删，否则会多出一份（`FileOutputStream` 本身是覆盖，
                // 这条删只是顺手清掉上一次失败留下的空壳）。
                runCatching { if (f.exists()) f.delete() }
                Sink(java.io.FileOutputStream(f), "$relPath$fileName", Uri.fromFile(f).toString())
            }
        } catch (e: Exception) {
            lastSinkError = "无法写入 $relPath：${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /**
     * 写进用户用 SAF 选中的目录树。
     *
     * 为什么这条路能「完全自定义」：SAF 的树 URI 是用户亲手点选的授权，系统给
     * 应用一个 `content://` 句柄，往树里建子目录/建文件都不需要任何存储权限，
     * 也不受 `MediaStore.RELATIVE_PATH` 那几棵标准子树的限制。代价是落盘的不是
     * 媒体库条目（部分第三方播放器/系统音乐 App 扫不到），并且**重启后必须先
     * `takePersistableUriPermission`**，否则 `listFiles()` 返空 —— 见
     * [com.netease.lite.data.LocalIndex.scan] 里同一处坑的注释。
     */
    private fun openTreeSink(treeUri: String, sub: String, fileName: String): Sink? {
        return try {
            val uri = Uri.parse(treeUri)
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            if (root == null || !root.canWrite()) {
                null
            } else {
                // 分类子目录逐段往下建（`歌手/`、`专辑/`），已存在就复用。
                var dir: androidx.documentfile.provider.DocumentFile = root
                for (seg in sub.split('/').filter { it.isNotEmpty() }) {
                    val next = dir.findFile(seg)?.takeIf { it.isDirectory }
                        ?: dir.createDirectory(seg)
                    if (next == null) return null
                    dir = next
                }
                // 同名文件先删再建（m00001 第①点）。`DocumentFile.createFile()` 遇到
                // 重名不会覆盖，而是自己改成 `歌名 (1).mp3` —— 「重新下载」一次就多
                // 一个文件，正是用户报的现象。目标目录是用户自己选的，同名即同一个
                // 下载目标，删掉旧的才是他要的「只保留一份」。
                dir.findFile(fileName)?.delete()
                val doc = dir.createFile(mimeFor(fileName), fileName) ?: return null
                val os = context.contentResolver.openOutputStream(doc.uri) ?: return null
                // displayPath 给界面看：树的真实磁盘路径拿不到，只能显示树名 + 文件名。
                Sink(os, "${root.name ?: "自定义目录"}/$sub$fileName", doc.uri.toString())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeFor(fileName: String): String =
        if (fileName.endsWith(".flac")) "audio/flac" else "audio/mpeg"

    companion object {
        /** 默认基目录。与桌面版 `download.js` 的「默认是系统的音乐文件夹」同义。 */
        const val DEFAULT_BASE_DIR = "Music/网易云音乐 Lite"

        /** 默认命名格式。用户 m03577 第②点改的，桌面版必须同步。 */
        const val DEFAULT_TEMPLATE = "{歌手名} - {歌曲名}"

        /**
         * `MediaStore.Audio` 认得的公共目录首段。
         *
         * **这份名单是实测出来的，不是猜的**：往 `content://media/external/audio/media`
         * 里 insert 一条 `RELATIVE_PATH=Download/xxx` 会被系统顶回来
         *
         * ```
         * Primary directory Download not allowed for content://media/external/audio/media;
         * allowed directories are [Alarms, Audiobooks, Music, Notifications, Podcasts, Ringtones]
         * ```
         *
         * 换句话说 **`Download` / `Documents` / `DCIM` 对音频表是非法首段**。
         * 上一轮的名单把这三个写进来了（当时没实测，只按「公共目录」想当然），
         * 结果用户在设置页选了「下载/Download」这一档之后每一首都下不来。
         *
         * 音频表只认这六个。要让歌落到别处（下载目录、SD 卡、U 盘），走 SAF 目录树
         * 那条路（[UPDATE_TREE_HINT] / 设置页的「选一个文件夹…」），那条是真·任意位置。
         */
        val PUBLIC_ROOTS = listOf(
            "Music", "Podcasts", "Audiobooks", "Ringtones", "Alarms", "Notifications",
        )

        /**
         * 规范化用户输入的基目录，**不再夹回固定几档**。
         *
         * 规则：去空白、去首尾斜杠、把反斜杠与连续斜杠压平成 `/`、逐段去掉
         * `.`/`..` 与非法字符；首段不在 [PUBLIC_ROOTS] 里时补上 `Music/` 前缀
         * 并给出提示（返回的 [BaseDir.note] 非空）。
         *
         * 之所以「补前缀」而不是「拒绝」：用户输 `我的歌` 的意图很清楚 —— 想在
         * 音乐目录下建一个叫「我的歌」的文件夹，弹一句「路径不合法」只会让人困惑。
         *
         * 有一条特例要单独说：用户输 `Download/xxx` / `Documents/xxx` / `DCIM/xxx`
         * 时，**不是**补 `Music/` 前缀，而是把这几个词降级成 Music 下的一个子目录
         * （`Download/MyMusic` → `Music/Download/MyMusic`）。原因是音频表的
         * `RELATIVE_PATH` 硬性只认 [PUBLIC_ROOTS] 那六个首段，直接放行会让
         * 每一首下载都失败 —— 而用户输这个词的本意只是「想有个叫下载的文件夹」，
         * 不是「必须落在系统的 Download 目录」。降级 + 一句提示，比让下载全挂好。
         * 真要落到系统的 Download 目录，走设置页的「选一个文件夹…」（SAF）。
         */
        fun normalizeBase(raw: String): String = parseBase(raw).path

        data class BaseDir(val path: String, val note: String = "")

        /** 系统里存在、但**音频表不接受**的公共目录。落到这里必须降级。 */
        private val NON_AUDIO_ROOTS = listOf("Download", "Documents", "DCIM", "Movies", "Pictures")

        fun parseBase(raw: String): BaseDir {
            val flat = raw.trim()
                .replace('\\', '/')
                .replace(Regex("/{2,}"), "/")
                .trim('/')
            if (flat.isEmpty()) return BaseDir(DEFAULT_BASE_DIR)
            val segs = flat.split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { it.replace(Regex("""[:*?"<>|]"""), "_") }
            if (segs.isEmpty()) return BaseDir(DEFAULT_BASE_DIR)
            val first = segs.first()
            val matched = PUBLIC_ROOTS.firstOrNull { it.equals(first, ignoreCase = true) }
            if (matched != null) {
                return BaseDir((listOf(matched) + segs.drop(1)).joinToString("/"))
            }
            // 系统的 Download / Documents … 对音频表是非法的，降级成 Music 下的子目录。
            val asSub = NON_AUDIO_ROOTS.firstOrNull { it.equals(first, ignoreCase = true) }
            if (asSub != null) {
                return BaseDir(
                    (listOf("Music", asSub) + segs.drop(1)).joinToString("/"),
                    "系统音乐库不接受 $asSub/ 作为音频目录，已改存到 Music/$asSub/；" +
                        "想放到别处请点「选一个文件夹…」",
                )
            }
            return BaseDir(
                (listOf("Music") + segs).joinToString("/"),
                "为了让系统音乐库能扫到，路径以 Music/ 开头",
            )
        }

        /** 占位符 `{音质}` 的取值。与桌面版 `download.js` 的 `QUALITY_LABEL` 同一套。 */
        private val QUALITY_TAG = mapOf(
            "standard" to "标准",
            "higher" to "较高",
            "exhigh" to "极高",
            "lossless" to "无损",
        )

        /**
         * 套用文件名模板。
         *
         * 与桌面版 `electron/download.js` 的 `applyTemplate` 是同一套占位符
         * （`{首歌手名} {歌曲名} {歌手名} {专辑名} {音质}`）。两边必须同步改：
         * 用户在两个平台上看到的是同一个设置项。
         *
         * 模板整体为空时退回「歌手 - 歌名」，避免一堆「.mp3」。
         */
        fun applyTemplate(
            template: String,
            title: String,
            artist: String,
            firstArtist: String,
            album: String,
            quality: String,
        ): String {
            // 用户 m03577 第②点：默认从「{首歌手名} - {歌曲名}」改成「{歌手名} - {歌曲名}」。
            val tpl = template.ifBlank { DEFAULT_TEMPLATE }
            // 用户 m11461 第②点之后 `Song.artistText` 是 `"A/B"` 紧贴（官方就是这么
            // 显示的）。用户 m15635 第④点又要求「原封不动」—— 所以这里**不再**把
            // 斜杠换成空格，交给 [sanitize] 换成全角 `／`：肉眼看还是 `A/B/C`，
            // 文件系统也认。
            val multiArtist = artist.trim()
            val out = tpl
                .replace("{首歌手名}", firstArtist.ifBlank { artist }.ifBlank { "未知歌手" })
                .replace("{歌曲名}", title.ifBlank { "未命名" })
                .replace("{歌手名}", multiArtist.ifBlank { "未知歌手" })
                .replace("{专辑名}", album.ifBlank { "未知专辑" })
                .replace("{音质}", quality)
            // 占位符全填完之后如果只剩分隔符（比如模板是 "{专辑名}" 而专辑为空），
            // 再兜一次底，别落成一个叫「.mp3」的文件。
            return out.ifBlank {
                "${artist.trim().ifBlank { "未知歌手" }} - ${title.ifBlank { "未命名" }}"
            }
        }

        /**
         * 文件名净化。
         *
         * 用户 m15635 第④点：**不要用下划线顶替**。以前把 `/ \ : * ? " < > |`
         * 一律换成 `_`，于是多歌手的 `A/B/C` 落成 `A_B_C`、《DAMIDAMI: Reprise》
         * 落成 `DAMIDAMI_ Reprise` —— 名字被改得认不出来。
         *
         * 现在换成**长得一样的全角字符**（`：` `？` `／` …）：文件系统认它们是
         * 合法字符，肉眼看还是原来那个名字。控制字符（`\u0000`–`\u001F`）没法
         * 这样处理，直接丢掉。首尾空白与结尾句点仍要去掉 —— Windows 不允许
         * 文件名以 `.` 结尾。
         */
        private val FULLWIDTH = mapOf(
            '/' to '／', '\\' to '＼', ':' to '：', '*' to '＊', '?' to '？',
            '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜',
        )

        fun sanitize(name: String): String = buildString {
            for (ch in name) {
                when {
                    ch.code < 0x20 || ch.code == 0x7F -> Unit   // 控制字符：丢掉
                    FULLWIDTH.containsKey(ch) -> append(FULLWIDTH.getValue(ch))
                    else -> append(ch)
                }
            }
        }
            .trim()
            .trimEnd('.')
            .take(120)
            .ifEmpty { "未命名" }
    }
}
