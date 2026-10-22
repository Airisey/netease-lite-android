package com.netease.lite.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.netease.lite.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 本地音乐索引：「歌单里的这首歌，本机有没有？」
 *
 * 桌面版是主进程递归走文件系统（`electron\local.js`）。Android 没有全盘读权限，
 * 走 SAF：用户在「本地扫描」里选一个文件夹，系统给一个 `content://` 树 URI，
 * 之后读它下面的文件不需要任何权限。这比申请 `READ_MEDIA_AUDIO` 更少权限、
 * 也更容易过审 —— 用户看得见我们只碰他选的那个目录。
 *
 * 匹配优先看**文件自己的标签**（用户 m15635 第③点）：读 `TIT2`/`TPE1`/`TALB`
 * （FLAC 是 Vorbis 注释里的 `TITLE`/`ARTIST`/`ALBUM`），拿标签里的歌名去跟
 * 接口的 `song.name` 精确比对。文件名是用户/工具起的，跟歌本身没有约束关系，
 * `01.mp3` 和 `Rain.mp3` 都可能不是它 —— 标签才是歌自己带的身份。
 * 读不到标签（老文件、非 mp3/flac）才退回文件名匹配。
 *
 * 读标签用系统的 [android.media.MediaMetadataRetriever]：它只读头部，不用把
 * 整个文件读进来，也不用我们自己再写一遍 ID3/FLAC 解析。
 *
 * **只认完全匹配**（见 [matchOne]）：文件名归一化后必须整串等于「歌手 - 歌名」
 * 或「歌名 - 歌手」。以前那套「包含 / 切段」的宽松兜底命中率高，但会把一首歌
 * 串到另一首上，已经按用户要求删掉 —— 宁可扫不中让用户在线播。
 */
class LocalIndex(private val context: Context, private val prefs: Prefs) {

    /**
     * 本地一条记录：地址 + 这份文件是什么音质。
     *
     * [quality] 是用户 m11461 第③点加的。以前索引只记 `songId -> uri`，于是
     * 「切音质」在本地有文件时等于什么都没发生 —— 播放器拿到的还是同一个文件。
     * 现在切档时先看本地这份够不够格，够就用本地（不费流量），不够才走网络。
     *
     * 空串表示**不知道**：SAF 扫进来的文件没有下载记录，读标签又要为每个文件
     * 开一次流（几千首的目录会扫上十几秒）。不知道就当成「够格」——
     * 用户自己放进来的文件，宁可用它也别为了一个我们没记过的档位去下载。
     */
    data class Entry(val uri: String, val quality: String)

    /** songId -> [Entry]。 */
    private val map = LinkedHashMap<Long, Entry>()

    /** 扫过的目录（树 URI），用于界面上显示「来自 …」。 */
    var dirs: List<String> = emptyList()
        private set

    init { load() }

    val count: Int get() = map.size

    fun has(id: Long): Boolean = map.containsKey(id)

    fun uriOf(id: Long): String? = map[id]?.uri

    /** 这份本地文件是什么音质；空串表示不知道（扫描进来的文件）。 */
    fun qualityOf(id: Long): String? = map[id]?.quality

    fun ids(): Set<Long> = map.keys.toSet()

    /**
     * 本地这份够不够 [want] 这一档，够就用它、不去下载。
     *
     * 只用「够不够」，不用「等不等」：本地是 320k 而用户选了 128k 时放 320k
     * 谁也不吃亏（省流量是目的，音质更好不是问题）；反过来本地 128k 而用户
     * 要无损，就必须走网络 —— 那正是用户点这一档的意思。
     *
     * [quality] 为空串（扫描来的文件）时一律算够用，理由见 [Entry.quality]。
     */
    fun satisfies(id: Long, want: String): Boolean {
        val e = map[id] ?: return false
        if (e.quality.isEmpty() || want.isEmpty()) return true
        return rank(e.quality) >= rank(want)
    }

