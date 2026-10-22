package com.netease.lite.data

/**
 * 「这一份 `.part` 里的音频到底下全了没有」的算术。
 *
 * 单独拎成一个不依赖 Android 的纯函数，是因为它是下载器里唯一出过 bug 的算术
 * （用户 m04384 第③点）：三首歌都卡在「文件不完整（已下 X / Y 字节）」，而
 * `Y - X` 恰好等于源流开头那段被丢掉的 CDN ID3 长度（45 字节）。
 *
 * 两个来路都会造成这个差值：
 *   1. 账本里存的 `total` 是**源流**长度（含那段 ID3），而不是音频长度 ——
 *      老版本写的账本就是这样，见 [normalizeStoredTotal]；
 *   2. 判据用了源流长度（`Content-Range` 里的 `Z`）直接和盘上音频比。
 *
 * 所以这里统一成一句话：**能拿到远端总长时，音频应得长度 = 远端总长 - skip**；
 * 拿不到才退回账本里的 `total`。
 */

/** 音频应得字节数；<=0 表示长度未知（这时只要求「盘上有音频」）。 */
internal fun downloadNeedBytes(remoteTotal: Long?, skip: Long, total: Long): Long {
    val fromRemote = remoteTotal?.let { (it - skip).coerceAtLeast(0L) }
    if (fromRemote != null && fromRemote > 0) return fromRemote
    return total
}

/**
 * 把账本里的 `total` 归一成**音频**长度。
 *
 * 老账本（m01537 那次修 `skip` 之前）写的是 `Content-Length`，也就是源流长度，
 * 比音频多一个 `skip`。特征非常确定：账面总长比盘上音频正好多一个 `skip`。
 * 不掰回来的话，明明已经下完的任务会被判成「文件不完整」，而且重试永远一样 ——
 * 因为每次重算都用同一个错误的 `total`。
 */
internal fun normalizeStoredTotal(skip: Long, total: Long, onDisk: Long): Long =
    if (skip > 0 && total > skip && onDisk > 0 && total - onDisk == skip) total - skip else total

/**
 * 收尾判定：[audioOnDisk] 够不够 [need]。
 *
 * `audioOnDisk <= 0` 永远算不完整 —— 空壳 `.part` 不该变成成品（m02003 第④点）。
 */
internal fun downloadComplete(audioOnDisk: Long, need: Long): Boolean =
    audioOnDisk > 0 && (need <= 0 || audioOnDisk >= need)
