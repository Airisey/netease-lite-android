package com.netease.lite.play

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.netease.lite.data.Prefs
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * 把 `placeholder:<songId>` 换成真实播放地址的数据源工厂。
 *
 * ## 为什么要在数据源这一层做
 *
 * 网易云的直链有时效，队列里除正在播的那首以外都只放一个占位地址，等真要播
 * 的时候才去取流。问题是「什么时候要播」有**三条入口**：
 *
 *   1. 界面点一首歌（走 `MediaController`）
 *   2. 通知栏 / 锁屏按下一首
 *   3. 蓝牙耳机按键
 *
 * 第 2、3 条**完全不经过界面**，是系统直接对服务里的 `Player` 发命令。所以
 * 「在界面里取流」这个思路注定漏掉一半入口 —— 插上耳机按下一首就报
 * `MalformedURLException: unknown protocol: placeholder`。
 *
 * 把取流放到数据源，不管命令从哪来，ExoPlayer 最终都要经过它去打开地址，
 * 占位符就在这一刻被换掉。一个入口，三条路径全覆盖。
 *
 * 底层直接用 Media3 自带的 [ResolvingDataSource]（它就是为这种「播放时才解析
 * 真实地址」的场景写的），比自己实现 `DataSource` 少一份要维护的转发代码。
 *
 * ## 为什么同步等待不算问题
 *
 * `resolveDataSpec` 跑在 ExoPlayer 的加载线程上，不在主线程，所以这里可以
 * `runBlocking`。反过来（先异步取流、再替换 `MediaItem`）会有一个必然的竞态：
 * `prepare()` 和替换是两条线，谁先到不确定，先到的那次一定失败。
 */
@UnstableApi
class PlaceholderDataSourceFactory(private val context: Context) : DataSource.Factory {

    /**
     * 真正干活的上游数据源。
     *
     * 用 [DefaultDataSource] 而不是直接 `DefaultHttpDataSource`：本地索引里存的是
     * SAF 的 `content://` 地址，http 数据源打不开它，会把「文件不存在」报成
     * `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` —— 用户看到的就是「网络连接失败」，
     * 可歌明明在手机上。`DefaultDataSource` 会按 scheme 自动分派（file/content/
     * asset 走本地，http(s) 走网络）。
     *
     * 网络那一支仍然保留网易云 CDN 需要的两个设置：跨协议重定向（它经常先回
     * 一个 http 的 302 再跳到 https）与超时。
     */
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setAllowCrossProtocolRedirects(true)

    private val upstream: DataSource.Factory =
        DefaultDataSource.Factory(context, httpFactory)

    private val resolver = ResolvingDataSource.Resolver { spec ->
        val uri = spec.uri
        if (uri.scheme != SCHEME) {
            spec
        } else {
            // 地址形如 `placeholder:<songId>` 或 `placeholder:<songId>?q=<档>&e=<代次>`。
            // 后者只由 `PlayerController.reloadCurrent()` 写，用来把「刚换的音质档」
            // 绑到这一条流上（用户 m09508 第②点：只改设置里的档，数据源层读到的
            // 还是老档，声音自然没变）。查询串要自己拆 —— 不透明的 URI 上
            // `uri.query` 拿不到东西。
            val raw = uri.schemeSpecificPart ?: ""
            val cut = raw.indexOf('?')
            val id = (if (cut >= 0) raw.substring(0, cut) else raw).toLongOrNull() ?: 0L
            if (id == 0L) throw IOException("播放地址占位符不合法：$uri")
            // 代次对不上就当作没指定档：那条 MediaItem 是上一轮换档留下的，
            // 用户后来又换过档，此时应该按新档走而不是继续钉在旧档上。
            val override = if (cut >= 0) {
                val q = paramOf(raw.substring(cut + 1), "q")
                val e = paramOf(raw.substring(cut + 1), "e")?.toIntOrNull()
                if (q != null && e != null && e == TrackSource.epoch) q else null
            } else {
                null
            }
            val r = runBlocking {
                runCatching { TrackSource.resolve(id, Prefs(context).quality, override) }.getOrNull()
            }
            val url = r?.url
            if (url.isNullOrEmpty()) {
                // 抛 IOException 而不是自己弹提示：ExoPlayer 会把它包成
                // PlaybackException 交给 PlayerController 的 onPlayerError，
                // 那里统一决定「提示什么」和「要不要跳下一首」。
                throw IOException(r?.message ?: "取不到播放地址")
            }
            // 只换地址，位置/长度/自定义头全部照旧 —— 用 withUri 而不是重建 DataSpec，
            // seek 过的位置（`spec.position`）也就跟着保留下来了。
            spec.withUri(Uri.parse(url))
        }
    }

    override fun createDataSource(): DataSource =
        ResolvingDataSource(upstream.createDataSource(), resolver)

    companion object {
        /**
         * 占位地址的协议名。`placeholder:12345` 里的 `12345` 是歌曲 id。
         *
         * 特意用一个不存在的协议：真地址一定是 http(s)，不会撞上；
         * 而且万一解析漏了，报错信息里会明写 `unknown protocol: placeholder`，
         * 一眼能看出是「占位没被替换」而不是「CDN 挂了」。
         */
        const val SCHEME = "placeholder"

        /** 从 `a=1&b=2` 这种串里取一个参数；`Uri.getQueryParameter` 在不透明 URI 上不可用。 */
        private fun paramOf(query: String, key: String): String? =
            query.split('&').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")?.ifEmpty { null }
    }
}
