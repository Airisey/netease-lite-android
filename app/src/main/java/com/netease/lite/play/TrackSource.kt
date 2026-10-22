package com.netease.lite.play

import com.netease.lite.model.SongUrl

/**
 * 取流的**唯一入口**。
 *
 * ## 为什么需要这么一个中转站
 *
 * 「取这首歌的播放地址」这件事原本写在 `AppViewModel.resolve()` 里，只有界面
 * 点歌时会走。但真正能触发播放的有三条路径：
 *
 *   1. 界面点一首歌
 *   2. 通知栏 / 锁屏按下一首
 *   3. 蓝牙耳机按键
 *
 * 后两条是系统直接对**服务进程**里的 `Player` 发命令，压根不经过 ViewModel ——
 * 于是「界面能放、耳机上一按下一首就报错」。这里把取流逻辑挂成一个可由
 * ViewModel 注册的全局函数，`PlaceholderDataSource` 在打开地址时调用它，
 * 三条路径就自动合并成一条。
 *
 * 注册方式：[install]。没有注册时（例如服务先于界面起来）退化成直接问接口，
 * 只是少掉「本地优先」和「按档位回退」两个优化，不影响能不能播。
 */
object TrackSource {

    /**
     * 由 `AppViewModel` 注入。参数是歌曲 id（`DataSource` 手上只有 id，
     * 拿不到完整的 `Song`）与**这一条流指定的音质档**（`null` = 按设置里
     * 当前的档走），返回统一形状的 [SongUrl]。
     *
     * 为什么要带音质：用户在播放页切档后要把当前这首重新加载成新档位，
     * 如果占位地址里不写档位、数据源层又只会读 `Prefs.quality`，那么「切档」
     * 与「没切档」发出去的是同一个地址（用户 m09508 第②点：标签变了、声音没变）。
     */
    @Volatile
    var resolveById: (suspend (Long, String?) -> SongUrl)? = null

    fun install(fn: suspend (Long, String?) -> SongUrl) {
        resolveById = fn
    }

    /**
     * 音质「代次」。每次用户在播放页换档就 +1。
     *
     * 换档后要把当前这首重新加载成新档位，而队列里其他歌不该被这次换档连累 ——
     * 所以重新加载的那一项在占位地址里带上 `?q=<档>&e=<代次>`：数据源层只有当
     * `e` 等于当前代次时才采用那个档位（并跳过本地文件），代次一变，旧地址上的
     * 档位就自动作废、回到「按设置里当前的档走」。不这样做的话，换过一次档的
     * 那首歌会把旧档位钉死在自己的 MediaItem 上，之后从队列回到它仍是旧音质。
     */
    @Volatile
    var epoch: Int = 0

    suspend fun resolve(id: Long, fallbackQuality: String, qualityOverride: String? = null): SongUrl =
        resolveById?.invoke(id, qualityOverride)
            ?: com.netease.lite.api.Endpoints.resolveUrl(id, qualityOverride ?: fallbackQuality)
}