    /** 文件被删/被移走时剔除。SAF 每次都要查一次存在的，所以这里只做一次批量。 */
    fun prune() {
        val dead = map.filter { (_, e) -> !exists(e.uri) }.keys
        if (dead.isEmpty()) return
        for (id in dead) map.remove(id)
        save()
    }

    /**
     * 这条本地地址现在还能不能打开。
     *
     * 公开给 ViewModel 用：取流前先探一下，文件已经不在就别把 `content://` 交给
     * 播放器 —— 那会报成「网络连接失败」，而歌其实只是被删了。
     */
    fun exists(uri: String): Boolean = try {
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
    } catch (e: Exception) {
        false
    }

    fun forget() {
        map.clear()
        dirs = emptyList()
        prefs.localTreeUri = ""
        save()
    }

    fun remove(id: Long) {
        if (map.remove(id) != null) save()
    }

    /**
     * 下载完成后直接登记一条（不经过 SAF 扫描）。
     *
     * 下载落的是 `Music/网易云音乐 Lite/`，不在用户用 SAF 选的那棵树的子树里，
     * 所以扫描永远扫不到它 —— 必须在这里显式写进索引，否则「下载完在已下载音乐
     * 里看不到」。
     */
    fun adopt(id: Long, uri: String, quality: String = "") {
        map[id] = Entry(uri, quality)
        save()
    }

