package com.netease.lite.data

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 给下载下来的 MP3 写 ID3v2.3 标签（用户 m07048 第④点、m11461 第①点）。
 *
 * 起因：把「我们自己下的」和「官方 app 下的」同一首歌放到一起比，**音频载荷逐字节
 * 相同**（同为 Lavf57.71.100 转码的那一份），差的只有文件头的 ID3 标签 ——
 *
 * | | 我们（最早） | 官方 |
 * | --- | --- | --- |
 * | 标签大小 | 35 字节 | 54149 字节 |
 * | 帧 | `TSSE` 一个 | `TSSE` `APIC` `COMM` `TALB` `TIT2` `TPE1` `TPE2` `TPOS` `TRCK` |
 *
 * 于是我们的文件拷进别的播放器（车机、随身听、Windows 资源管理器）就成了
 * 「未知歌手 / 未知专辑 / 没有封面」，而官方那份什么都对。差的不是音质，是标签。
 *
 * ## 官方 app 为什么「不认」我们的文件（用户 m11461 第①点）
 *
 * 用户把两份文件放进官方 app 的本地音乐里，官方那份显示正常，我们那份带个感叹号。
 * 根因就在 `COMM` 帧：官方写的是
 *
 * ```
 * COMM (enc=0, lang="XXX", desc="") = "163 key(Don't modify):" + base64(AES-128-ECB(json))
 * ```
 *
 * 把那段密文解出来是 **`music:{…}` 一整段元数据 JSON**（`musicId` / `musicName` /
 * `artist` 的 id 数组 / `album` / `albumPicDocId` / `bitrate` / `duration` / `gain` /
 * `alias` …）。官方 app 认「这是我自己下的文件」靠的就是这一段 —— 没有它，标签里的
 * 歌名歌手再对也没用，它只当成一个外来文件。
 *
 * 密钥是 `#14ljk_!\]&0U<'(`（16 个 ASCII 字符，ncmdump 里用的那串）。这一行曾经写错过
 * 一个字符（`<'(` 写成 `<9(`），结果解密出来是一堆随机字节，连续几轮都判定成
 * 「私有算法、破不开」—— 教训是：**换密钥之前先确认密钥本身没打错**，尤其是这种
 * 带反斜杠和引号的字符串。
 *
 * ## 为什么在下载**之前**写
 *
 * 标签必须落在文件最前面。如果等下完再补，就得把整个文件重写一遍（或者挪出
 * 几十 MB 的临时文件再搬回来）—— 安卓的 MediaStore 流不支持「往前插」。
 * 而标签需要的信息（歌名/歌手/专辑/音轨号）在入队时就全有了，封面也能先单独
 * 取一张（几十 KB），所以直接**先写标签、再把音频接在后面**，一次成型。
 */
object Id3 {

    /**
     * 封面超过这个尺寸就不要了 —— 标签里塞一张几 MB 的图会让整首歌的打开变慢。
     *
     * 2MB 是给 1080×1080 留的余量：官方那种 JPEG 是 155,602 B，但**封面源图本身
     * 可能是 PNG**（见 [coverUrl] 里的 history），同尺寸能到 1MB 上下。
     */
    private const val MAX_COVER_BYTES = 2 * 1024 * 1024

    /** 官方客户端那份文件里写的编码器串，照写（原来是 `Lavf57.71.100`，官方是 58）。 */
    private const val ENCODER = "Lavf58.76.100"

    /**
     * `COMM` 帧里那段密文的 AES 密钥。
     *
     * 16 个字节，全部 ASCII：`#14ljk_!\]&0U<'(`。写成 `String` 再取字节而不是直接写
     * `byteArrayOf(...)`：里面有一个反斜杠和一个单引号，按字节写极易看错。
     */
    private const val COMM_KEY = "#14ljk_!\\]&0U<'("

    /** `COMM` 里密文的固定前缀，官方原样保留，包括那句「别改」。 */
    private const val COMM_PREFIX = "163 key(Don't modify):"

