package com.netease.lite.play

import android.content.Context
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地文件的可用性判定 + 「这首本地放不出来」的标记。
 *
 * 用户 m48583 第②点：「当下载的歌曲文件无法本地播放时，弹出提示：本地无法播放，
 * 已转为在线资源」。
 *
 * ## 为什么需要两层判定
 *
 * 本地文件坏掉有两种形态，一层抓不住：
 *
 *   1. **打开就失败** —— 文件被删、SD 卡拔了、SAF 授权被回收、落盘写了半截。
 *      这一层在**取流时**就能发现（[isReadable]），代价是一次几十字节的读，
 *      比起让 ExoPlayer 试一遍再报错快得多，也不会先闪一下错误提示。
 *   2. **打得开但解不开** —— 容器头损坏、码流截断、下载时 CDN 回了半截响应。
 *      只有 ExoPlayer 真正解码才会失败，所以第二层在
 *      [PlayerController] 的 `onPlayerError` 里兜（见 [consumeDecodeFailure]）。
 *
 * 两层都走同一个出口：[onFallback] 通知界面弹一句提示，同时把这首从本地索引里
 * 摘掉 —— 摘掉之后行内的 √ 会消失、「已下载音乐」页也会少一条，用户能看出
 * 「这首确实不再是本地文件了」，而不是下次点开又踩同一个坑。
 *
 * 标记（[broken]）不落盘，故意只活在本次进程里：文件可能被用户重新下载、
 * 也可能换了一台设备能读，把「坏」持久化下去反而会在某天变成无法解释的
 * 「明明文件在却一直走在线」。
 */
object LocalSource {

    private val broken = ConcurrentHashMap.newKeySet<Long>()

    /** 上一次解析用的是不是本地文件。解码失败时靠它判断「该不该回退」。 */
    private val lastWasLocal = ConcurrentHashMap<Long, Boolean>()

    /** 从本地回落到在线时通知界面（ViewModel 注入，负责弹提示与刷新列表）。 */
    @Volatile
    var onFallback: ((Long) -> Unit)? = null

    fun isBroken(id: Long): Boolean = broken.contains(id)

    /** [AppViewModel.resolveById] 决定用本地文件时记一笔。 */
    fun noteLocal(id: Long) {
        lastWasLocal[id] = true
    }

    /** 决定走在线时记一笔 —— 覆盖掉上一次的「本地」。 */
    fun noteRemote(id: Long) {
        lastWasLocal[id] = false
    }

    /**
     * 取流阶段就判定这份本地文件用不了：标坏、通知界面。
     *
     * 调用方随后**继续取在线流**，所以用户看到的是一次点击之内完成的回退，
     * 而不是「先报错、再点一次」。
     */
    fun failBeforeOpen(id: Long) {
        if (broken.add(id)) {
            lastWasLocal[id] = false
            onFallback?.invoke(id)
        }
    }

    /**
     * 播放器解码阶段失败时调用：只有在「上一次解析确实用的本地文件」且
     * 「还没标坏」时返回 true —— 这时调用方应该原地重新 prepare 一次
     * （数据源层会重新解析，这次走在线），而不是把这首歌判成播放失败。
     *
     * 用 `broken.add` 的返回值做闸门，保证同一首歌最多回退一次，
     * 不会出现「在线也失败 → 再回退 → 死循环」。
     */
    fun consumeDecodeFailure(id: Long): Boolean {
        if (lastWasLocal[id] != true) return false
        lastWasLocal[id] = false
        if (!broken.add(id)) return false
        onFallback?.invoke(id)
        return true
    }

    /** 用户重新下载成功时可以清掉标记，下次播放又会先试本地。 */
    fun reset(id: Long) {
        broken.remove(id)
        lastWasLocal.remove(id)
    }

    /**
     * 这条本地地址现在还读不读得出东西。
     *
     * 只读前 64 字节判两件事：**一个字节都读不到**（文件没了/权限没了）、
     * **读出来是 HTML 或 JSON**（下载时 CDN 回了个错误页，服务端 4xx 也常常
     * 返回 200 + 一段 HTML）。其它情况一律放行 —— 真正的容器校验交给 ExoPlayer，
     * 在这里做严格的魔数白名单会把 m4a / wma / ape 这些我们没枚举全的格式误杀。
     */
    fun isReadable(context: Context, uri: String): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val buf = ByteArray(64)
            val n = input.read(buf)
            if (n <= 0) {
                false
            } else {
                val head = String(buf, 0, n, Charsets.ISO_8859_1).trimStart()
                !(head.startsWith("<") || head.startsWith("{") || head.startsWith("["))
            }
        } ?: false
    } catch (e: Exception) {
        false
    }
}
