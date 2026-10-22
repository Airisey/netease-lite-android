package com.netease.lite.play

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * 播放内核的组装与「音频焦点」策略。
 *
 * 用户要求（m38831）：「音频焦点必须处理来电、通知、其他应用播放时的暂停/降低音量」。
 * 这一条**不需要我们写代码**——ExoPlayer 自带：
 *
 *   - `setAudioAttributes(attrs, /* handleAudioFocus = */ true)`
 *     之后 ExoPlayer 会注册 AudioFocusRequest。来电 / 别的播放器抢焦点时它自己暂停，
 *     恢复焦点时自己继续；对方要求 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 时它自己压低音量。
 *   - 「暂停还是压低」由**对方**的请求类型决定，我们再插手反而会打架。
 *
 * 所以这里只做两件事：给对属性（让系统知道我们是音乐、支持 ducking），
 * 以及开启「播完自动下一首」的语义（由 queue 层管，这里只声明）。
 */
@UnstableApi
object Playback {

    /**
     * 音乐类音频属性 + 允许 ducking。
     *
     * `setWillPauseWhenDucked(false)` 与 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 配套：
     * 导航播报那种「压低一下」的场景我们继续放着，只是音量被系统压低，
     * 播报结束自动恢复；真正的独占请求（来电、别的音乐 App）才会让我们暂停。
     */
    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
        .build()

    /**
     * 建 ExoPlayer。
     *
     * `setHandleAudioBecomingNoisy(true)` 解决「拔耳机自动暂停」——用户没提，
     * 但这是音乐播放器的默认预期，不实现会被当成 bug。
     *
     * 关键是 `setMediaSourceFactory` 里装的 [PlaceholderDataSourceFactory]：
     * 队列里的占位地址（`placeholder:<songId>`）在那里被换成真实直链。放在
     * 数据源层而不是界面层，是因为通知栏和蓝牙按键根本不经过界面 —— 详见
     * [PlaceholderDataSource] 的注释。
     */
    @UnstableApi
    fun newPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(PlaceholderDataSourceFactory(context)),
        )
        .setAudioAttributes(audioAttributes(), /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /**
     * 播放模式映射：桌面版的 REPEAT 三态 ↔ Media3 的 repeatMode + shuffleModeEnabled。
     *
     * 注意桌面版 m36690 第③点的语义：「随机播放时正在听的那首固定排到队列首位」。
     * Media3 的 shuffle 是**播放顺序**上的乱序，队列本身不动，所以那条语义要在
     * 队列层自己实现（见 [QueueState]），不能靠 `shuffleModeEnabled`。
     */
    /**
     * 把三态循环映射到 ExoPlayer。
     *
     * **不使用 `shuffleModeEnabled`** —— 桌面版 m36690 第③点的语义是
     * 「随机播放时正在听的那首固定排到队列首位，其余打乱」，这是**队列顺序**
     * 的改变（用户能在播放列表里看见），而 `shuffleModeEnabled` 只是打乱
     * 播放次序、队列本身不动，界面上看不出区别，还会出现「列表里第一首
     * 和正在听的不是同一首」。所以真正的乱序交给 [PlayerController] 重排队列，
     * 这里只负责顺序 / 列表两档（随机的队列形态由外层决定）。
     */
    fun applyRepeat(player: Player, mode: String) {
        player.shuffleModeEnabled = false
        player.repeatMode = when (normalize(mode)) {
            // 用户 m03577 第⑥点：「单曲循环模式图标错误，且单曲循环模式不起作用」。
            // 上一轮按 m48583 第④点砍到三档时，留下的是「顺序 / 列表循环 / 随机」，
            // 单曲循环被当成「多加的一站」删掉了 —— 但用户要的三种本来就是
            // 「顺序 / 单曲循环 / 随机」（这一轮已确认）。现在把 ALL 换成 ONE：
            // 单曲循环必须是播放器真的单曲重来，而不是把整个队列循环一遍。
            REPEAT_ORDER -> Player.REPEAT_MODE_OFF
            REPEAT_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_ALL
        }
    }

    /**
     * 列表循环（整队循环）——用户 m00001 第④点之后它**不再是循环里的一档**。
     *
     * 这个常量现在只剩一个用途：认得出老存档 / 老代码写进去的 `"list"`，
     * 好把它折回三档（见 [normalize]）。除此之外任何地方都不该再写它。
     */
    const val REPEAT_LIST = "list"
    const val SHUFFLE = "shuffle"

    /** 顺序播放：播完最后一首就停，不回头。 */
    const val REPEAT_ORDER = "order"

    /** 单曲循环：同一首反复播（`Player.REPEAT_MODE_ONE`）。 */
    const val REPEAT_ONE = "one"

    /**
     * 把任何来路不明的档位折回三档之一。
     *
     * 用户 m00001 第④点：「现在有四种循环模式，请保留我要求的三种」。
     * 第四档（「列表循环」）上一轮就已经从循环里删掉了，但它**还活在很多默认值里**：
     * `Prefs.queueRepeat` 的缺省 `"list"`、`PlayerController.repeatMode` 的初值、
     * `QueueState.repeat` 的初值、`SessionUiState.repeatMode` 的初值 ——
     * 于是全新安装、或恢复一条老队列之后，界面拿到的是 `"list"`，
     * `repeatLabel("list")` 落到 `else` 打出「列表循环」，用户就看见了第四档。
     *
     * 所以修法不是挨个改默认值（漏一个就复发），而是**只在三个入口认三档**，
     * 其余一律折叠。四个入口：读存档、恢复队列、`setRepeat`、`repeatLabel`/`applyRepeat`。
     */
    fun normalize(mode: String): String = when (mode) {
        REPEAT_ORDER, REPEAT_ONE, SHUFFLE -> mode
        else -> REPEAT_ORDER
    }

    /**
     * 循环顺序：顺序 → 单曲循环 → 随机 → 顺序（正好三种，用户 m03577 第⑥点）。
     *
     * 三档一轮回的意义在于「再点两下就回到出发点」。用户这一轮明确要的三档是
     * 「顺序 / 单曲循环 / 随机」——「列表循环」被换掉了：整队循环与随机播放
     * 在听感上几乎分不开（都停不下来），而单曲循环是另一个明确的用法。
     */
    fun nextRepeat(mode: String): String = when (normalize(mode)) {
        REPEAT_ORDER -> REPEAT_ONE
        REPEAT_ONE -> SHUFFLE
        else -> REPEAT_ORDER
    }

    fun repeatLabel(mode: String): String = when (normalize(mode)) {
        REPEAT_ORDER -> "顺序播放"
        REPEAT_ONE -> "单曲循环"
        else -> "随机播放"
    }
}