    /**
     * 生成一整段 ID3v2.3 标签。
     *
     * 帧顺序照官方的来：`TSSE` `APIC` `COMM` `TALB` `TIT2` `TPE1` `TPE2` `TPOS` `TRCK`。
     * 顺序本身不影响解析，但用户是**逐字节**跟官方比的，顺序不同会让两边的十六进制
     * 从第 40 个字节就开始分叉，看不出「到底还剩几处不一样」。
     *
     * @param cover 封面 JPEG/PNG 的原始字节；`null` 表示取不到，这时不写 `APIC` 帧
     *   （不写比写一张空白图好 —— 播放器至少会退回去显示它自己的占位图）
     * @param comm `COMM` 帧用的 [CommInfo]；`null` 时整帧不写
     * @param padding 帧区之后补的 0，官方会补几十到两百多字节（[CommInfo.padding]）
     */
    fun build(
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        trackNo: Int,
        discNo: Int,
        cover: ByteArray?,
        comm: CommInfo? = null,
        padding: Int = 0,
    ): ByteArray {
        val frames = ByteArrayOutputStream()

        // 文本帧统一用 UTF-16（带 BOM）。ID3v2.3 的编码字节 0x01 = UTF-16 with BOM，
        // 日文/中文歌名必须走这个 —— 0x00（ISO-8859-1）会把「桜海岸」写成问号。
        //
        // **结尾不补 0x00 0x00**：官方那份每个文本帧都比我们短 2 字节，就是差在这里。
        // 原来写的注释是「某些车机靠它判断字符串结束」，实际对照下来官方不写、各家
        // 播放器也都读得对 —— 帧长度本来就在帧头里写着，不需要第二个结束符。
        fun text(id: String, value: String) {
            if (value.isEmpty()) return
            val body = ByteArrayOutputStream()
            body.write(0x01)
            body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))  // UTF-16LE BOM
            body.write(value.toByteArray(Charset.forName("UTF-16LE")))
            frame(frames, id, body.toByteArray())
        }

        // 多歌手用 `/` 直接相连、不带空格 —— 与官方客户端那份文件逐字节对齐
        // （用户 m08537 第①点：官方写 `Sihan/三Z-STUDIO/HOYO-MiX`）。注意这只影响
        // 标签，文件名模板里的 `{歌手名}` 是另一套规则（见 Downloader.applyTemplate）。
        val artistTag = artist.replace(" / ", "/")

        text("TSSE", ENCODER)

        if (cover != null && cover.isNotEmpty() && cover.size <= MAX_COVER_BYTES) {
            val body = ByteArrayOutputStream()
            body.write(0x00)                                        // 编码：APIC 的
                                                                    // 描述串用 ISO-8859-1
            // MIME 必须跟真实字节对上。官方那份写的是 `image/jpg`（少个 e），
            // 这里也按官方的写法来 —— 用户是逐字节比的，而 `image/jpg` 虽然不标准，
            // 各家播放器都认（它比 `image/jpeg` 只是少一个字母）。
            body.write(mimeOf(cover).toByteArray(Charsets.ISO_8859_1))
            body.write(0x00)                                        // MIME 结束
            body.write(0x00)                                        // 图片类型 0 = 其他
                                                                    // （官方写 0。原来我们
                                                                    // 写 3「封面正面」，
                                                                    // 语义上更准，但对齐
                                                                    // 官方就得写 0）
            body.write(0x00)                                        // 描述串为空，直接结束
            body.write(cover)
            frame(frames, "APIC", body.toByteArray())
        }

        if (comm != null) {
            val body = ByteArrayOutputStream()
            body.write(0x00)                                        // 编码 0
            body.write("XXX".toByteArray(Charsets.ISO_8859_1))      // 语言，官方写 XXX
            body.write(0x00)                                        // 描述串为空
            body.write(COMM_PREFIX.toByteArray(Charsets.ISO_8859_1))
            body.write(comm.sealed())
            frame(frames, "COMM", body.toByteArray())
        }

        // 官方把 TALB 放在 TIT2 前面，这里也照这个顺序。
        text("TALB", album)
        text("TIT2", title)
        text("TPE1", artistTag)
        // TPE2 是「专辑艺术家」。官方那份把它写成和 TPE1 一样，多歌手时也是整串。
        text("TPE2", albumArtist.ifEmpty { artist }.replace(" / ", "/"))
        // TPOS 比 TRCK 早 —— 官方顺序如此。
        // 碟号：官方写的是 `disc1 万籁拂旧尘`（碟名）或 `01`（纯数字），见 CommInfo.discText。
        //
        // 判据不能只看 `discNo > 0`：万一碟号没解析出来但蝶名拿到了（接口里
        // `cd = "disc1 万籁拂旧尘"` 就是这种情况的高危形态），照样要写这一帧，
        // 否则资源管理器「详细信息」里官方那份比我们多一行「集的一部分」（用户 m15041）。
        if (discNo > 0 || !comm?.discText.isNullOrEmpty()) {
            text("TPOS", comm?.discText?.takeIf { it.isNotEmpty() } ?: discNo.toString().padStart(2, '0'))
        }
        if (trackNo > 0) text("TRCK", trackNo.toString())

        val body = frames.toByteArray()
        // 留白：官方那两份实测 147 / 211 字节，具体数字是各自编码器写出来的，
        // 我们复刻不了，取 [CommInfo.padding] 里那个中间量（128）。
        //
        // 坑：这个参数原本只挂在 [CommInfo] 上，`build()` 的形参默认 0 —— 调用方
        // 传了 `comm` 却没传 `padding`，结果标签尾巴一个 0 都没有（实测 pull 下来
        // 的文件 `padding=0`）。这里改成「没显式给就跟着 COMM 走」。
        val pad = (if (padding > 0) padding else (comm?.padding ?: 0)).coerceIn(0, 4096)
        val out = ByteArrayOutputStream(body.size + pad + 10)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
        out.write(0x03)   // 主版本 3 = ID3v2.3
        out.write(0x00)   // 修订号
        out.write(0x00)   // 标志位：无扩展头、无实验位
        // 标签长度用 syncsafe 整数（每字节只放 7 位，最高位恒 0）。
        // 这是 ID3v2 的硬性规定：直接写 32 位整数的话，长度字节一旦 ≥ 0x80
        // 播放器会把它当成「非法标签」整个跳过 —— 症状是标签明明写了却读不出来。
        val total = body.size + pad
        out.write((total shr 21) and 0x7F)
        out.write((total shr 14) and 0x7F)
        out.write((total shr 7) and 0x7F)
        out.write(total and 0x7F)
        out.write(body)
        for (i in 0 until pad) out.write(0x00)
        return out.toByteArray()
    }

    /**
     * `COMM` 帧的内容（用户 m11461 第①点）。
     *
     * 官方 app 靠这一帧认「这是我自己下的文件」：没有它，同一首歌在官方 app 的本地
     * 音乐里会带个感叹号，点开详情显示「原 / 新」两串对不上。
     *
     * 明文是 `music:` + 一段 JSON，字段名与接口返回的一致，但**值是官方自己算的**：
     * `musicId` 是曲目 id、`artist` 是 `[[名字, id], …]` 的数组、`bitrate` 是实际码率
     * （320002 而不是 320000，差在帧头开销）、`duration` 是毫秒、`gain` 是音量增益。
     *
     * 明文末尾按 PKCS#7 补齐到 16 的倍数（官方用的是**字节值**当填充数，不是标准的
     * 「填充长度」——两者在这里恰好相同，解出来先剥掉 `value` 个字节即可）。
     */
    data class CommInfo(
        val musicId: Long,
        val musicName: String,
        /** 歌手名与 id，保持接口里的顺序。 */
        val artists: List<Pair<String, Long>>,
        val album: String,
        val albumId: Long,
        /** 封面在 CDN 上的 docId（`109951172148710780`），从 `albumPic` 里抠出来。 */
        val albumPicDocId: Long,
        val albumPic: String,
        val mvid: Long,
        val bitrate: Int,
        val durationMs: Long,
        val gain: Double,
        val alias: List<String>,
        val transNames: List<String>,
        /** `TPOS` 用的碟号文本（官方在合集里写 `disc1 万籁拂旧尘`），空则用数字。 */
        val discText: String = "",
        /** 帧区之后补多少个 0。官方两份实测 147 / 211，取中间值即可 —— 它只是留白。 */
        val padding: Int = 0,
    ) {
        /** 加密成官方那种 base64 串。 */
        fun sealed(): ByteArray {
            val plain = json().toByteArray(Charsets.UTF_8)
            val pad = 16 - (plain.size % 16)
            val buf = ByteArray(plain.size + pad)
            System.arraycopy(plain, 0, buf, 0, plain.size)
            for (i in plain.size until buf.size) buf[i] = pad.toByte()
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(COMM_KEY.toByteArray(Charsets.ISO_8859_1), "AES"))
            val enc = cipher.doFinal(buf)
            return android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP)
                .toByteArray(Charsets.ISO_8859_1)
        }

        /**
         * 拼明文 JSON。
         *
         * 手拼而不是走 `org.json`：官方那份的键顺序是固定的（`format` `musicId`
         * `musicName` `artist` `album` `albumId` `albumPicDocId` `albumPic` `mvId`
         * `flag` `bitrate` `duration` `gain` `alias` `transNames`），`JSONObject`
         * 在安卓上底层是 `LinkedHashMap` 会保序，但 `JSONObject.toString` 不做
         * 转义风格的统一（官方把 `/` 写成 `\/`）—— 逐字节对齐只能自己拼。
         */
        private fun json(): String {
            val sb = StringBuilder("music:{")
            sb.append("\"format\":\"mp3\"")
            sb.append(",\"musicId\":").append(musicId)
            sb.append(",\"musicName\":").append(quote(musicName))
            sb.append(",\"artist\":[")
            sb.append(artists.joinToString(",") { (n, id) -> "[${quote(n)},$id]" })
            sb.append(']')
            sb.append(",\"album\":").append(quote(album))
            sb.append(",\"albumId\":").append(albumId)
            sb.append(",\"albumPicDocId\":").append(albumPicDocId)
            sb.append(",\"albumPic\":").append(quote(albumPic))
            sb.append(",\"mvId\":").append(mvid)
            sb.append(",\"flag\":0")
            sb.append(",\"bitrate\":").append(bitrate)
            sb.append(",\"duration\":").append(durationMs)
            sb.append(",\"gain\":").append(gain)
            sb.append(",\"alias\":").append(alias.joinToString(",", "[", "]") { quote(it) })
            sb.append(",\"transNames\":").append(transNames.joinToString(",", "[", "]") { quote(it) })
            sb.append('}')
            return sb.toString()
        }

        /**
         * JSON 字符串转义 —— **斜杠也转**。
         *
         * 官方那份里 `http://p1.music.126.net/…` 写成 `http:\/\/p1.music.126.net\/…`，
         * 是 Java 系 JSON 库的默认行为（`escapeForwardSlash`）。不转的话两边明文
         * 长度都不一样，密文自然也完全不同。
         */
        private fun quote(s: String): String {
            val sb = StringBuilder(s.length + 8)
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '/' -> sb.append("\\/")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }

    /**
     * 写一个帧：10 字节帧头 + 正文。
     *
     * 注意 v2.3 的帧长度是**普通** 32 位大端整数，和标签头的 syncsafe 不一样
     * （v2.4 才统一成 syncsafe）。这里按 v2.3 写，所以用 `shr 24` 而不是 `shr 21`。
     */
    private fun frame(out: ByteArrayOutputStream, id: String, body: ByteArray) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write((body.size shr 24) and 0xFF)
        out.write((body.size shr 16) and 0xFF)
        out.write((body.size shr 8) and 0xFF)
        out.write(body.size and 0xFF)
        out.write(0x00)   // 标志位高字节
        out.write(0x00)   // 标志位低字节
        out.write(body)
    }

    /**
     * 按文件头魔数判断封面格式。
     *
     * 官方那份写 `image/jpg`，这里跟着写 —— 它和 `image/jpeg` 是同一个格式的两种
     * 拼法，`image/jpg` 虽然不在 IANA 表里，各家播放器都认。
     */
    private fun mimeOf(cover: ByteArray): String {
        fun at(i: Int) = cover[i].toInt() and 0xFF
        if (cover.size >= 8 && at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) {
            return "image/png"
        }
        if (cover.size >= 6 && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46) {
            return "image/gif"
        }
        if (cover.size >= 12 && at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        ) {
            return "image/webp"
        }
        return "image/jpg"
    }

    /**
     * 封面地址：**必须带 `type=jpg`**（用户 m11461 第①点）。
     *
     * 原来只写 `?param=1080y1080&quality=80`。对 JPEG 源图没问题（DAMIDAMI 那张
     * 与官方 `APIC` 逐字节相同），但对 **PNG 源图**就出事了：CDN 会原样给 PNG，
     * 1080×1080 能到 1.7MB，而官方那份是 168KB 的 JPEG ——
     * 玄翎谣：我们 12,894,461 B / 官方 11,299,116 B，1.6MB 全差在封面上。
     *
     * `?imageView&thumbnail=…&quality=…&type=jpg` 是又一层「强制出图格式」的参数，
     * 加上之后 PNG 源图也回 JPEG，且与官方 `APIC` **逐字节相同**（两首歌都验过）。
     * 尺寸取官方那个：1080 的框，源图小就按源图尺寸出（玄翎谣源图 1000×1000，
     * 出来的就是 1000×1000，官方也是）。
     */
    fun coverUrl(url: String): String {
        if (url.isEmpty()) return url
        val base = url.replace(Regex("\\?.*$"), "")
        return "$base?imageView&thumbnail=1080y1080&quality=80&type=jpg"
    }

    /**
     * 判断这份文件是不是 FLAC。
     *
     * FLAC 用 Vorbis comment 而不是 ID3，硬塞一段 ID3 到 FLAC 前面会让一部分
     * 解码器直接认不出来（它们只认 `fLaC` 魔数）。所以无损档不写标签 ——
     * 宁可少几个字段，也不能把文件弄成打不开的。
     */
    fun isFlac(fileName: String): Boolean = fileName.endsWith(".flac", ignoreCase = true)
}
