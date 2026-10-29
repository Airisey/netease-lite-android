package com.netease.lite.play

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.netease.lite.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 界面与 [PlaybackService] 之间的桥。
 *
 * 界面**不**自己 new 一个 ExoPlayer —— 那样锁屏后一划掉界面音乐就断了。
 * 正确做法是连到服务的 `MediaSession`：服务持有播放器，界面只是个遥控器。
 * 界面被系统回收、被划掉，服务还在放；界面回来时重连，进度和队列原样都在。
 *
 * 状态用 Compose 的 `mutableStateOf` 暴露（而不是 Flow）：
 * 这里每个字段都是「一变就重画」，Flow 的 collect 只是在中间多一层。
 * 变化都来自 ExoPlayer 的监听回调，天然在主线程。
 */
@UnstableApi
class PlayerController(private val scope: CoroutineScope) {

    var controller: MediaController? = null
        private set

    // ------------------------------------------------------------ 对外状态
    var currentSong: Song? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableStateOf(0L)
        private set

    /**
     * [positionMs] 最后一次被赋值的时刻（[SystemClock.elapsedRealtime]，毫秒）。
     *
     * 只在主线程写，读的人也在主线程（[positionNow] 由界面每帧调用），不用加锁。
     */
    private var positionStamp = 0L
    var durationMs: Long by mutableStateOf(0L)
        private set
    var bufferedMs: Long by mutableStateOf(0L)
        private set
    /**
     * 循环档位，只有三种（用户 m00001 第④点）。
     *
     * 初值必须是三档之一。以前这里写的是 `Playback.REPEAT_LIST`，于是**全新安装**
     * 或服务刚起来、队列还没恢复的那段时间，界面上显示的是第四档「列表循环」。
     */
    var repeatMode: String by mutableStateOf(Playback.REPEAT_ORDER)
        private set
    var queueSize: Int by mutableStateOf(0)
        private set
    var queueIndex: Int by mutableStateOf(-1)
        private set

    /** 当前曲目对应的歌曲（队列里 index 位置的那首）。队列是 id 字符串。 */
    private var queueSongs: List<Song>
        get() = queue
        set(value) { queue = value }

    /**
     * 播放队列，给「播放队列」页读。
     *
     * 之前队列只有 `queueSongsSnapshot()` 这个普通函数 —— 一个页面读它，队列变了
     * 界面也不知道（Compose 只对 State 重组）。改成对外暴露的 State 之后，
     * 加歌、删歌、切随机都会让队列页自己重画。
     */
    var queue: List<Song> by mutableStateOf(emptyList())
        private set

    /**
     * 播放失败回调（所有候选档都取不到流 / 音频解码不了）。
     * 界面要弹提示并跳到下一首。
     */
    var onFailure: ((Song?, String) -> Unit)? = null

    /** 每次真正开始播一首新歌时回调，用来记录「最近播放」和写进度。 */
    var onSongStarted: ((Song) -> Unit)? = null

    /** 进度需要落盘时回调（节流由调用方做）。 */
    var onProgress: ((Song, Long) -> Unit)? = null

    /**
     * 队列变了要落盘时回调（用户 m03123 第②点：关掉应用不能把队列清掉）。
     *
     * 只在**用户动了队列**的地方调（起播、加歌、插歌、删歌、清空、切随机、切歌），
     * 不放在 [syncQueue] 里统一调 —— 冷启动时 `connect()` 也会走一遍 syncQueue，
     * 那时队列还是空的，落盘等于把刚存下的队列当场抹掉。
     */
    var onQueueChanged: (() -> Unit)? = null

    /**
     * 恢复过程中不落盘。
     *
     * [restoreQueue] 会把队列和序号一项项摆好，中途每次 `setMediaItems` 都会触发
     * 切歌回调；不挡住的话，落盘的是「摆到一半」的队列 —— 比如序号还停在 0。
     */
    private var suppressPersist = false

    private fun persist() {
        if (suppressPersist) return
        onQueueChanged?.invoke()
    }

    private var lastSavedAt = 0L

    /**
     * 主线程队列。只用来把「播放器回调里想做的事」挪到回调返回之后。
     *
     * 用 [Dispatchers.Main] 代替不了：界面传进来的 scope 是 `viewModelScope`，
     * 它的调度器是 `Main.immediate` —— 已经在主线程时会**立刻内联执行**，
     * 等于没挪。
     */
    private val main = Handler(Looper.getMainLooper())

