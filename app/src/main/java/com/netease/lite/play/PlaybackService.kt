package com.netease.lite.play

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 播放服务。
 *
 * 用户要求的四件事全部由这一个类 + Media3 的 `MediaSessionService` 承担，
 * 不需要我们写通知、不需要处理耳机按键：
 *
 *   1. **后台播放** —— 服务在播放期间是前台服务，App 切后台/锁屏继续放。
 *      系统会按 `MediaSession` 上的元数据自动生成前台通知。
 *   2. **通知栏控制**（播放/暂停/上一首/下一首/进度/封面）—— 通知由 Media3 的
 *      `DefaultMediaNotificationProvider` 生成，按钮与进度条来自 `Player` 的命令与
 *      `MediaMetadata`；封面取 `MediaMetadata.artworkUri`，所以下面每首歌都塞了封面。
 *   3. **锁屏控制** —— `MediaSession` 就是锁屏/系统媒体面板看到的会话，
 *      和前一条是同一份数据。
 *   4. **蓝牙耳机按键** —— `MediaSession` 注册 `MediaButtonReceiver`，
 *      单击播放/暂停、双击下一首由系统解析后转成 `Player` 命令。
 *
 * 换句话说，这些能力是「接对了 API 就白送」，我们的工作是**把元数据填全**
 * ——标题、歌手、时长、封面缺一项，通知和车机就会显示空白。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = Playback.newPlayer(this)

        // 用户 m48583 第⑤点：单曲队列切循环时通知栏按钮会错位。
        // 根因在 Media3 的默认 provider —— 它按「当前可用命令」过滤按钮，
        // 队列只剩一首歌时「下一首」会被整个拿掉，后面的按钮就往左补位。
        // 换成固定五颗的 provider，位置与播放状态彻底解耦。
        setMediaNotificationProvider(FixedNotificationProvider(this))

        // 只暴露通知上真正有意义的按钮。全部放开会让通知塞进七八个图标，
        // 在窄屏上被系统折叠成「更多」，反而不好按。
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            // 点通知/锁屏封面回到 App。没有这一条，点通知是没有任何反应的。
            .setSessionActivity(contentIntent(this, packageName))
            // 第⑫点：通知栏/锁屏除了播放暂停上下首，还要有「红心」和「循环模式」。
            // 这两颗是自定义命令（见 SessionCallback.onCustomCommand），图标随状态变
            // （空心/实心、列表/顺序/随机），所以每次状态变化都要重设一遍。
            .setMediaButtonPreferences(notificationButtons())
            .setCustomLayout(notificationButtons())
            .build()

        // 界面那边改了「有没有红心」「循环到了哪一档」之后会回调这里重画按钮。
        SessionUiState.onChanged = { refreshNotificationButtons() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // ------------------------------------------------------------ 通知去抖
    /** 上一次真正发出去的通知「内容指纹」，见 [onUpdateNotificationAsync]。 */
    private var lastNotificationKey: String? = null

    /** 服务当前是不是已经在前台了（由我们放过的那次 `fg=true` 决定）。 */
    private var foregroundEstablished = false

    /**
     * 用户 m02429 第③点：点歌词/拖进度条跳转时，通知栏会整张刷新一下。
     *
     * 根因不在我们的代码里（[refreshNotificationButtons] 只在红心与循环档位变化时调，
     * `AppViewModel.seekTo` 一路只转发给 `PlayerController.seekTo`），而在 Media3：
     * `MediaNotificationManager$MediaControllerListener.onEvents` 对
     *
     * ```
     * {EVENT_TIMELINE_CHANGED(0), EVENT_POSITION_DISCONTINUITY(4),
     *  EVENT_AVAILABLE_COMMANDS_CHANGED(5), EVENT_MEDIA_METADATA_CHANGED(14)}
     * ```
     *
     * 做 `containsAny`，命中就调 `onUpdateNotificationInternal(session, false)` 重建整条
     * 通知。而 seek 必然产生 `EVENT_POSITION_DISCONTINUITY` —— 于是每跳一次进度就重发
     * 一遍通知：卡片闪一下、五颗按钮重播一遍入场动画。
     *
     * 可跳进度时通知上**没有任何东西真的变了**：进度条是系统按 `PlaybackState` 自己
     * 同步的（Media3 建通知时并不写 `setProgress`，只写 `setWhen/setUsesChronometer`
     * 当计时器初值）。所以在应用侧记一份指纹，指纹没变就把这次重建吃掉，让歌词跳转
     * 只影响播放本身。
     *
     * **拦在 `onUpdateNotificationAsync` 而不是 2 参 `onUpdateNotification` 上。**
     * 后者只是被前者调用的一环，而「到底要不要发通知」由前者看私有的
     * `defaultMethodCalled` 决定 —— 那个标志一旦被置位就再不复位，所以在 2 参版本里
     * `return` 拦不住重建。`onUpdateNotificationAsync` 是 public 且不是 final，是唯一
     * 能把整次重建吃掉的位置。
     *
     * 传进来的 `startInForegroundRequired` 其实是
     * `MediaNotificationManager.shouldRunInForeground(...)` 的结果（播放中恒为 true），
     * 不是 `onUpdateNotificationInternal` 的那个实参；所以判据不能只看它，还要看
     * [foregroundEstablished] —— 只有服务真的已经在前台、内容又没变，跳过才安全。
     * 「切成前台服务」那一次（`foregroundEstablished == false`）永不跳过：漏掉它通知
     * 就再也发不出来，还可能撞上 `ForegroundServiceDidNotStartInTimeException`。
     */
    @Suppress("UNCHECKED_CAST")
    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ): ListenableFuture<Void> {
        val key = notificationKey(session)
        val skip = key == lastNotificationKey &&
            (!startInForegroundRequired || foregroundEstablished)
        // Guava 用 JSpecify 把 `Void` 标成可空，这里收窄回 Java 签名上的
        // `ListenableFuture<Void>`。跳过时给一个「已完成、无异常」的 future，等价于
        // Media3 内部 `defaultMethodCalled == false` 的那条早退分支。
        if (skip) return Futures.immediateVoidFuture() as ListenableFuture<Void>
        lastNotificationKey = key
        foregroundEstablished = startInForegroundRequired
        return super.onUpdateNotificationAsync(session, startInForegroundRequired)
    }

    /**
     * 通知长相的指纹 —— 只列**真的画在通知上**的东西。
     *
     * 这份清单是照着 [FixedNotificationProvider.getMediaButtons] 与 Media3
     * `DefaultMediaNotificationProvider.createNotificationInternal` 的输入逐项对出来的：
     *
     *  - `mediaId` / `currentMediaItemIndex` / `mediaItemCount` —— 是哪首歌、在队列哪一位；
     *  - `mediaMetadata.title` / `.artist` / `artworkUri` —— 通知的标题、正文、大图；
     *  - `SessionUiState.liked` —— 红心图标与它的 `displayName`；
     *  - `SessionUiState.repeatMode` —— 循环图标与它的 `displayName`；
     *  - `playWhenReady` —— 播放/暂停那个图标。我们的 provider 读的是 `isPlaying`，
     *    但**判据必须用 `playWhenReady`**：seek 到没缓冲的位置时 `isPlaying` 会短暂
     *    变 false，拿它当指纹等于每次跳转都重建，正好是这次要治的毛病；
     *  - `playbackState == STATE_ENDED` —— 播完之后 `playWhenReady` 仍是 true，
     *    得靠这一位把图标从「暂停」翻回「播放」；
     *  - `availableCommands` —— 按钮可用性（系统按它决定置不置灰）。
     *
     * **故意不含**播放位置、`playbackState` 的 BUFFERING/READY 抖动、player 自己的
     * `repeatMode` / `shuffleModeEnabled`（循环图标看的是 [SessionUiState.repeatMode]）、
     * 以及「这次是不是前台请求」—— 最后一项是 [foregroundEstablished] 该管的事，
     * 混进指纹会让 `fg=false` 那次永远对不上上一份指纹。
     */
    private fun notificationKey(session: MediaSession): String {
        val p = session.player
        val item = p.currentMediaItem
        return buildString {
            append(item?.mediaId).append('|')
            append(p.currentMediaItemIndex).append('/').append(p.mediaItemCount).append('|')
            append(p.mediaMetadata.title).append('|')
            append(p.mediaMetadata.artist).append('|')
            append(item?.mediaMetadata?.artworkUri).append('|')
            append(SessionUiState.liked).append('|')
            append(SessionUiState.repeatMode).append('|')
            append(p.playWhenReady).append('|')
            append(p.playbackState == Player.STATE_ENDED).append('|')
            append(p.availableCommands.hashCode())
        }
    }

    // ------------------------------------------------------------ 通知栏按钮
    /**
     * 通知栏/锁屏上的两颗自定义按钮：红心与循环模式。
     *
     * 上一首/下一首走的是 **player 命令**（`COMMAND_SEEK_TO_PREVIOUS/NEXT`），
     * 由 Media3 自己按槽位排布。红心和循环用 **自定义 session 命令**：系统没有
     * 「喜欢」这个概念的等价命令，循环模式那条 `COMMAND_SET_REPEAT_MODE` 只有
     * 列表/单曲两档，装不下我们的三档（随机的实现是重排队列，见
     * [Playback.applyRepeat]）。
     *
     * ## 为什么必须显式给槽位（用户 m48583 第⑤点）
     *
     * `CommandButton.Builder(icon)` 不给 slots 时，Media3 会按**图标常量**推断
     * 默认槽位（`CommandButton.getDefaultSlot`）：红心这类不认识的图标落到
     * `SLOT_OVERFLOW`，而循环/随机那几个图标常量在它那张表里被认成了
     * 「上一首/下一首」所在的槽位。于是循环档位一切换（图标换了 → 推断出的槽位
     * 也换了），按钮就会在通知栏里换位置 —— 队列只有一首歌时上一首/下一首不可用、
     * 位置本来就在收拢，这一跳就成了肉眼可见的错位。
     *
     * 修法是把两颗按钮**钉死在同一个固定槽位**（`SLOT_OVERFLOW`，即系统媒体卡片
     * 右侧那块「更多」区）：槽位不再随图标变，位置就永远不变。不用
     * `SLOT_BACK_SECONDARY / SLOT_FORWARD_SECONDARY` —— 在这台 Android 11 上
     * 实测那两个副槽位不会被渲染，通知只剩三颗系统按钮，自定义按钮直接消失
     * （`dumpsys notification` 的 `actions` 从 5 掉回 3）。
     */
    private fun notificationButtons(): List<CommandButton> {
        val liked = SessionUiState.liked
        val heart = CommandButton.Builder(
            if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        )
            .setSessionCommand(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
            .setDisplayName(if (liked) "取消喜欢" else "喜欢")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val repeat = CommandButton.Builder(
            when (Playback.normalize(SessionUiState.repeatMode)) {
                Playback.SHUFFLE -> CommandButton.ICON_SHUFFLE_ON
                Playback.REPEAT_ONE -> CommandButton.ICON_REPEAT_ONE
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setSessionCommand(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
            .setDisplayName(Playback.repeatLabel(SessionUiState.repeatMode))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        return listOf(heart, repeat)
    }

    /**
     * 状态变了就重画。`setCustomLayout`/`setMediaButtonPreferences` 会触发 Media3
     * 重建前台通知，所以这里不需要自己碰 NotificationCompat。
     */
    private fun refreshNotificationButtons() {
        val s = session ?: return
        val buttons = notificationButtons()
        s.setCustomLayout(buttons)
        s.setMediaButtonPreferences(buttons)
    }

    /**
     * 用户从最近任务里划掉 App。**不停止播放** —— 这是音乐播放器的常识，
     * 划掉界面不该把正在听的歌掐了。真要停，用户会在通知上按暂停。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        // 指纹与前台标记都跟着服务实例走：服务被销毁后重建，第一次更新不能被上一轮的
        // 记录吃掉，前台也得重新走一次 startForeground。
        lastNotificationKey = null
        foregroundEstablished = false
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSession.Callback {
        /**
         * 告诉系统「这个会话支持哪些命令」。少写一条，对应的按钮就会在通知/锁屏上
         * 灰掉或不出现 —— 比如漏了 `COMMAND_SEEK_TO_NEXT`，蓝牙双击就无效。
         *
         * 用无参构造 `AcceptedResultBuilder()`（带 MediaSession 的那个已废弃），
         * 并顺手把 `sessionActivity` 设成「点通知回到 App」。
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // 会话命令（由系统面板/通知发起的那一类）。默认集合够用，但**自定义命令
            // 必须显式加进去** —— 没加的话通知上那两颗按钮点下去，系统侧就被拦掉，
            // 连 onCustomCommand 都不会回调（日志里只有一行 "unexpected custom command"）。
            // 用 `DEFAULT_...buildUpon()` 而不是 `SessionCommands.Builder()`：后者那两个
            // `addAllSessionCommands/addAllLibraryCommands` 是包内可见的，从应用代码里调不到。
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_TOGGLE_LIKE, Bundle.EMPTY))
                .add(SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            // 关键：**player 命令必须显式给全**。
            //
            // 只写 setAvailableSessionCommands 时，控制器侧拿到的 player 命令是空的 ——
            // 症状是 MediaController 上 setMediaItems/playWhenReady 全部被静默忽略，
            // 日志里只有一行 "Calling play() omitted due to COMMAND_PLAY_PAUSE not being
            // available."，然后通知栏空白、锁屏没有会话、蓝牙按键不响应。
            // 一开始漏了这一段，界面能画、歌却永远不播。
            val playerCommands = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_GET_VOLUME,
                    Player.COMMAND_SET_VOLUME,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_RELEASE,
                )
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder()
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setSessionActivity(contentIntent(this@PlaybackService, packageName))
                .build()
        }

        /**
         * 用户点了通知栏/锁屏上的红心或循环按钮。
         *
         * 这里**不自己做业务**，只把动作转交给界面（[SessionUiState]）并立刻回
         * `SessionResult(RESULT_SUCCESS)`：红心要发网络请求、循环要重排整个队列，
         * 都在 ViewModel 里；而且在通知上按一下等两秒才有反馈是很糟的体验 ——
         * 界面那边会先乐观地改图标再发请求。
         *
         * 界面没起来时（只有服务在跑）回调是 null，这时退回「能力范围内」的处理：
         * 循环按钮在 顺序 / 单曲循环 / 整队循环 之间切，红心什么都不做。
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_TOGGLE_LIKE -> {
                    val cb = SessionUiState.onLikeRequested
                    if (cb != null) {
                        cb()
                    } else {
                        // 没有界面时也要给出可感知的反馈，至少把图标翻过来。
                        SessionUiState.update(!SessionUiState.liked, SessionUiState.repeatMode)
                    }
                }
                CMD_CYCLE_REPEAT -> {
                    val cb = SessionUiState.onRepeatRequested
                    if (cb != null) {
                        cb()
                    } else {
                        // 没有界面时的降级：只在三档之间切，且只动 `Player.repeatMode`。
                        // 随机要重排队列，服务这边做不到（队列语义在
                        // PlayerController 里），但 `applyRepeat` 把「随机」映射成
                        // `REPEAT_MODE_ALL`（随机本来就该放完再从头来），所以
                        // ALL 这一档在通知栏上**就是随机**，这样三档一轮回仍然成立
                        // （用户 m00001 第④点：只有顺序 / 单曲循环 / 随机）。
                        val next = when (player.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                            else -> Player.REPEAT_MODE_OFF
                        }
                        player.repeatMode = next
                        SessionUiState.update(
                            SessionUiState.liked,
                            when (next) {
                                Player.REPEAT_MODE_OFF -> Playback.REPEAT_ORDER
                                Player.REPEAT_MODE_ONE -> Playback.REPEAT_ONE
                                // 第三档是随机，不是「列表循环」——常量已经删了。
                                else -> Playback.SHUFFLE
                            },
                        )
                    }
                }
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        /** 通知栏「喜欢 / 取消喜欢」。 */
        const val CMD_TOGGLE_LIKE = "com.netease.lite.TOGGLE_LIKE"

        /** 通知栏「循环模式」（顺序 → 单曲循环 → 随机，用户 m03577 第⑥点）。 */
        const val CMD_CYCLE_REPEAT = "com.netease.lite.CYCLE_REPEAT"

        /**
         * 「点通知回到 App」的 PendingIntent。
         *
         * 从 launcher intent 派生而不是自己 new 一个 Activity intent：这样它带着
         * 正确的 task affinity，点通知是**回到原来的任务**而不是新开一个界面。
         */
        fun contentIntent(context: android.content.Context, packageName: String): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent().setClassName(packageName, "$packageName.MainActivity")
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * 把一首歌变成 MediaItem，元数据填全 —— 通知栏 / 锁屏 / 车机全靠它。
         *
         * 封面刻意给 `artworkUri`（http 地址）而不是 `artworkData`（字节数组）或
         * Bitmap：后两者都要跟着元数据走一遍 MediaSession 的 Binder，一张 300KB 的
         * 封面足以让事务超限，而且每切一次歌就复制一份。给 URL 的话是 Media3 自己
         * 在服务进程里下载并缓存，界面和通知各取各的。
         */
        fun mediaItemFor(song: com.netease.lite.model.Song, uri: String): MediaItem {
            val meta = MediaMetadata.Builder()
                .setTitle(song.name)
                .setArtist(song.artistText)
                .setAlbumTitle(song.albumName)
                .setArtworkUri(song.cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
            return MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(uri)
                .setMediaMetadata(meta)
                .build()
        }
    }
}