/**
 * 队列 + 播放模式的状态容器（`netease-lite\renderer\js\player.js` 的队列语义移植）。
 *
 * 只放**纯逻辑**：不放 audio、不放协程，方便单测，也让 Service 与界面各持一份镜像。
 */
data class QueueState(
    val songs: List<com.netease.lite.model.Song> = emptyList(),
    val index: Int = -1,
    val repeat: String = Playback.REPEAT_ORDER,
    /** 进入随机模式前的原始顺序，退出随机时还原。 */
    val originalOrder: List<com.netease.lite.model.Song> = emptyList(),
    val contextId: Long = 0,
    val contextName: String = "",
) {
    val current: com.netease.lite.model.Song? get() = if (index in songs.indices) songs[index] else null

    /**
     * 「顺序播放」时播到最后一首就没有下一首了；另外两档都还能往下走。
     *
     * 这里原来比的是 `repeat != REPEAT_LIST` —— 拿一个已经废弃的档位当哨兵，
     * 于是三档里只剩「列表循环」被当成能续播，另外两档全被判成末尾。
     */
    val hasNext: Boolean get() = songs.isNotEmpty() && (index < songs.size - 1 || repeat != Playback.REPEAT_ORDER)

    /**
     * 进入随机模式：**正在听的那首钉在首位**，其余打乱。
     *
     * 这正是桌面版 m36690 第③点修的那个 bug —— 原来的实现连当前曲一起洗，
     * 于是「随机播放」一按，正在听的歌就被换掉了。
     */
    fun toShuffle(): QueueState {
        val keep = current ?: return this.copy(repeat = Playback.SHUFFLE)
        val rest = songs.filter { it.id != keep.id }.shuffled()
        return copy(
            songs = listOf(keep) + rest,
            index = 0,
            repeat = Playback.SHUFFLE,
            originalOrder = if (originalOrder.isEmpty()) songs else originalOrder,
        )
    }

    /** 退出随机：还原进入前的顺序，并把指针移回正在听的那首。 */
    fun fromShuffle(): QueueState {
        val keep = current ?: return this.copy(repeat = Playback.REPEAT_ORDER, originalOrder = emptyList())
        val restored = if (originalOrder.isNotEmpty()) originalOrder else songs
        val at = restored.indexOfFirst { it.id == keep.id }
        return copy(
            songs = restored,
            index = if (at >= 0) at else 0,
            repeat = Playback.REPEAT_ORDER,
            originalOrder = emptyList(),
        )
    }
}