    /**
     * 上一次真正「开播」的歌曲 id。
     *
     * 用户 m00932 第③点：切到/切出随机模式时 [reorder] 会用 `setMediaItems` 整批
     * 换队列，ExoPlayer 因此报一次「切歌」回调 —— 可歌其实还是同一首。那时候若照样
     * 走 [onSongStarted]，界面就会重新拉一遍歌词（面板先清空再填回，看起来就是
     * 「切循环模式把歌词重载了」），「最近播放」也会把同一首连记两次。
     * 所以同一首歌连着来时不再回调；用户主动起播（[playList] / [playAt] /
     * [restore]）会把这个值清掉，保证「重播同一首」仍然算一次新的开播。
     */
    private var lastStartedId = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncCurrent()
            // 序号变了也要落盘：下次打开要停在「正在听的那首」上，不是队列第一首。
            persist()
            val song = currentSong ?: return
            if (song.id == lastStartedId) return
            lastStartedId = song.id
            onSongStarted?.invoke(song)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                durationMs = controller?.duration?.coerceAtLeast(0L) ?: 0L
                // 准备好了才恢复进度：太早 seek 会被 ExoPlayer 丢掉。
                restorePendingSeek()
            }
            if (state == Player.STATE_ENDED) {
                isPlaying = false
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val song = currentSong
            // 用户 m48583 第②点：本地文件**打得开但解不开**（容器头坏了、下载时
            // CDN 回了半截响应）只有真正解码时才暴露。这时不该判成「播放失败」
            // 然后跳下一首 —— 这首明明还能在线听。
            //
            // 做法是在原地重来一次：数据源层（PlaceholderDataSourceFactory）每次
            // prepare 都会重新解析地址，而 LocalSource 已经把这首标成坏文件，
            // 所以第二次解析必然走在线流。consumeDecodeFailure 用 broken 集合
            // 做闸门，保证一首歌最多回退一次，不会在线也失败之后来回死循环。
            if (song != null && LocalSource.consumeDecodeFailure(song.id)) {
                val at = controller?.currentPosition ?: 0L
                val index = controller?.currentMediaItemIndex ?: 0
                // 用户 m00932 第②点：这两个调用**不能**在播放器回调里原地执行。
                // 回调是主线程上的（见类注释），而 MediaController 的命令要送回
                // 同一个主线程上的 MediaSession 去执行 —— 在回调里再发命令，
                // 就是拿主线程去等主线程：轻则重入播放器状态机，重则直接卡住。
                // 用户看到的现象正是「弹完『已转为在线资源』提示之后整个应用都不动了」。
                // post 到队列尾部，让这次回调先干净地返回。
                main.post {
                    controller?.let { c ->
                        c.seekTo(index, at.coerceAtLeast(0L))
                        c.prepare()
                    }
                }
                return
            }
            // onFailure 那条路也一样：上层在 autoSkip 打开时会调 player.next()，
            // 那又是一次「回调里发命令」。统一挪到回调返回之后。
            val why = friendly(error)
            main.post {
                onFailure?.invoke(song, why)
                // 跳到下一首，别卡在坏歌上。
                controller?.seekToNextMediaItem()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) { /* 由我们自己的 repeatMode 字段表达 */ }
    }

    private fun friendly(error: androidx.media3.common.PlaybackException): String = when (error.errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "网络连接失败"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "资源已失效（可能被限流或下架）"
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> "文件损坏或格式不支持"
        else -> error.message?.take(80) ?: "播放失败"
    }

    // ------------------------------------------------------------ 连接
    /**
     * 还没连上时要补做的事（见 [whenReady]）。
     *
     * 冷启动那几百毫秒里 `controller` 还是 null，而「恢复上次队列」正好赶在这段时间
     * 里发生 —— 直接调 [restoreQueue] 会撞上 `controller ?: return` 一声不响地丢掉，
     * 表现就是「关掉应用再打开，队列没了」。
     */
    private val readyQueue = mutableListOf<() -> Unit>()

    /** 连上了就立刻执行，没连上就等 [connect] 成功之后执行一次。 */
    fun whenReady(block: () -> Unit) {
        if (controller != null) block() else readyQueue.add(block)
    }

    fun connect(context: Context, onReady: () -> Unit = {}) {
        if (controller != null) { onReady(); return }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                c.addListener(listener)
                syncQueue()
                // 先补做排队的事（恢复队列），再通知界面 —— 反过来的话界面会先
                // 按空队列画一帧，迷你播放条闪一下才出来。
                val queued = readyQueue.toList()
                readyQueue.clear()
                queued.forEach { runCatching(it) }
                onReady()
            } catch (e: Exception) {
                onFailure?.invoke(null, "播放服务启动失败：${e.message ?: "未知原因"}")
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    // ------------------------------------------------------------ 队列
    /**
     * 用一批歌重建队列并从 [fromIndex] 开始播。
     *
     * 队列里每首歌的地址都是 `placeholder:<songId>` 占位符 —— 网易云的直链有
     * 时效，提前把整队列的地址取一遍等于白取。真正播放时由
     * [PlaceholderDataSourceFactory] 在数据源层把它换成真直链：只有那样才能
     * 同时覆盖界面点歌、通知栏下一首、蓝牙按键三条入口。
     *
     * ## 随机模式下为什么「先洗后给」（用户报的「点歌不切歌」）
     *
     * 这里原来写的是「先 `setMediaItems(songs, fromIndex)`，再洗 `queueSongs`
     * 并调 `reorder()`」。`MediaController` 上的 `setMediaItems` 是把命令送回
     * 会话的**异步**调用，紧接着读 `currentMediaItemIndex` / `getMediaItemAt()`
     * 拿到的还是上一份队列 —— 于是 `reorder()` 是拿旧队列去凑新顺序，算出来的
     * 每一个 `moveMediaItem` 都是错的；对不上时它还会退到 [rebuildItems]，而
     * 那里按 `currentSong`（此刻仍是**上一首**）反查下标：查不到就从头播、查得到
     * 就把播放指针挪回上一首。表现正是「点了别的歌，放出来的还是刚才那首」。
     *
     * 顺序一开始就洗好、只发一条 `setMediaItems`，这两个坑就都不存在了。
     */
    fun playList(
        songs: List<Song>,
        fromIndex: Int,
        contextId: Long = 0,
        contextName: String = "",
        autoPlay: Boolean = true,
    ) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        this.contextId = contextId
        this.contextName = contextName
        // 换个来源就等于换一份「原始顺序」，旧的随机还原点作废。
        originalSongs = songs
        // 随机模式必须**先**把顺序洗好，再一次性交给播放器 —— 理由见函数头的长注释。
        val ordered = if (repeatMode == Playback.SHUFFLE) {
            QueueState(songs = songs, index = fromIndex, originalOrder = songs).toShuffle().songs
        } else {
            songs
        }
        // 洗过之后要点的那首被 `toShuffle()` 钉在首位，起点固定是 0；顺序模式起点就是 fromIndex。
        val startAt = if (repeatMode == Playback.SHUFFLE) 0 else fromIndex.coerceIn(0, ordered.lastIndex)
        // 队列先落定（queueSongs 的 setter 直接转发给 queue）。
        queueSongs = ordered
        // 用户主动起播：清掉「上一次开播的 id」，这样即便点的还是刚才那一首，
        // onMediaItemTransition 的去重也不会把这次开播吞掉（见 lastStartedId）。
        lastStartedId = 0L
        c.setMediaItems(ordered.map { placeholderItem(it) }, startAt, 0L)
        c.prepare()
        // autoPlay=false 是「恢复上次播放」用的：队列摆好、停在那个位置，但不自己出声。
        c.playWhenReady = autoPlay
        // 界面状态直接按用户点的那首写，**不要**回读控制器：上面那条 `setMediaItems`
        // 是把命令送回会话的异步调用，回读 `currentMediaItemIndex` 拿到的还是上一首
        // 的下标，套进新列表就是另一首歌。真正对齐由 onMediaItemTransition 完成。
        queueSize = ordered.size
        queueIndex = startAt
        currentSong = ordered.getOrNull(startAt)
        // 换歌就等于进度归零：不清的话歌词面板会拿上一首的进度去高亮，
        // 进度条也会在新歌开头停半拍才回位。
        positionMs = 0L
        bufferedMs = 0L
        positionStamp = SystemClock.elapsedRealtime()
        persist()
    }

    /** 只换队列不播放（「加入播放列表」用）。 */
    fun appendToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        queueSongs = queueSongs + songs
        originalSongs = originalSongs + songs
        val items = songs.map { placeholderItem(it) }
        c.addMediaItems(items)
        syncQueue()
        persist()
    }

    /**
     * 插到当前曲后面立即播（桌面版的「下一首播放」）。
     *
     * 不能简单拼到队尾：用户点「下一首播放」的意思是「这首接下来就要听」，
     * 所以位置是 `currentMediaItemIndex + 1`，不是末尾。
     */
    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val at = (c.currentMediaItemIndex + 1).coerceIn(0, queueSongs.size)
        queueSongs = queueSongs.take(at) + songs + queueSongs.drop(at)
        originalSongs = originalSongs.take(at) + songs + originalSongs.drop(at)
        c.addMediaItems(at, songs.map { placeholderItem(it) })
        syncQueue()
        persist()
    }

    var contextId: Long = 0
        private set
    var contextName: String = ""
        private set

    /**
     * 把正在播的这一首重新加载一遍，进度不动（用户 m08537 第④点）。
     *
     * 切音质之后必须走这里：`quality` 只影响**下一次取流**，而正在播的那条流
     * 早就取好了、也缓冲好了 —— 不重载的话用户切完档听不出任何区别，会以为
     * 按钮没生效。
     *
     * ## 为什么换 mediaItem 而不是 `seekTo` 回原地
     *
     * 只 seek 不会重新打开数据源，ExoPlayer 用的是已经缓冲的那一段，音质照旧。
     * 换掉同一位置的 `MediaItem` 才会让它重新走 `PlaceholderDataSource` 的
     * resolver —— 那里每次都调 `TrackSource.resolve()`，于是拿到新档位的地址
     * （并且仍然是「本地优先，本地不行才在线」）。
     *
     * ## 为什么进度不会跳
     *
     * `replaceMediaItem` 会掉到 IDLE，所以要在 `prepare()` **之前**把
     * `playWhenReady` 与 `seekTo(idx, at)` 都摆好，让 ExoPlayer 在准备阶段
     * 就知道从哪开始 —— 先 prepare 再 seek 会先响一下第 0 秒。
     *
     * ## 为什么先写 `lastStartedId`
     *
     * 换 item 会触发一次 `onMediaItemTransition`，可歌还是同一首。不挡住的话
     * 界面会重新拉歌词、「最近播放」再记一遍 —— 与 m00932 第③点（切循环不该
     * 重载歌词）是同一个坑。
     */
    fun reloadCurrent(quality: String) {
        val c = controller ?: return
        val song = currentSong ?: return
        val idx = c.currentMediaItemIndex
        if (idx < 0 || idx >= c.mediaItemCount) return
        val at = c.currentPosition.coerceAtLeast(0L)
        val keepPlaying = c.playWhenReady || c.isPlaying
        lastStartedId = song.id
        // 代次 +1 再换 item：新地址里的 `?q=&e=` 才会被数据源层认（见
        // `PlaceholderDataSource` 的解析）。同时把上一轮换档留在别的 item 上的
        // 旧档位一起作废 —— 否则从队列回到那首歌还是旧音质。
        TrackSource.epoch += 1
        c.replaceMediaItem(idx, placeholderItem(song, quality))
        c.seekTo(idx, at)
        c.playWhenReady = keepPlaying
        c.prepare()
        // 上面那次 seekTo 会落在「掩码时间轴」上：replaceMediaItem 换的是同一位置
        // 的另一个 MediaItem，ExoPlayer 先用一条临时时间轴维持进度，等真正的
        // 数据源建好、时长拿到之后才换成新时间轴 —— 那一刻进度会掉回 0。
        // 实测日志：`reload at=26945 / afterSeek=26954 / afterPrepare=26954`，
        // 但几秒后 `dumpsys media_session` 的 position 是 2770（从头播）。
        // 所以再挂一次「准备好就补 seek」，复用冷启动恢复进度那条路径。
        //
        // 紧跟着的 `if (READY)` 不是多余的：如果换 item 之后播放器没离开 READY
        // （缓存命中、换档时数据源是同一个 CDN 地址），`onPlaybackStateChanged`
        // 不会再回调，补 seek 就永远等不到。而 pendingSeekMs 留着不清，下一首
        // 进 READY 时会被它拽回这首歌的进度 —— 那才是真 bug。
        seekOnReady(at)
        if (c.playbackState == Player.STATE_READY) restorePendingSeek()
        syncCurrent()
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in queueSongs.indices) return
        // 用户主动点某一首：清掉去重记忆（见 lastStartedId），重播同一首也算新开播。
        lastStartedId = 0L
        if (c.availableCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) {
            c.seekTo(index, 0L)
        } else {
            // 会话没给 `COMMAND_SEEK_TO_MEDIA_ITEM` 时（[PlaybackService.onConnect]
            // 的白名单漏写过一次），`seekTo(index, …)` 会被 MediaController 直接丢掉，
            // 用户看到的就是「点了队列里的歌，歌声没换、只是从当前这首接着放」。
            // `COMMAND_CHANGE_MEDIA_ITEMS` 一直在，所以退一步：整批重摆 + 指定起播位。
            c.setMediaItems(queueSongs.map { placeholderItem(it) }, index, 0L)
        }
        c.prepare()
        c.playWhenReady = true
        syncCurrent()
    }

    fun next() { controller?.let { if (it.hasNextMediaItem()) { it.seekToNextMediaItem() } } }
    fun previous() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L) } }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.mediaItemCount == 0) return
            c.play()
        }
    }

    /**
     * 只暂停。
     *
     * 与 [toggle] 分开是因为「恢复上次进度」要的语义是「摆好队列、停在那个位置」，
     * 不是「切换一下」—— 冷启动时 `isPlaying` 是 false，toggle 会当成用户按了播放键。
     */
    fun pause() {
        controller?.pause()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0L))
        positionMs = ms.coerceAtLeast(0L)
        positionStamp = SystemClock.elapsedRealtime()
        currentSong?.let { onProgress?.invoke(it, positionMs) }
    }

    /**
     * 估算**此刻**的播放位置（毫秒）。
     *
     * 用户报的「歌词不同步」（同一首歌电脑端正常）：`positionMs` 由 [tick] 每
     * 500ms 采一次，而桌面端是跟着 `requestAnimationFrame` 每帧用
     * `audio.currentTime` 重算的 —— 安卓这边的歌词行因此最多慢半拍才翻页，
     * 短句（一句两三个字的那种）尤其明显：句子都唱完了高亮还没过来。
     *
     * 采样点之间不必再问控制器，用单调时钟往前推就够准：播放中
     * `positionMs + 距上次采样的毫秒数` 就是当前进度，误差只剩时钟漂移。
     * 只在 `isPlaying` 时推 —— 缓冲/暂停时位置本来就不动，免得进度条自己跑。
     *
     * 上限 [DRIFT_MAX_MS] 纯属防御：万一 ticker 被主线程卡住（刚进页面做大列表
     * 布局、或切歌那一下），不至于让高亮一路飞到歌尾；超出就等下一次采样纠正。
     */
    fun positionNow(): Long {
        val c = controller ?: return positionMs
        if (!c.isPlaying) return positionMs
        val since = SystemClock.elapsedRealtime() - positionStamp
        // 采样太旧说明位置已经不是「刚量的」（缓冲、被主线程卡住、刚恢复上次进度）：
        // 这种情况不往外推，宁可先显示上一次的采样值，等下一次 tick 纠正。
        if (since !in 0..DRIFT_MAX_MS) return positionMs
        val at = positionMs + since
        return if (durationMs > 0) at.coerceAtMost(durationMs) else at
    }

    /**
     * 切换循环模式。
     *
     * 随机模式在**队列层**实现（重排 `queueSongs` 后整批换给播放器），而不是
     * 打开 `shuffleModeEnabled` —— 后者只改播放次序、队列不动，界面上播放列表
     * 与正在听的那首会对不上。重排规则见 [QueueState.toShuffle]：
     * 正在听的那首钉在首位，其余打乱。
     */
    fun setRepeat(mode: String) {
        val next = Playback.normalize(mode)
        val prev = repeatMode
        repeatMode = next
        controller?.let { Playback.applyRepeat(it, next) }

        val c = controller ?: return
        if (next == Playback.SHUFFLE && prev != Playback.SHUFFLE) {
            if (originalSongs.isEmpty()) originalSongs = queueSongs
            queueSongs = QueueState(songs = queueSongs, index = queueIndex, originalOrder = originalSongs)
                .toShuffle()
                .songs
            reorder()
        } else if (next != Playback.SHUFFLE && prev == Playback.SHUFFLE) {
            if (originalSongs.isNotEmpty()) {
                queueSongs = originalSongs
                reorder()
            }
        }
        c.playWhenReady = isPlaying || c.playWhenReady
        // 循环档也要存：随机模式的队列顺序是洗过的，只恢复顺序不恢复档位的话，
        // 队列页显示的和迷你条上那颗图标就对不上了。
        persist()
    }

    /**
     * 把队列顺序同步给播放器，**不打断正在响的那一首**。
     *
     * 用户 m07048 第①点：切循环档位时正在播的歌会短暂停顿。原因是原来这里走
     * `setMediaItems(items, idx, at)` + `prepare()` —— 整批换媒体项会把播放器
     * 打回 `STATE_IDLE`，正在解码的音频源被拆掉、缓冲清空，下一拍再从零 prepare，
     * 听感上就是「顿一下」。
     *
     * 现在改成用 `moveMediaItem` 原地重排：ExoPlayer 的 `moveMediaItems` 走的是
     * `createMaskingTimeline` + `maskTimelineAndPosition` + `moveMediaSources`，
     * 只改媒体源在时间轴上的顺序，**不重建播放源**，当前曲的解码器与缓冲都留着。
     * 因此循环档位切换对声音零影响，进度、缓冲、歌词位置都不用再兜。
     *
     * 实现方式是「插入排序」：从下标 0 开始逐个把目标项 move 到位。ExoPlayer 的
     * `moveMediaItems(from, to, newIndex)` 语义是「把 [from, to) 这段整体挪到
     * newIndex 前」，每次只挪一个元素时它就是一个稳定交换，累加起来正好等于把
     * 当前列表变成目标列表，且途中不会产生越界下标。
     */
    private fun reorder() {
        val c = controller ?: return
        val playing = currentSong
        val targetIds = queueSongs.map { it.id }
        // 播放器里现在的顺序（按 mediaId 认，mediaId 就是 songId 的字符串）。
        val currentIds = (0 until c.mediaItemCount).map { i ->
            c.getMediaItemAt(i).mediaId.toLongOrNull() ?: -1L
        }
        // 长度对不上（比如队列编辑与播放器不同步）就退回整批重建 —— 那种情况下
        // 顺序本来就对不齐，硬 move 只会挪出更乱的结果。
        if (currentIds.size != targetIds.size) {
            rebuildItems()
            return
        }
        // 原地重排：对每个目标位置，把目标 id 从它当前所在处挪过来。
        val working = currentIds.toMutableList()
        for (i in targetIds.indices) {
            if (working[i] == targetIds[i]) continue
            val from = working.indexOf(targetIds[i])
            // 目标 id 不在播放器里（队列刚换过、id 还没同步）：放弃原地重排。
            if (from < 0) { rebuildItems(); return }
            c.moveMediaItem(from, i)
            val moved = working.removeAt(from)
            working.add(i, moved)
        }
        // 正在听的那首如果被挪了位置，播放器自己会跟过去（masking timeline 会
        // 把当前 window 重新指到同一个 mediaId），这里只需要把界面状态刷新一遍。
        if (playing != null) {
            val idx = queueSongs.indexOfFirst { it.id == playing.id }
            if (idx >= 0) queueIndex = idx
        }
        syncQueue()
        syncCurrent()
    }

    /**
     * 整批重建媒体项（[reorder] 的后备路径）。
     *
     * 只有「播放器里的媒体项与 `queueSongs` 长度/内容对不上」时才走这里 ——
     * 那条路会打断播放，但它是异常路径（正常切循环不会命中）。
     */
    private fun rebuildItems() {
        val c = controller ?: return
        val playing = currentSong
        val items = queueSongs.map { placeholderItem(it) }
        val at = c.currentPosition.coerceAtLeast(0L)
        val idx = playing?.let { s -> queueSongs.indexOfFirst { it.id == s.id } } ?: -1
        // 切随机/还原顺序时用户可能正暂停，也可能在缓冲（isPlaying=false 但
        // playWhenReady=true）。只看 isPlaying 会把后一种当成暂停，播到一半自己停了。
        val keepPlaying = c.playWhenReady || c.isPlaying
        if (idx >= 0) c.setMediaItems(items, idx, at) else c.setMediaItems(items, 0, 0L)
        // playWhenReady 要在 prepare 之前摆好，免得暂停状态下 prepare 自己出声。
        c.playWhenReady = keepPlaying
        // 换队列会掉到 IDLE，必须再 prepare 一次；否则按播放键没反应。
        c.prepare()
        syncQueue()
        syncCurrent()
    }

    /** 队列原始顺序，用于退出随机时还原。 */
    private var originalSongs: List<Song> = emptyList()

    // ------------------------------------------------------------ 队列编辑
    /**
     * 从队列里移除一项（播放队列页的「⋮ → 从队列中移除」）。
     *
     * 移除的是正在播的那首时行为要讲究：它后面还有歌就接着播下一首，
     * 已经到底就退成上一首并暂停 —— 直接把「正在响的声音」抽掉、界面停在
     * 一首不存在的歌上是最难理解的状态。
     * 随机模式下同时从 [originalSongs] 里剔掉，否则退出随机时它又冒出来。
     */
    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in queueSongs.indices) return
        val wasCurrent = index == c.currentMediaItemIndex
        val at = c.currentPosition
        val wasPlaying = c.isPlaying
        val removed = queueSongs[index]
        queueSongs = queueSongs.filterIndexed { i, _ -> i != index }
        originalSongs = originalSongs.filterNot { it.id == removed.id }
        if (queueSongs.isEmpty()) {
            c.clearMediaItems()
            currentSong = null
            syncQueue()
            persist()
            return
        }
        val items = queueSongs.map { placeholderItem(it) }
        val nextIndex = when {
            !wasCurrent -> c.currentMediaItemIndex.coerceAtMost(queueSongs.lastIndex)
            index <= queueSongs.lastIndex -> index
            else -> (queueSongs.size - 1).coerceAtLeast(0)
        }
        c.setMediaItems(items, nextIndex, if (wasCurrent) 0L else at)
        c.prepare()
        // 换队列会掉到 IDLE，playWhenReady 也随之被清；按删之前的状态恢复。
        c.playWhenReady = wasPlaying
        syncQueue()
        syncCurrent()
        persist()
    }

    /** 清空队列（播放队列页的「清空」）。 */
    fun clearQueue() {
        val c = controller ?: return
        c.clearMediaItems()
        queueSongs = emptyList()
        originalSongs = emptyList()
        currentSong = null
        syncQueue()
        persist()
    }

    fun cycleRepeat() {
        setRepeat(Playback.nextRepeat(repeatMode))
    }

    fun tick() {
        val c = controller ?: return
        // 用户 m03577 第⑥点：切循环模式时进度条会短暂变灰。
        //
        // 根因不在界面：进出随机模式要整批换队列（[reorder]），`setMediaItems` +
        // `prepare()` 会让播放器掉回 IDLE，这一拍里 `currentPosition` 和
        // `bufferedPosition` 都读成 0。照抄进状态的话，进度条会先跳回开头、
        // 缓冲进度整条清空 —— 看上去就是「灰了一下」。
        // IDLE 只可能是「正在换队列」，跳过这一拍即可，下一次 tick 自然恢复。
        if (c.playbackState != Player.STATE_IDLE) {
            positionMs = c.currentPosition.coerceAtLeast(0L)
            bufferedMs = c.bufferedPosition.coerceAtLeast(0L)
            // 记下采样时刻，界面每帧要拿它把进度往当前时间推（见 positionNow）。
            positionStamp = SystemClock.elapsedRealtime()
        }
        if (durationMs <= 0) durationMs = c.duration.coerceAtLeast(0L)
        val song = currentSong ?: return
        val now = System.currentTimeMillis()
        if (now - lastSavedAt > 5000) {
            lastSavedAt = now
            onProgress?.invoke(song, positionMs)
        }
    }

    // ------------------------------------------------------------ 占位地址
    /**
     * 队列里每一项的地址都是 `placeholder:<songId>`。
     *
     * 换成真直链的时机刻意留在**数据源层**（[PlaceholderDataSourceFactory]），
     * 而不是在这里替换 `MediaItem`。原因：在这里替换只能覆盖「界面点歌」这条
     * 路径，而通知栏的下一首、蓝牙耳机按键是系统直接对服务发命令，完全不经过
     * 界面 —— 那样做的结果是「界面能放，一插耳机按下一首就报
     * `unknown protocol: placeholder`」。
     *
     * `quality` 非空时地址后面会带上 `?q=<档>&e=<代次>`，只有 [reloadCurrent]
     * 这么写：把「刚换的档」钉在**这一条**流上，别的歌照旧按设置走。
     */
    private fun placeholderItem(song: Song, quality: String? = null): MediaItem {
        val uri = if (quality == null) {
            "${PlaceholderDataSourceFactory.SCHEME}:${song.id}"
        } else {
            "${PlaceholderDataSourceFactory.SCHEME}:${song.id}?q=$quality&e=${TrackSource.epoch}"
        }
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(song.name)
                    .setArtist(song.artistText)
                    .setAlbumTitle(song.albumName)
                    .setArtworkUri(song.cover.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()
    }

    private fun syncQueue() {
        val c = controller ?: return
        queueSize = c.mediaItemCount
        queueIndex = c.currentMediaItemIndex
        // 队列页读的是这个 State，每次队列变动都同步一次（重排、加歌、删歌都走这里）。
        queue = queueSongs
    }

    private fun syncCurrent() {
        val c = controller ?: return
        queueIndex = c.currentMediaItemIndex
        currentSong = queueSongs.getOrNull(queueIndex)
        // 用户 m03577 第⑥点：切循环模式时进度条会短暂变灰。
        //
        // 这里原来无条件写 `durationMs = c.duration`。而 [reorder] 换完队列要
        // `prepare()`，那一拍播放器是 IDLE，`c.duration` 是 `C.TIME_UNSET`
        // （一个极大的负数），`coerceAtLeast(0)` 把它压成 0 —— 界面拿到的时长
        // 突然变成 0，进度条 `enabled = duration > 0` 直接变灰、右端时间显示
        // `--:--`，等 READY 回调回来才恢复。切一次循环灰一下，就是这么来的。
        // 时长未知时**保留上一次的值**，等 READY 再更新。
        val d = c.duration
        if (d > 0) durationMs = d
    }

    /** 冷启动恢复出来的歌没有队列，单独播一首。 */
    fun playSingle(song: Song, startAtMs: Long = 0L) {
        if (startAtMs > 0) seekOnReady(startAtMs)
        playList(listOf(song), 0)
    }

    /**
     * 冷启动恢复：把上次那首摆进播放条并停在上次的位置，**不出声**。
     *
     * 与 [playSingle] 的区别只有 `autoPlay = false`。放在这里而不是让调用方
     * 先 playSingle 再 pause：先播再暂停会有一段 200ms 的响声，
     * 半夜打开应用尤其明显。
     */
    fun restore(song: Song, startAtMs: Long) {
        whenReady {
            if (startAtMs > 0) seekOnReady(startAtMs)
            playList(listOf(song), 0, autoPlay = false)
        }
    }

    /**
     * 冷启动恢复**整个队列**（用户 m03123 第②点）。
     *
     * 与 [restore] 的区别：那个只摆一首，队列页打开是空的 —— 用户关一次应用，
     * 手动排好的播放列表就没了。这里把队列、当前序号、原始顺序、循环档、来源
     * 一起摆回去，界面上和关之前一模一样。
     *
     * 仍然 `autoPlay = false`：恢复队列不等于「打开应用就出声」，那是两件事。
     *
     * @param songs 当前队列顺序（随机模式下已经是洗过的顺序）
     * @param index 当前播到第几首
     * @param original 原始顺序，退出随机时还原用
     * @param atMs 上次的播放位置
     */
    fun restoreQueue(
        songs: List<Song>,
        index: Int,
        original: List<Song>,
        atMs: Long,
        contextId: Long = 0,
        contextName: String = "",
        repeat: String = Playback.REPEAT_ORDER,
    ) {
        if (songs.isEmpty()) return
        // 冷启动时 MediaController 还在连（几百毫秒），这里必须排队等连上再摆 ——
        // 否则整段恢复被 `controller ?: return` 一声不响地吞掉。
        whenReady { doRestoreQueue(songs, index, original, atMs, contextId, contextName, repeat) }
    }

    private fun doRestoreQueue(
        songs: List<Song>,
        index: Int,
        original: List<Song>,
        atMs: Long,
        contextId: Long,
        contextName: String,
        repeat: String,
    ) {
        val c = controller ?: return
        // 摆队列期间不落盘：中途每次 setMediaItems 都会触发切歌回调，
        // 那会把「摆到一半」的队列写回去（序号还停在 0）。
        suppressPersist = true
        try {
            // 存档里可能是老版本的 "list"（第四档），恢复时立刻折回三档，
            // 否则界面上又冒出「列表循环」（用户 m00001 第④点）。
            val mode = Playback.normalize(repeat)
            repeatMode = mode
            Playback.applyRepeat(c, mode)
            this.contextId = contextId
            this.contextName = contextName
            queueSongs = songs
            originalSongs = original.ifEmpty { songs }
            val at = index.coerceIn(0, songs.lastIndex)
            val items = songs.map { placeholderItem(it) }
            lastStartedId = 0L
            // seek 要在 prepare 之前挂上：STATE_READY 的回调里才取它，
            // prepare 之后再挂有可能晚于那一次回调（缓存命中时 READY 来得很快）。
            if (atMs > 0) seekOnReady(atMs)
            c.setMediaItems(items, at, 0L)
            c.prepare()
            c.playWhenReady = false
            syncQueue()
            syncCurrent()
        } finally {
            suppressPersist = false
        }
    }

    /** 当前队列的原始顺序（退出随机时还原用），落盘时要一起存。 */
    fun originalSnapshot(): List<Song> = originalSongs

    /** 清空落盘的队列（退出登录时用，免得把上一个账号的队列带过来）。 */
    fun clearPersistedQueue() {
        queueSongs = emptyList()
        originalSongs = emptyList()
    }

    // ------------------------------------------------------------ 恢复进度
    private var pendingSeekMs = 0L

    fun seekOnReady(ms: Long) { pendingSeekMs = ms }

    private fun restorePendingSeek() {
        if (pendingSeekMs <= 0) return
        val ms = pendingSeekMs
        pendingSeekMs = 0
        controller?.seekTo(ms)
        positionMs = ms
        positionStamp = SystemClock.elapsedRealtime()
    }

    /** 队列里当前这首歌在原始列表里的下标（随机播放时对不上，交给 UI 判断）。 */
    fun queueSongsSnapshot(): List<Song> = queueSongs

    companion object {
        /**
         * [positionNow] 允许往前推的最大毫秒数。
         *
         * ticker 是 500ms 一拍，正常只会用到 0~500；给到 2000 是留出主线程偶尔
         * 忙一下的余量。超过这个值就说明采样已经不新鲜了（暂停、缓冲、或进度
         * 刚被跳转重写），此时外推只会算出错误的位置。
         */
        private const val DRIFT_MAX_MS = 2000L
    }
}