    /**
     * 扫描一个 SAF 目录树，用文件标签（缺失时用文件名）去匹配 [songs]。
     *
     * 返回 `matched` / `scanned` 两个数，界面直接报给用户 ——
     * 「扫了 300 个文件匹配上 42 首」比一句「扫描完成」有用得多。
     *
     * **边扫边报进度**（m00001 第②点）：几千个文件要扫十几秒，以前界面只有一个
     * 「扫描中」的提示，用户分不清它是在动还是卡死。现在分三段报：
     *   1. 走目录收集音频文件（事先不知道总数，只报「已找到 N 个」）；
     *   2. 逐个读标签并匹配（有了总数，进度按 `done/total` 走，同时报当前文件名）；
     *   3. 收尾写索引。
     *
     * [onTick] 在 IO 线程被调用，调用方负责切主线程。
     */
    suspend fun scan(
        treeUri: Uri,
        songs: List<Song>,
        onTick: ((ScanTick) -> Unit)? = null,
    ): ScanResult = withContext(Dispatchers.IO) {
        // SAF 的树权限默认只在本次进程有效；不 take 一次，重启后 fromTreeUri 还能
        // 拿到树，listFiles() 却返回空 —— 表现就是「扫了 0 个文件」，用户以为匹配坏了。
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScanResult(0, 0)
        val byKey = buildIndex(songs)
        val byTag = buildTagIndex(songs)
        val hit = LinkedHashMap<Long, String>()
        var files = 0

        onTick?.invoke(ScanTick(1, "", "正在遍历目录…"))

        // 第一段：只收集文件（不读标签），这样后面才有确定的进度分母。
        val all = ArrayList<DocumentFile>()
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) { walk(f); continue }
                if (!f.isFile) continue
                val name = f.name ?: continue
                if (!AUDIO_EXT.any { name.endsWith(it, ignoreCase = true) }) continue
                all.add(f)
                if (all.size % 20 == 0) onTick?.invoke(ScanTick(3, name, "已找到 ${all.size} 个音频文件"))
            }
        }
        walk(root)
        val total = all.size

        // 第二段：逐个读标签 + 匹配。这一段是真正耗时的部分（每个文件都要开流读头），
        // 进度条按它走，当前文件名也在这里报。
        for ((i, f) in all.withIndex()) {
            val name = f.name ?: continue
            val folder = norm(f.parentFile?.name ?: "")
            files++
            // 先看标签：标签里写了歌名就是最可信的身份。读不到（没有标签、
            // 格式不支持、文件损坏）才退回文件名。
            val tag = readTag(f.uri)
            val id = (tag?.let { matchByTag(it, byTag) })
                ?: matchOne(name.substringBeforeLast('.'), byKey, folder)
            if (id != null && id !in hit) hit[id] = f.uri.toString()
            // 每 4 个文件报一次：太密会把主线程刷爆，太疏进度条会一跳一大截。
            if (i % 4 == 0 || i == total - 1) {
                val pct = if (total > 0) 5 + ((i + 1) * 90 / total) else 95
                onTick?.invoke(ScanTick(pct, name, "已扫描 ${i + 1} / $total 个文件"))
            }
        }

        // 重扫同一棵树时，把上次从这棵树里认出来、这次没能严格对上的条目剔掉。
        // 少了这一步，旧版宽松匹配留下的错误对应会一直粘在索引里，「串歌」就修不干净。
        // 下载登记（[adopt]）的条目落在 `Music/网易云音乐 Lite/`，不在用户选的树里，
        // 除非用户恰好选了 `Music` —— 而下载文件名本来就是「歌手 - 歌名」，照样能对上。
        val prefix = "$treeUri/"
        for (id in map.filter { (id, e) -> id !in hit && e.uri.startsWith(prefix) }.keys) {
            map.remove(id)
        }
        // 扫描来的文件不记音质（[Entry.quality] 空串 = 不知道）。已经由下载登记过
        // 的条目保留原来的档位 —— 重扫一遍不该把「这是无损」这条信息抹掉。
        val keep = hit.keys.mapNotNull { id -> map[id]?.let { id to it.quality } }.toMap()
        for ((id, uri) in hit) map[id] = Entry(uri, keep[id] ?: "")
        if (treeUri.toString() !in dirs) dirs = dirs + treeUri.toString()
        prefs.localTreeUri = treeUri.toString()
        save()
        onTick?.invoke(ScanTick(100, "", "扫描完成，匹配到 ${hit.size} 首"))
        ScanResult(files, hit.size)
    }

    private fun buildIndex(songs: List<Song>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (s in songs) {
            val song = norm(s.name)
            if (song.isEmpty()) continue
            val artists = s.artists
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .map { norm(it) }
                .filter { it.isNotEmpty() }
            // 「歌手 - 歌名」和「歌名 - 歌手」两种命名都很常见，两个键都放进去。
            for (a in artistForms(artists)) {
                out.putIfAbsent("$a - $song", s.id)
                out.putIfAbsent("$song - $a", s.id)
            }
        }
        return out
    }

    /**
     * 一首歌的歌手串有哪几种写法（用户 m03123 第③点：首歌手名与歌手名两种都要试）。
     *
     * 同一个歌手列表，磁盘上能见到的写法至少有四种，归一化后落在三个不同的键上：
     *  - **首歌手**（或任意一位）：`A - 歌名` —— 手动整理的文件多半只写一位；
     *  - **空格连接**：`A & B - 歌名`、`A, B - 歌名`、`A、B - 歌名` —— [norm] 会把
     *    `&` `,` `、` 直接抹掉，剩下 `a b`；
     *  - **横线连接**：`A / B - 歌名`、`A|B`、`A_B` —— 这些分隔符归一成 `" - "`，
     *    剩下 `a - b`。
     *
     * 最后一种正是我们自己下载时落的形状（`Downloader` 的 `{歌手名}` 取
     * `Song.artistText`，也就是 `artists.joinToString(" / ")`）。只建首歌手的键的话，
     * 应用自己下的合唱文件重扫一遍反而认不出来 —— 这是原先真正的缺口。
     */
    private fun artistForms(artists: List<String>): List<String> {
        if (artists.isEmpty()) return emptyList()
        val forms = LinkedHashSet<String>()
        forms.addAll(artists)                       // 每一位单独一份
        if (artists.size > 1) {
            forms.add(artists.joinToString(" "))    // `A & B`
            forms.add(artists.joinToString(" - "))  // `A / B`
            // `A、B`、`A,B`、`A&B`：[norm] 把这些符号**整个抹掉**，归一化后是 `ab`，
            // 既不是 `a b` 也不是 `a - b`，所以还得单独建一份连写的。
            forms.add(artists.joinToString(""))
        }
        return forms.toList()
    }

    /**
     * 一个文件名怎么匹配到 songId —— **只做完全匹配**。
     *
     * 这里原来有四级宽松匹配（双向「包含」+ 切段兜底），命中率高但会串歌：一首
     * 《Rain》会把《Rain (Live)》《Rainy Days》都抢过来，中文短歌名更严重。用户
     * 明确要求「只有歌手名、歌曲名完全匹配的文件才能被识别」，于是整段删掉，只留
     * 整串相等这一条 —— 宁可扫不中让用户在线播，也不能把歌对应错。
     *
     * 归一化后整串必须命中索引里的一条：
     *  - `歌手 - 歌名` / `歌名 - 歌手`（`-` `–` `—` `_` `/` `|` 都归一成 ` - `）；
     *  - 多歌手时几位歌手用空格连着写也算一条；
     *  - 文件名只写了歌名时，只有**所在文件夹名恰好等于歌手名**才认
     *    （`Music/歌手/歌名.mp3` 这种归档布局）。
     *
     * @param folder 文件所在文件夹名，已归一化。
     */
    private fun matchOne(rawName: String, byKey: Map<String, Long>, folder: String): Long? {
        val key = norm(rawName)
        if (key.isEmpty()) return null
        byKey[key]?.let { return it }
        if (folder.isNotEmpty()) {
            byKey["$folder - $key"]?.let { return it }
            byKey["$key - $folder"]?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ 标签匹配

    /** 文件标签里我们关心的三项。 */
    private data class Tag(val title: String, val artist: String, val album: String)

    /** 索引里的一首歌：够用来判「这个名字是不是它」。 */
    private data class Tagged(val id: Long, val artistForms: List<String>, val album: String)

    /**
     * 读文件的标签。
     *
     * 用系统的 [android.media.MediaMetadataRetriever]：它按格式自己找头部，
     * mp3 / flac / m4a / ogg 都能读，比自己写一遍 ID3 + Vorbis 靠谱得多，
     * 也只需要读文件头。读不出来（没标签、格式不支持、文件坏了）返回 null。
     */
    private fun readTag(uri: Uri): Tag? {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            // 没有歌名就等于没有身份，别拿空串去撞索引。
            if (title.isBlank()) return null
            Tag(
                title = title,
                artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                    .ifBlank {
                        mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
                    },
                album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** `归一化歌名 -> 候选歌曲`。同名不同版本会落进同一个桶，靠歌手/专辑再分。 */
    private fun buildTagIndex(songs: List<Song>): Map<String, List<Tagged>> {
        val out = LinkedHashMap<String, MutableList<Tagged>>()
        for (s in songs) {
            val key = norm(s.name)
            if (key.isEmpty()) continue
            val artists = s.artists
                .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
                .map { norm(it) }
                .filter { it.isNotEmpty() }
            // 歌手串的几种写法都要认：标签里可能写「Sihan / 三Z」也可能只写「Sihan」。
            out.getOrPut(key) { mutableListOf() }.add(
                Tagged(s.id, artistForms(artists), norm(s.albumName)),
            )
        }
        return out
    }

    /**
     * 用标签找歌。[byTag] 的键是归一化歌名，所以**歌名必须完全相等**；
     * 歌手、专辑只用来在同名候选里挑那一份。
     *
     * 挑不准（两首同名、歌手和专辑都对不上）时返回 null —— 宁可让用户在线播，
     * 也不能把 A 的歌对应到 B 的文件上。
     */
    private fun matchByTag(tag: Tag, byTag: Map<String, List<Tagged>>): Long? {
        val cands = byTag[norm(tag.title)] ?: return null
        val artist = norm(tag.artist)
        val album = norm(tag.album)
        val scored = cands.map { c ->
            val aHit = artist.isNotEmpty() && c.artistForms.any { it == artist || it.contains(artist) || artist.contains(it) }
            val alHit = album.isNotEmpty() && album == c.album
            // 标签带歌手、歌里也有歌手，却两边都对不上 —— 同名的另一首歌
            // （翻唱、现场版、完全撞名），不能算匹配。专辑对得上时放行，
            // 用来兜住「标签里写的是 Various Artists」这种合集情况。
            val ok = artist.isEmpty() || c.artistForms.isEmpty() || aHit || alHit
            c to (if (ok) (if (aHit) 2 else 0) + (if (alHit) 1 else 0) else -1)
        }.filter { it.second >= 0 }
        if (scored.isEmpty()) return null
        val top = scored.maxByOrNull { it.second } ?: return null
        // 最高分并列（含「全是 0 分」）⇒ 分不出来，放弃，避免随机挑一个「串歌」。
        if (scored.count { it.second == top.second } > 1) return null
        return top.first.id
    }

    private fun load() {
        val raw = prefs.localIndex
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) {
                val id = k.toLongOrNull() ?: continue
                // 老格式是 `"id": "uri"`，新格式是 `"id": {"u": "uri", "q": "音质"}`。
                // 两种都要认：升级安装的用户手里存的是老格式，不认就等于索引全丢，
                // 所有下过的歌又得重新下一遍。
                val v = o.opt(k)
                if (v is JSONObject) {
                    val uri = v.optString("u")
                    if (uri.isNotEmpty()) map[id] = Entry(uri, v.optString("q"))
                } else {
                    val uri = o.optString(k)
                    if (uri.isNotEmpty()) map[id] = Entry(uri, "")
                }
            }
        } catch (e: Exception) {
            // 存坏了就当空的重来，不要因为一份索引让应用起不来。
            map.clear()
        }
        dirs = prefs.localTreeUri.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    }

    private fun save() {
        val o = JSONObject()
        for ((k, e) in map) {
            o.put(k.toString(), JSONObject().put("u", e.uri).put("q", e.quality))
        }
        prefs.localIndex = o.toString()
    }

    data class ScanResult(val scanned: Int, val matched: Int)

    /**
     * 扫描进度的一次快照（m00001 第②点）。
     *
     * @param percent 整段进度 0~100
     * @param file 当前正在扫描的文件名（界面把它显示在进度条下方）
     * @param detail 「已扫描 120 / 190 个文件」这类计数
     */
    data class ScanTick(val percent: Int, val file: String, val detail: String)

    companion object {
        val AUDIO_EXT = listOf(".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".wma", ".ape")

        /** 音质档高低（[satisfies] 用）。数值越大越好，未知一律按最低算。 */
        fun rank(q: String): Int = when (q) {
            "lossless" -> 3
            "exhigh" -> 2
            "higher" -> 1
            "standard" -> 0
            else -> -1
        }

        /**
         * 归一化：去括号内容、去常见后缀、把各种分隔符统一成 ` - `。
         *
         * **故意保留分隔符**：完全匹配要能分清「歌手」和「歌名」两段。像旧版那样把
         * `-` 一起抹掉的话，`A - B`、`A-B`、`AB` 会归一成同一个键，完全匹配就没意义了。
         */
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("""[（(\[【].*?[)）\]】]"""), " ")
            .replace(Regex("""\b(feat|ft|remaster|remastered|live|explicit|hires)\b.*"""), "")
            .replace(Regex("""\s*[-–—_/|／｜]\s*"""), " - ")
            .replace(Regex("""[·・.,，、!！?？'"“”‘’:：;；&]"""), "")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""(?:\s*-\s*)+"""), " - ")
            .trim(' ', '-')
    }
}
