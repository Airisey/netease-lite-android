package com.netease.lite.play

/**
 * 通知栏 / 锁屏那两颗自定义按钮（红心、循环模式）需要的状态中转站。
 *
 * ## 为什么需要一个进程级单例
 *
 * 通知栏的按钮由 [PlaybackService]**自己**画 —— 它在服务进程里跑，手上的
 * `Player` 只有 `repeatMode` 这个三值（OFF / ONE / ALL），既不知道「随机播放」这一档
 * （我们的随机是重排队列，不走 `shuffleModeEnabled`，见 [Playback.applyRepeat]），
 * 也不知道当前这首有没有被红心。而这两件事的状态都在界面的 ViewModel 里。
 *
 * 服务和界面在**同一个进程**，所以最直接的办法是放一个双方都能摸到的对象：
 * 界面往里写，服务读到之后重画按钮。反过来，用户在通知上点了按钮，服务通过
 * [onLikeRequested] / [onRepeatRequested] 把动作交回界面 —— 因为「喜欢」要发
 * 网络请求还要改侧栏计数，「切循环」要重排整个队列，这些逻辑本来就在 ViewModel，
 * 在服务里再写一份必然两处不一致。
 *
 * 代价是：界面进程没起来（比如只从系统媒体面板恢复服务）时这两个回调是 null，
 * 循环按钮退化成只切 `Player.repeatMode`（顺序 ↔ 单曲循环 ↔ 随机），红心不做任何事。
 * 这是有意的降级：那时连歌单都还没加载，红心点了也无法体现在任何地方。
 */
object SessionUiState {

    @Volatile
    var liked: Boolean = false
        private set

    /**
     * 当前循环档（[Playback.REPEAT_ORDER] / [Playback.REPEAT_ONE] / [Playback.SHUFFLE]）。
     *
     * 用户 m00001 第④点：只留三种。初值也必须落在三档里 —— 原来写的是
     * [Playback.REPEAT_LIST]，通知栏那颗按钮在界面起来之前会画成第四档。
     */
    @Volatile
    var repeatMode: String = Playback.REPEAT_ORDER
        private set

    /** 状态变了 → 服务重画按钮（图标的实心/空心、循环/随机/单曲）。 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** 用户点了通知上的红心。 */
    @Volatile
    var onLikeRequested: (() -> Unit)? = null

    /** 用户点了通知上的循环模式按钮。 */
    @Volatile
    var onRepeatRequested: (() -> Unit)? = null

    fun update(newLiked: Boolean, newRepeat: String) {
        // 折一次再比：来路不明的档位（老存档的 "list"、服务降级路径算错的值）
        // 一律落到三档之一，别让它从通知栏渗进界面。
        val mode = Playback.normalize(newRepeat)
        if (newLiked == liked && mode == repeatMode) return
        liked = newLiked
        repeatMode = mode
        onChanged?.invoke()
    }

    fun clear() {
        onChanged = null
        onLikeRequested = null
        onRepeatRequested = null
    }
}
