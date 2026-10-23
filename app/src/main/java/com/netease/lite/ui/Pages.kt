package com.netease.lite.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netease.lite.AppViewModel
import com.netease.lite.Route
import com.netease.lite.data.Downloader
import com.netease.lite.data.Prefs
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.netease.lite.model.Song
/**
 * 各页面。
 *
 * 布局原则：**手机竖屏优先**。桌面版的侧栏在这里换成抽屉（[Sidebar]），
 * 五列网格的歌单行换成 [SongRow]，横向滚动的歌单卡片保留。
 * 平板/折叠屏展开时只是同一套布局变宽，不另做一套。
 */

// ------------------------------------------------------------------ 通用外壳

/** 页面容器：负责加载中 / 出错 / 空态三态，页面自己只写「有数据」的样子。 */
@Composable
fun PageShell(
    vm: AppViewModel,
    empty: Boolean = false,
    emptyText: String = "这里还没有内容",
    /** 关掉下拉刷新：错误页和「正在播放」这类不靠接口重拉的页面用得上。 */
    refreshable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val body: @Composable () -> Unit = {
        when {
            vm.pageLoading && vm.pageError == null -> LoadingBlock(vm.loading ?: "加载中")
            // 空态与错误页必须**可滚动**，否则下拉手势根本不会被识别 ——
            // PullToRefreshBox 的拖拽靠 nestedScroll 往上冒，静止不动的
            // Column 不产生滚动事件，用户会以为「下拉刷新没做」。
            // 而这正是最需要刷新的时刻（首屏没数据、或上一次请求失败）。
            vm.pageError != null -> Scrollable { EmptyState("加载失败", vm.pageError) }
            empty -> Scrollable { EmptyState(emptyText) }
            else -> content()
        }
    }
    if (!refreshable) {
        body()
        return
    }
    // 第②点：下拉刷新。
    // 两种写法都试过：自己接 nestedScroll 并在顶部画圈，代码量翻倍还得自己算
    // 回弹动画；material3 1.4 自带 PullToRefreshBox，行为与系统一致（含无障碍）。
    // 用 Box 包住内容而不是每页各包一次 —— 十来个页面一处接上就够了。
    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        body()
    }
}

/**
 * 让一块**静态内容**也能被下拉手势拉动。
 *
 * 内容不够一屏时 `verticalScroll` 不会真的滚动，但它仍会参与 nestedScroll 协商，
 * 于是 `PullToRefreshBox` 能拿到拖拽量。少这一层，空态页面上的下拉完全没反应。
 */
@Composable
private fun Scrollable(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        content()
    }
}

/**
 * 歌曲列表。所有出现歌曲列表的页面都走这里 ——
 * 红心、播放、「⋯」菜单三件事只写一遍。
 *
 * [batchable] 打开「多选」：列表顶部出现一条工具条（全选 / 已选 n 首 / 移除 / 取消），
 * 点行改成勾选。它只在歌单页打开 —— 别处（搜索结果、榜单）没有「批量操作」的对象。
 */
@Composable
fun SongList(
    vm: AppViewModel,
    songs: List<Song>,
    contextId: Long = 0,
    contextName: String = "",
    /** 出现在这个歌单页里时给「从歌单删除」用；null 表示不在歌单里。 */
    playlistId: Long? = null,
    batchable: Boolean = false,
    /**
     * 列表**底部**的内容（歌手页热门歌曲下面那一栏「查看全部歌曲」、全部歌曲页的
     * 「加载更多」）。
     *
     * 不做成 header 的一部分：header 是列表第一项、footer 得跟着歌走到底，
     * 塞进 header 会变成「一进页面就看见」，点不到还有下一页这件事。
     * 多选时同样收起来 —— 那时候要的是勾歌，不是翻页。
     *
     * 排在 [header] **前面**：header 一直是尾部 lambda 的写法
     * （`SongList(...) { 页头 }`），把 footer 放到最后会让那些调用点静静地把页头
     * 装进 footer 里，页头一瞬间全跑到列表底下去。
     */
    footer: (@Composable () -> Unit)? = null,
    /**
     * 列表顶部的内容（页头 / 大图头部）。
     *
     * 参数是「进入多选」的回调，不是无参 lambda：第⑪点要求把「多选」按钮放到页头
     * 动作行里「评论」的右边，而多选状态住在 [SongList] 内部 —— 页头要能反过来
     * 触发它，只能由这里把入口递下去。
     */
    header: (@Composable (startPick: () -> Unit) -> Unit)? = null,
) {
    var menuFor by remember { mutableStateOf<Song?>(null) }
    var picking by remember { mutableStateOf(false) }
    // 批量「添加 / 移动」要选目标歌单，两个弹窗共用一个开关：null 表示没开。
    var batchSheet by remember { mutableStateOf<String?>(null) }
    // 用 id 而不是下标：删掉一首之后下标全部前移，拿下标记选中会把别的歌一起删。
    val picked = remember { androidx.compose.runtime.mutableStateListOf<Long>() }

    fun exitPick() {
        picking = false
        picked.clear()
    }

    Column(Modifier.fillMaxSize()) {
        if (picking) {
            BatchBar(
                count = picked.size,
                total = songs.size,
                onAll = {
                    if (picked.size == songs.size) picked.clear()
                    else {
                        picked.clear()
                        picked.addAll(songs.map { it.id })
                    }
                },
                onRemove = {
                    if (picked.isNotEmpty() && playlistId != null) {
                        val gone = songs.filter { picked.contains(it.id) }
                        vm.removeFromPlaylist(playlistId, gone)
                        exitPick()
                    }
                },
                // 批量下载：桌面版多选之后同样能一次下完（`App.downloadSongs`）。
                onDownload = {
                    if (picked.isNotEmpty()) {
                        vm.downloadMany(songs.filter { picked.contains(it.id) })
                        exitPick()
                    }
                },
                // 「移动到」「添加到」：桌面版 `.tl-selbar` 里这两颗挨在一起。
                // 移动 = 先加进目标歌单、再从当前歌单删掉；只在歌单页（playlistId
                // 非空）才有「移动」，因为别处没有「从这里移走」这个概念。
                onMove = if (playlistId != null) {
                    { if (picked.isNotEmpty()) batchSheet = "move" }
                } else null,
                onAdd = { if (picked.isNotEmpty()) batchSheet = "add" },
                onDone = { exitPick() },
            )
        }
        // 多选时**仍然列全部歌曲**。早先的写法是「只列已选中的歌」，看起来像是
        // 帮用户在长歌单里核对，实际上刚点开多选时一首都没选 ⇒ 整页一片空白，
        // 403 首的歌单像是加载失败；而且下面 playSongs(songs, i) 用的是 shown
        // 的下标，两份列表一旦长度不同，点歌会播错。勾选态交给行首的方框表达。
        val shown = songs
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // 多选期间页头要收起来：动作行里就有一颗「多选」，留着它等于
            // 让用户在工具条和页头之间来回看两个互相矛盾的入口。
            if (header != null && !picking) item { header { if (batchable) picking = true } }
            if (picking && picked.isEmpty()) {
                item {
                    Text(
                        "点歌名左侧的方框勾选，可全选后批量下载或移除",
                        color = skin.text3,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            items(shown.size) { i ->
                val song = shown[i]
                SongRow(
                    song = song,
                    // shown 就是 songs，直接用 i。早先写成 songs.indexOfFirst{...}
                    // 是 O(n) 查一遍，400 首的歌单每帧要扫十几万次比较。
                    index = i,
                    isPlaying = vm.player.currentSong?.id == song.id,
                    liked = vm.likedIds[song.id] == true,
                    local = vm.local.has(song.id),
                    selected = if (picking) picked.contains(song.id) else null,
                    showCover = vm.listCover,
                    onClick = {
                        if (picking) {
                            if (picked.contains(song.id)) picked.remove(song.id) else picked.add(song.id)
                        } else {
                            vm.playSongs(songs, i, contextId, contextName)
                        }
                    },
                    onLike = { vm.toggleLike(song) },
                    onMore = if (picking) null else ({ menuFor = song }),
                )
            }
            if (footer != null && !picking) item { footer() }
        }
    }
    menuFor?.let { song ->
        // 把「这首歌在哪个列表的第几位」一起交给菜单：桌面版右键菜单的「播放」
        // 是「从这一首开始播当前列表」，而不是把队列换成只有这一首。
        SongMenu(
            vm = vm,
            song = song,
            playlistId = playlistId,
            onDismiss = { menuFor = null },
            songs = songs,
            index = songs.indexOfFirst { it.id == song.id },
            contextId = contextId,
            contextName = contextName,
        )
    }
    // 批量「移动到 / 添加到」：都先选目标歌单。
    batchSheet?.let { mode ->
        val chosen = songs.filter { picked.contains(it.id) }
        AddToPlaylistSheet(
            vm = vm,
            songs = chosen,
            title = if (mode == "move") "移动到歌单（${chosen.size} 首）" else null,
            onPick = { target ->
                vm.addToPlaylist(target, chosen)
                // 移动 = 先加进目标、再从当前歌单删掉。顺序不能反：先删后加，
                // 中途失败（网络、服务端拒）会把歌从两边都弄丢。
                if (mode == "move" && playlistId != null && target != playlistId) {
                    vm.removeFromPlaylist(playlistId, chosen)
                }
                exitPick()
            },
            onDismiss = { batchSheet = null },
        )
    }
}

/**
 * 多选工具条。与桌面版 `.tl-selbar` 同一套文案与顺序。
 *
 * 桌面版的按钮序列是「全选 / 移动到 / 添加到 / 喜欢 / 下载 / 从歌单删除 / 取消」
 * （`renderer/js/views.js:290-302`）。手机上七颗一行放不下，砍掉了「喜欢」
 * （单曲红心在行尾本来就有），其余保留 —— 顺序仍与桌面一致。
 *
 * 整条可横向滚动：小屏（折叠屏外屏 344dp）上就算砍到六颗也会溢出，
 * 滚动比折行好看，也比再砍按钮诚实。
 */
@Composable
private fun BatchBar(
    count: Int,
    total: Int,
    onAll: () -> Unit,
    onMove: (() -> Unit)?,
    onAdd: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .background(s.bg2)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $count / $total", color = s.text1, fontSize = 12.5.sp)
        Spacer(Modifier.width(10.dp))
        PillButton(if (count == total) "取消全选" else "全选", onClick = onAll)
        if (onMove != null) {
            Spacer(Modifier.width(8.dp))
            PillButton("移动", onClick = onMove)
        }
        Spacer(Modifier.width(8.dp))
        PillButton("添加", onClick = onAdd)
        Spacer(Modifier.width(8.dp))
        PillButton("下载", onClick = onDownload)
        Spacer(Modifier.width(8.dp))
        // 只有在歌单里才有「移除」，其余场景这一颗是无意义的空按钮 —— 但仍保留，
        // 因为多选目前只在歌单页打开（见 SongList 的 batchable 注释）。
        PillButton("移除", primary = true, onClick = onRemove)
        Spacer(Modifier.width(8.dp))
        PillButton("完成", onClick = onDone)
    }
}

/**
 * 「带下标的 items」的薄封装。
 *
 * 直接用 foundation 的 `itemsIndexed(list) { i, s -> }` 需要 import 那个泛型重载，
 * 而 lazy 包里有好几个同名重载，写错就编译不过。统一走 `items(count)`，
 * 全项目一处定义，调用点少一层心智负担。
 */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedStable(
    list: List<T>,
    row: @Composable (Int, T) -> Unit,
) = items(list.size) { i -> row(i, list[i]) }

// ------------------------------------------------------------------ 歌曲菜单

/**
 * 单曲的「⋯」菜单（第⑬点：与桌面版完全一致）。
 *
 * 桌面版 `songContextMenu` 的顺序是：
 *   播放 / 下一首播放 ── 喜欢 / 添加到歌单 / 下载 ── 查看专辑 / 查看歌手 / 查看评论
 *   ── 复制歌曲链接 ──（在歌单里时）从本歌单中删除
 * 这里逐项对齐：分组顺序、文案、以及「哪些项在什么情况下是灰的」。
 *
 * 两处刻意的不同：
 *   - 桌面版用右键菜单（`U.ctxMenu`），移动端用对话框 —— 手机上长按弹菜单会与
 *     「长按拖动排序」抢手势，而且小屏里贴着手指的菜单常被裁掉一半。
 *   - 「查看专辑 / 查看歌手」在没有对应 id 时置灰而不是隐藏：桌面版也是置灰，
 *     让用户知道「这首歌确实没有专辑信息」，而不是怀疑菜单少了一项。
 */
@Composable
fun SongMenu(
    vm: AppViewModel,
    song: Song,
    playlistId: Long?,
    onDismiss: () -> Unit,
    /** 所在列表，用于「播放」这一项重开一个上下文；为空时只播这一首。 */
    songs: List<Song>? = null,
    index: Int = 0,
    contextId: Long = 0,
    contextName: String = "",
) {
    val s = skin
    val liked = vm.likedIds[song.id] == true
    var pickPlaylist by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    if (pickPlaylist) {
        AddToPlaylistSheet(vm, listOf(song), onDismiss = { pickPlaylist = false; onDismiss() })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = {
            Column {
                Text(
                    song.name,
                    color = s.text0,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artistText,
                    color = s.text3,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MenuItem("播放", Icons.Play) {
                    val list = songs
                    if (list != null && list.isNotEmpty()) {
                        vm.playSongs(list, index.coerceIn(0, list.size - 1), contextId, contextName)
                    } else {
                        vm.playSong(song)
                    }
                    onDismiss()
                }
                MenuItem("下一首播放", Icons.Next) {
                    vm.player.playNext(listOf(song))
                    vm.notify("已加入下一首")
                    onDismiss()
                }
                MenuSep()
                MenuItem(if (liked) "取消喜欢" else "喜欢", if (liked) Icons.HeartFilled else Icons.HeartOutline) {
                    vm.toggleLike(song)
                    onDismiss()
                }
                MenuItem("添加到歌单…", Icons.Add) { pickPlaylist = true }
                MenuItem(
                    if (vm.local.has(song.id)) "重新下载" else if (song.fee == 1) "下载（VIP）" else "下载",
                    Icons.Download,
                    // 「重新下载」这行只在本地已有的时候才出现，所以这里必须把
                    // force 传下去 —— 否则 `download()` 会拦成「已经在本地了」，
                    // 菜单说能重下、点下去什么都不发生。
                ) { vm.download(song, force = vm.local.has(song.id)); onDismiss() }
                MenuSep()
                MenuItem("查看专辑", Icons.Album, enabled = song.albumId > 0) {
                    vm.go(Route.Album(song.albumId, song.albumName))
                    onDismiss()
                }
                MenuItem(
                    "查看歌手",
                    Icons.Artist,
                    enabled = (song.artists.firstOrNull()?.id ?: 0L) > 0,
                ) {
                    val a = song.artists.first()
                    vm.go(Route.Artist(a.id, a.name))
                    onDismiss()
                }
                MenuItem("查看评论", Icons.Comment) {
                    vm.go(Route.Comments("song", song.id, song.name))
                    onDismiss()
                }
                MenuSep()
                MenuItem("复制歌曲链接", Icons.Copy) {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "song",
                            "https://music.163.com/song?id=${song.id}",
                        ),
                    )
                    vm.notify("链接已复制")
                    onDismiss()
                }
                if (playlistId != null) {
                    MenuSep()
                    MenuItem("从本歌单中删除", Icons.Trash) {
                        vm.removeFromPlaylist(playlistId, listOf(song))
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = s.text2) } },
    )
}

/** 菜单里的一行：图标 + 文案，与桌面版 `U.ctxMenu` 的行结构一致。 */
@Composable
private fun MenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val s = skin
    val tint = if (enabled) s.text0 else s.text3
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(s.radii.baseDp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 11.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            null,
            tint = if (enabled) s.text2 else s.text3,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, color = tint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 分组之间的细线。桌面版的 ctxMenu 用同样一条 `--line` 分隔。
 *
 * 不用 `private`：侧栏的长按菜单（`Shell.kt` 的 `Sidebar`）也用它，
 * 同包不同文件，private 访问不到。
 */
@Composable
internal fun MenuSep() {
    val s = skin
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(s.line),
    )
}

@Composable
internal fun MenuLine(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = skin.text0,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(skin.radii.baseDp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
    )
}

@Composable
private fun PlaylistPicker(vm: AppViewModel, song: Song, onDismiss: () -> Unit) {
    AddToPlaylistSheet(vm, listOf(song), onDismiss = onDismiss)
}

/**
 * 「添加到歌单」的选单。
 *
 * 第⑬点起它要能被多处复用（单曲菜单、批量多选、正在播放的 ⋯），所以参数从
 * 一首歌改成一组歌 —— 桌面的 `App.openAddToPlaylist([song])` 也是收数组的，
 * 批量加歌单时不会退化成一个一个弹。
 *
 * [title] / [onPick] 是第⑪点「移动到」带进来的：移动与添加的界面完全一样，
 * 只有标题不同、以及选完之后要不要从原歌单删掉。与其复制一份弹窗，不如把
 * 这两处差异参数化。
 */
@Composable
fun AddToPlaylistSheet(
    vm: AppViewModel,
    songs: List<Song>,
    onDismiss: () -> Unit,
    title: String? = null,
    onPick: ((Long) -> Unit)? = null,
) {
    val s = skin
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = {
            Text(
                title ?: if (songs.size > 1) "添加到歌单（${songs.size} 首）" else "添加到歌单",
                color = s.text0,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                items(vm.playlists.size) { i ->
                    val p = vm.playlists[i]
                    if (p.isLiked) return@items
                    Text(
                        p.name,
                        color = s.text0,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(s.radii.baseDp))
                            .clickable {
                                if (onPick != null) onPick(p.id) else vm.addToPlaylist(p.id, songs)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消", color = s.text2) } },
    )
}

// ------------------------------------------------------------------ 各页面

/**
 * 「大框框」页头（用户 m00932 第⑩点；m00001 第⑤点改成逐款对齐桌面）。
 *
 * 桌面版每日推荐页最上面那一块（`renderer/styles.css:700` 的 `.daily-head` 加
 * `skins.css` 里每款皮肤的一条覆盖规则）：一整条圆角底色块，左边一格大数字日期，
 * 中间标题 + 一行说明，右边一两个动作按钮。手机的每日推荐原来只有一行 [PageTitle]，
 * 跟「榜单」「播客」完全一样，一天只看一次的那一页显得很素。
 *
 * **底色 / 描边 / 左侧杠 / 硬投影全部来自 `skin.style.hero`**（见 [HeroStyle]），
 * 五款皮肤各自对应桌面那五条 CSS。这里不再自己拿强调色调一个渐变 —— 以前就是
 * 这么干的，于是现代白和瑞士风在手机上是一块明显发红的色块，而桌面只是极淡
 * 一层；街机风在桌面是薄荷色块，手机上还是黄渐变。
 *
 * 一处**有意不照抄桌面**：桌面的大数字是 40px，手机上 40sp 会把这一条撑得比
 * 封面还抢眼，取 32sp。其余（日期格有无、按钮、文案）与桌面一致。
 *
 * @param date 大数字（每日推荐传当天的日），null = 不画日期格
 * @param dateSub 大数字下面那小字（「2月 · 周五」）
 */
@Composable
private fun PageHero(
    title: String,
    subtitle: String,
    date: String? = null,
    dateSub: String = "",
    actions: @Composable () -> Unit = {},
) {
    val s = skin
    val hero = s.style.hero
    val shape = RoundedCornerShape(s.radii.lgDp)
    val borderW = hero.borderWidth.coerceAtLeast(1).dp
    val barW = hero.leftBarWidth.coerceAtLeast(2).dp

    // 底色：先垫一层实色（桌面 CSS 里 `linear-gradient(...), var(--bg-3)` 的后半段），
    // 再把渐变铺在上面。没有给颜色就退回皮肤的默认渐变 —— 这样以后新加皮肤
    // 即使忘了写 hero 也不会变成一块空白。
    //
    // 方向：桌面经典黑/现代白是 `120deg`（左上→右下），落日余晖是 `90deg`（纯横向）。
    // Compose 的 `linearGradient` 默认就是从左上角到右下角，正好对上 120deg 的观感；
    // 横向那一款用 `horizontalGradient`。
    val colorStops: Array<Pair<Float, Color>> = run {
        val cols = hero.colors
        val st = hero.stops
        if (st != null && st.size == cols.size) {
            Array(cols.size) { i -> st[i].coerceIn(0f, 1f) to cols[i] }
        } else {
            Array(cols.size) { i ->
                (if (cols.size == 1) 0f else i.toFloat() / (cols.size - 1)) to cols[i]
            }
        }
    }
    val fill: Brush = when {
        hero.colors.size >= 2 && hero.angleIsDiagonal -> Brush.linearGradient(*colorStops)
        hero.colors.size >= 2 -> Brush.horizontalGradient(*colorStops)
        hero.colors.size == 1 -> Brush.linearGradient(listOf(hero.colors[0], hero.colors[0]))
        else -> Brush.linearGradient(
            listOf(s.accent.copy(alpha = 0.16f), s.accent.copy(alpha = 0.03f)),
        )
    }

    val bodyColor = (hero.bodyColor ?: s.text2).copy(alpha = hero.bodyAlpha)
    val titleColor = hero.titleColor ?: s.text0

    // 街机风的硬投影是纯绘制、不占布局，所以用 HardShadowBox 包一层；
    // 其余皮肤 offset = 0，它内部会直接透传。
    //
    // 外边距必须在 HardShadowBox **外面**（m01537 第⑤点）：原来 16dp 横向留白写在
    // 里层 Row 上，HardShadowBox 拿到的是「整行宽」，投影也跟着按整行宽画 ——
    // 于是薄荷色块右边探出一道 16dp 宽的投影，比播放页封面（104dp 配 4dp，
    // 比例 1/26）粗得多。把留白挪到外面之后，盒子尺寸就等于大框框本身，
    // 偏移量按「框的短边 × 1/26」算，和封面那一档完全同比例。
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HardShadowBox(offset = hero.hardShadow, radius = s.radii.lgDp, shadowColor = s.text0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .then(
                        if (hero.base != null) Modifier.background(hero.base) else Modifier,
                    )
                    .background(fill)
                    .then(
                        if (hero.border != null) Modifier.border(borderW, hero.border, shape) else Modifier,
                    )
                    // 左侧内嵌的那道杠（瑞士风红线 / 落日橙杠）：画成一个固定宽度的
                    // 色块放在最左边，比给 Row 加 border-left 更接近桌面的
                    // `box-shadow: inset 4px 0 0`（后者不占布局，前者也不占）。
                    .then(
                        if (hero.leftBar != null) {
                            Modifier.drawBehind {
                                drawRect(
                                    color = hero.leftBar,
                                    size = androidx.compose.ui.geometry.Size(barW.toPx(), size.height),
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        start = 16.dp + if (hero.leftBar != null) barW else 0.dp,
                        end = 16.dp,
                        top = 14.dp,
                        bottom = 14.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (date != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        date,
                        color = s.accentHi,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 32.sp,
                    )
                    if (dateSub.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(dateSub, color = bodyColor, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = titleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    subtitle,
                    color = bodyColor,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            actions()
            }
        }
    }
}

@Composable
fun DailyPage(vm: AppViewModel) {
    PageShell(vm, vm.dailySongs.isEmpty(), "今天的推荐还没出来，下拉或等一会儿再来") {
        SongList(vm, vm.dailySongs, contextId = -1, contextName = "每日推荐") {
            // 用户 m00932 第⑩点：仿桌面端 `.daily-head` 加一个「大框框」。
            // 日期取本机当天 —— 接口给的 reason 只影响说明文字，
            // 而这一格大数字本来就是「今天的推荐」的日期戳，用本机时间更稳。
            val today = java.time.LocalDate.now()
            val week = listOf("一", "二", "三", "四", "五", "六", "日")[today.dayOfWeek.value - 1]
            PageHero(
                title = "根据你的口味生成",
                subtitle = "每天 6:00 更新，为你推荐 ${vm.dailySongs.size} 首歌曲",
                date = today.dayOfMonth.toString(),
                dateSub = "${today.monthValue}月 · 周$week",
                actions = {
                    PillButton("播放全部", primary = true) {
                        vm.playSongs(vm.dailySongs, 0, -1, "每日推荐")
                    }
                },
            )
        }
    }
}

@Composable
fun RecommendPage(vm: AppViewModel) {
    PageShell(vm, vm.recommend.isEmpty(), "没有推荐歌单") {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                PageTitle("推荐歌单", "精选歌单，点开就能听")
            }
            items(vm.recommend.size) { i ->
                val p = vm.recommend[i]
                PlaylistCard(p) { vm.go(Route.Playlist(p.id, p.name)) }
            }
        }
    }
}

@Composable
fun ChartsPage(vm: AppViewModel) {
    PageShell(vm, vm.charts.isEmpty(), "没有榜单数据") {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                PageTitle("榜单", "官方榜与特色榜")
            }
            items(vm.charts.size) { i ->
                val p = vm.charts[i]
                PlaylistCard(p) { vm.go(Route.Playlist(p.id, p.name)) }
            }
        }
    }
}

@Composable
fun PodcastsPage(vm: AppViewModel) {
    PageShell(vm, vm.podcasts.isEmpty(), "没有播客内容") {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                PageTitle("播客", "电台与声音专栏")
            }
            items(vm.podcasts.size) { i ->
                val p = vm.podcasts[i]
                // 播客是一台电台，不是歌单：点进去要的是节目列表（Route.Podcast），
                // 拿 Route.Playlist 会去请求一个不存在的歌单。
                PlaylistCard(p) { vm.go(Route.Podcast(p.id, p.name)) }
            }
        }
    }
}

/**
 * 音乐云盘（用户 m05071 第④点）。
 *
 * 只读列表：云盘里是用户自己上传的音频，上传/删除都留给官方客户端，误删原文件
 * 的代价太大。页头副标题给「总数 + 已用/总容量」——这两项只有云盘接口会回。
 */
@Composable
fun CloudPage(vm: AppViewModel) {
    val s = skin
    // 搜索在**已经取回来的列表**里过滤（用户 m05718），不再打接口：云盘一次也就
    // 几千首，本地过滤即时出结果，也不会把关键词当成全站搜索跳走。
    var searching by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    val key = q.trim()
    val shown = if (key.isEmpty()) vm.cloudSongs else vm.cloudSongs.filter {
        val hay = "${it.name} ${it.artists.joinToString(" ") { a -> a.name }} ${it.albumName}"
        hay.contains(key, ignoreCase = true)
    }

    // SAF 选文件：OpenDocument 而不是 GetContent —— 前者给的 Uri 有读权限、
    // 能拿到 DISPLAY_NAME，上传时文件名才有意义。
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.uploadToCloud(uri)
    }

    val sub = when {
        !vm.loggedIn -> "登录后同步你的云盘"
        vm.cloudSongs.isEmpty() -> "共 0 首 · 云盘占用 0 B"
        // 与「已下载音乐」页同一口径（用户 m05942 第③点）：总数拿不到就只显示占用
        vm.cloudMaxSize > 0 ->
            "共 ${vm.cloudCount} 首 · 云盘占用 ${fmtBytes(vm.cloudSize)} / ${fmtBytes(vm.cloudMaxSize)}"
        else -> "共 ${vm.cloudCount} 首 · 云盘占用 ${fmtBytes(vm.cloudSize)}"
    }

    Box(Modifier.fillMaxSize()) {
        PageShell(
            vm,
            vm.cloudSongs.isEmpty(),
            if (vm.loggedIn) "云盘里还没有歌，点右下角的「上传」把本机的歌传上去" else "登录后查看音乐云盘",
        ) {
            SongList(vm, shown, contextId = 0, contextName = "音乐云盘") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { PageTitle("音乐云盘", sub) }
                        // 右上角搜索按钮：展开 / 收起输入框，收起时清空关键词。
                        PillButton(if (searching) "收起" else "搜索") {
                            searching = !searching
                            if (!searching) q = ""
                        }
                        Spacer(Modifier.width(16.dp))
                    }
                    if (searching) {
                        OutlinedTextField(
                            value = q,
                            onValueChange = { q = it },
                            singleLine = true,
                            placeholder = { Text("在云盘里搜索歌名 / 歌手 / 专辑", color = s.text3, fontSize = 12.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = s.text0),
                        )
                        Text(
                            if (key.isEmpty()) "" else "${shown.size} / ${vm.cloudSongs.size}",
                            color = s.text3,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                        if (key.isNotEmpty() && shown.isEmpty()) {
                            Text(
                                "没有匹配的歌，换个关键词试试",
                                color = s.text2,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        // 底部上传条（用户 m05718）：浮在列表上方，带一层底色，滚到最后一首时
        // 文字不会和按钮叠在一起看不清。
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(s.bg0.copy(alpha = 0.92f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                vm.cloudUploadNote.ifEmpty {
                    when (val stage = vm.cloudUploading) {
                        null -> "把手机里的歌传到云盘，传完立刻出现在列表里"
                        else -> "正在${stage}…"
                    }
                },
                color = if (vm.cloudUploadNote.startsWith("上传失败")) s.accent else s.text2,
                fontSize = 11.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            PillButton(
                if (vm.cloudUploading != null) "上传中…" else "上传",
                primary = true,
            ) {
                if (vm.cloudUploading == null) pickAudio.launch(arrayOf("audio/*"))
            }
        }
    }
}

/** 字节数 -> 人类可读（云盘容量用）。 */
private fun fmtBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return (if (v >= 100) "%.0f".format(v) else "%.1f".format(v)) + " " + units[i]
}

@Composable
fun LikedPage(vm: AppViewModel) {
    PageShell(vm, vm.likedSongs.isEmpty(), "还没有收藏的歌曲") {
        SongList(
            vm,
            vm.likedSongs,
            contextId = -2,
            contextName = "我喜欢的音乐",
            batchable = true,
        ) { startPick ->
            // 「我喜欢的音乐」也是一个歌单，页头用同一套大图头部（第⑪点）。
            // 它没有 playlistDetail（走的是 likedSongs 接口），所以这里现拼一个：
            // 创建者就是自己；播放量接口没给，页头那一格自然不显示。
            val me = vm.me
            PlaylistHero(
                vm = vm,
                playlistId = -2,
                pl = com.netease.lite.model.Playlist(
                    id = -2,
                    name = "我喜欢的音乐",
                    trackCount = vm.likedSongs.size,
                    creatorId = me?.id ?: 0,
                    creatorName = me?.nickname ?: "",
                    creatorAvatar = me?.avatar.orEmpty(),
                    specialType = 5,
                ),
                songs = vm.likedSongs,
                onComment = { vm.go(Route.Comments("playlist", -2, "我喜欢的音乐")) },
                onMulti = startPick,
            )
        }
    }
}

@Composable
fun RecentPage(vm: AppViewModel) {
    PageShell(vm, vm.recent.isEmpty(), "听几首歌之后这里就会有内容") {
        SongList(vm, vm.recent, contextId = -3, contextName = "最近播放") {
            // 用户 m00932 第⑩点：与每日推荐同一个「大框框」。
            // 桌面端的最近播放框**没有**大数字日期那一格（`views.js:1373`），
            // 所以这里不传 date，只留标题 + 说明 + 右边两个动作。
            PageHero(
                title = "最近常听",
                subtitle = "这里记录你在本客户端播放过的歌曲，最新播放的排在最前",
                actions = {
                    PillButton("播放全部", primary = true) { vm.playSongs(vm.recent, 0, -3, "最近播放") }
                    Spacer(Modifier.width(8.dp))
                    PillButton("清空") { vm.clearRecent() }
                },
            )
        }
    }
}

/**
 * 已下载音乐。
 *
 * 两段内容，回答两个不同的问题：
 *   1. 上半段是**下载队列**（第②点新增），回答「我刚点的下载到哪一步了」；
 *   2. 下半段是**本机有哪些歌**，数据来自 [com.netease.lite.data.LocalIndex]。
 *
 * 这两件事共用一页是有意的：下载完的文件直接登记进本地索引，所以正在下的那些
 * 下完之后会自己出现在下面的列表里，用户不需要换页面去找。
 *
 * 落盘位置由设置页的「选择文件夹…」决定（用户 m00932 第①点）：选了就用那个文件夹，
 * 没选就落在公共音乐目录 `Music/网易云音乐 Lite/`（见 [Downloader] 的注释）。
 * 这一页顶部的「扫描本地音乐」是另一条路 —— 扫的是**别的 App / 早就存在**的歌。
 */
@Composable
fun DownloadsPage(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.scanLocal(uri)
    }
    LaunchedEffect(Unit) { vm.loadDownloads() }

    Column(Modifier.fillMaxSize()) {
        PageTitle(
            "已下载音乐",
            if (vm.localCount > 0) "本机索引里已有 ${vm.localCount} 首歌"
            else "还没有扫描过本机音乐",
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            PillButton("本地扫描", primary = true) { picker.launch(null) }
            Spacer(Modifier.width(10.dp))
            if (vm.downloadSongs.isNotEmpty()) {
                PillButton("播放全部") { vm.playSongs(vm.downloadSongs, 0, -4, "已下载音乐") }
                Spacer(Modifier.width(10.dp))
            }
            PillButton("清空索引") { vm.forgetLocal() }
        }

        // 用户 m07698 第①点：这一页只列**本机能播的歌**。
        // 原来的「下载记录」区块（含完成 / 失败 / 取消）和下面那行本机目录路径都删了：
        //   - 记录与「正在下载」页重复，同一件事在两边各说一遍；
        //   - 失败的歌留在这一页，用户会以为「下好了」，其实它放不出来（第②点把它们
        //     挪回「正在下载」页，那里有错误原因）；
        //   - 那行路径（`content://…/Music/网易云音乐 Lite`）对用户没有意义 ——
        //     他知道歌在哪也改不了，要改去设置页改。
        when {
            vm.pageLoading -> LoadingBlock("读取中")
            vm.downloadSongs.isEmpty() -> EmptyState(
                "本机索引里还没有歌",
                "在上面的歌曲菜单里点「下载」，文件会存到设置里那个下载地址（默认 " +
                    "${com.netease.lite.data.Downloader.DEFAULT_BASE_DIR}/）；" +
                    "也可以点「本地扫描」选一个目录。认歌靠文件里写的标签" +
                    "（歌名 / 歌手 / 专辑），对不上的不会硬凑。",
            )
            else -> SongList(vm, vm.downloadSongs, contextId = -4, contextName = "已下载音乐")
        }
    }
}

/**
 * 「正在下载」（用户 m03577 第①点）。
 *
 * 与 [DownloadsPage] 的分工：这一页只放**还在队列里**的任务，下完就自动从这里
 * 消失、出现在「已下载音乐」里。所以页面本身没有「已完成」那一段，也就没有
 * 「清除已完成」这种按钮 —— 队列清空之后这里就该是空的。
 *
 * 三个动作，每个都对应用户说的一句话：
 *   - 顶部「全部暂停 / 全部开始」：一次处理整条队列；
 *   - 顶部「清空」：这些都不下了；
 *   - 每行最右的垃圾桶：只取消这一条；
 *   - 点行本身：暂停 / 继续这一条。
 */
@Composable
fun DownloadingPage(vm: AppViewModel) {
    // 只留「还在队列里」的。DONE 立刻不算，因为用户的要求是「下完自动移出」。
    // CANCELED 也不显示（m07048 第③点）：用户点垃圾桶就是想让它消失，留在列表里
    // 还要自己再看一眼「已取消」再等它被清空，等于点了没用。ERROR 仍然留着 ——
    // 那是「这条挂了」，用户得看见原因才知道要不要重下。
    val tasks = vm.downloads.filter {
        it.state != com.netease.lite.data.Downloader.State.DONE &&
            it.state != com.netease.lite.data.Downloader.State.CANCELED
    }
    val running = tasks.any { it.state == com.netease.lite.data.Downloader.State.RUNNING }
    // 还能「开始」的：暂停中的，以及**失败的**（用户 m03593 第③点 —— 队列里的歌
    // 全都失败时也必须看得见「全部开始」，点一下把失败的歌按各自的断点重试）。
    val canResume = tasks.any {
        it.state == com.netease.lite.data.Downloader.State.PAUSED ||
            it.state == com.netease.lite.data.Downloader.State.ERROR
    }
    // 还能「暂停」的是在下的和排队的。
    // 全部取消之后这两个按钮都该消失 —— 否则「全部开始」按下去什么也不会发生，
    // 用户会以为按钮坏了。
    val canPause = tasks.any {
        it.state == com.netease.lite.data.Downloader.State.RUNNING ||
            it.state == com.netease.lite.data.Downloader.State.QUEUED
    }

    Column(Modifier.fillMaxSize()) {
        PageTitle(
            "正在下载",
            when {
                tasks.isEmpty() -> "队列里没有任务"
                // 分项报数而不是挑一个状态当标题：混合状态下（1 首在下、4 首排队、
                // 1 首暂停）只报「已暂停」是错的，用户会以为剩下那些不下了。
                else -> {
                    val nRun = tasks.count { it.state == com.netease.lite.data.Downloader.State.RUNNING }
                    val nQueue = tasks.count { it.state == com.netease.lite.data.Downloader.State.QUEUED }
                    val nPause = tasks.count { it.state == com.netease.lite.data.Downloader.State.PAUSED }
                    // 用户 m07698 第②点：失败的歌留在这一页，所以标题要把它数出来。
                    // 原来那格叫「已结束」，把失败和「下完了但还没移走」混在一起说，
                    // 用户看到「已结束 1 首」会以为下好了。
                    val nFail = tasks.count { it.state == com.netease.lite.data.Downloader.State.ERROR }
                    val parts = buildList {
                        if (nRun > 0) add("正在下载 $nRun 首")
                        if (nQueue > 0) add("等待 $nQueue 首")
                        if (nPause > 0) add("暂停 $nPause 首")
                        if (nFail > 0) add("失败 $nFail 首")
                    }
                    parts.joinToString(" · ").ifEmpty { "共 ${tasks.size} 首" }
                }
            },
        )
        if (tasks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 一个按钮切换两种语义：有在下的/排队的就「全部暂停」，否则只要还有
                // 暂停中或失败的，就是「全部开始」（失败的那些会按断点重试）。
                // 分成两个常驻按钮的话，永远有一个是灰的，用户还得先看一眼才知道点哪个。
                if (canPause) {
                    PillButton("全部暂停", primary = true) { vm.pauseAllDownloads() }
                } else if (canResume) {
                    PillButton("全部开始", primary = true) { vm.resumeAllDownloads() }
                }
                PillButton("清空") { vm.clearAllDownloads() }
            }
        }
        if (tasks.isEmpty()) {
            EmptyState(
                "没有正在下载的歌",
                "在歌曲菜单里点「下载」，任务会出现在这里；下完自动移到「已下载音乐」。",
            )
        } else {
            // 队列一般就几十条，不另开 LazyColumn —— 外层以后要整体滚动时
            // 嵌套滚动反而更难控（[DownloadsPage] 里同一个取舍）。
            LazyColumn(Modifier.fillMaxSize()) {
                items(tasks) { t -> DownloadingRow(t, vm) }
            }        }
    }
}

/**
 * 「正在下载」里的一行。
 *
 * 与 [DownloadRow] 的差别：这一行的整块区域是**可点的**（点一下暂停/继续），
 * 所以最右那颗垃圾桶必须是独立的点击区，不能跟着行走 —— 用 [IconButton] 包住，
 * 它在 38dp 的圆里自己吃事件。
 */
@Composable
private fun DownloadingRow(t: com.netease.lite.data.Downloader.Task, vm: AppViewModel) {
    val s = skin
    val st = t.state
    val isPaused = st == com.netease.lite.data.Downloader.State.PAUSED
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(s.radii.baseDp))
            .clickable {
                // 点行 = 暂停/继续/重试。
                // 失败的那条也响应（用户 m03593 第④点）：文件不完整那类错误会特意
                // 留着 `.part`，点击走 resume() 从断点接着下；点了没反应用户只会以为
                // 这一条永远下不完了。（界面上不再写「点一下重试」，见下面的文案。）
                when (st) {
                    com.netease.lite.data.Downloader.State.RUNNING,
                    com.netease.lite.data.Downloader.State.QUEUED,
                    -> vm.pauseDownload(t.key)
                    com.netease.lite.data.Downloader.State.PAUSED,
                    com.netease.lite.data.Downloader.State.ERROR,
                    -> vm.resumeDownload(t.key)
                    else -> Unit
                }
            }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.cover, 40.dp, s.radii.baseDp.coerceAtMost(8.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name,
                    color = s.text0,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (t.vip) {
                    Spacer(Modifier.width(5.dp))
                    VipTag()
                }
            }
            Text(
                when (st) {
                    // 失败只显示原因本身，不再后缀「点一下重试」（用户 m04384 第①点）：
                    // 提示语挤在失败原因后面，把真正的错因（「文件不完整（已下 … 字节）」）
                    // 挤得更长，而这一行本来就窄。点击行为不变，只是不说出来。
                    com.netease.lite.data.Downloader.State.ERROR ->
                        t.error.ifEmpty { "下载失败" }
                    com.netease.lite.data.Downloader.State.PAUSED -> "已暂停 · ${t.artistText}"
                    else -> t.artistText
                },
                color = when (st) {
                    com.netease.lite.data.Downloader.State.ERROR -> s.accent
                    else -> s.text3
                },
                fontSize = 11.5.sp,
                // 失败原因经常很长（`HTTP 403`、`EPERM: operation not permitted, open
                // 'D:\Music\…'`），只给一行会把最关键的尾巴截掉 —— 而那截正好是
                // 「哪个路径 / 哪个状态码」。所以失败时放开到三行（用户 m07698 第②点）。
                maxLines = if (st == com.netease.lite.data.Downloader.State.ERROR) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 进度线只在真的在写盘时画；暂停时留一条灰底，让人看出「进度停在这」。
            if (st == com.netease.lite.data.Downloader.State.RUNNING ||
                st == com.netease.lite.data.Downloader.State.PAUSED
            ) {
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(s.bgActive),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(t.percent / 100f)
                            .height(3.dp)
                            .background(if (isPaused) s.text3 else s.accent),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when (st) {
                com.netease.lite.data.Downloader.State.QUEUED -> "等待中"
                com.netease.lite.data.Downloader.State.RUNNING -> "${t.percent}%"
                com.netease.lite.data.Downloader.State.PAUSED -> "已暂停"
                com.netease.lite.data.Downloader.State.ERROR -> "失败"
                else -> "已取消"
            },
            color = when (st) {
                com.netease.lite.data.Downloader.State.RUNNING -> s.accent
                com.netease.lite.data.Downloader.State.ERROR -> s.accent
                else -> s.text3
            },
            fontSize = 11.sp,
        )
        // 垃圾桶：只取消这一条（用户 m03577 第①点要的图标就是垃圾桶，
        // 所以这里用 Icons.Trash 而不是原来那颗 ×）。
        //
        // 用 remove 而不是 cancel（m07048 第③点）：cancel 只把状态改成 CANCELED，
        // 任务还留在列表里，用户点完垃圾桶还会看见一行「已取消」挂着，等于没反应。
        // remove 会顺手 cancel 掉在下的协程再把这条从队列里摘掉 —— 点一下就是
        // 「它消失了」。失败的那条同样走 remove：重下要走歌曲菜单，留在这里
        // 只会越积越多（用户 m07698 第②点要求它留在这页显示原因，不是永久留着）。
        IconButton(Icons.Trash, s.text3, { vm.removeDownload(t.key) }, size = 16.dp)
    }
}

@Composable
fun SettingsPage(vm: AppViewModel) {
    var page by remember { mutableStateOf("play") }
    // 五页与桌面版设置面板一一对应（`settings.js` 的 PAGES）。
    // 原来这里是一整条长滚动页，只有皮肤/音质/一个开关 —— 用户说「设置项补齐」，
    // 补的不是几行字，而是按用途分组、每组一页。
    val pages = listOf(
        "play" to "播放",
        "download" to "下载",
        "look" to "外观",
        "side" to "侧边栏",
        "about" to "关于",
    )
    Column(Modifier.fillMaxSize()) {
        PageTitle("设置")
        // 分页条。五页横排会挤，所以可横滑；选中的那页用整块强调色，
        // 与桌面版的分页签同一套写法（`.set-tab.on`）。
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((key, label) in pages) {
                PillButton(label, primary = page == key) { page = key }
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            when (page) {
                "play" -> PlaySettings(vm)
                "download" -> DownloadSettings(vm)
                "look" -> LookSettings(vm)
                "side" -> SideSettings(vm)
                else -> AboutSettings(vm)
            }
        }
    }
}

// ------------------------------------------------------------------ 设置：播放

@Composable
private fun PlaySettings(vm: AppViewModel) {
    GroupTitle("默认音质")
    for ((key, label) in Prefs.QUALITY_LABEL) {
        SettingRow(
            title = label,
            sub = when (key) {
                "standard" -> "128kbps MP3，最省流量"
                "higher" -> "192kbps MP3"
                "exhigh" -> "320kbps MP3，多数歌的最好选择"
                else -> "FLAC 无损，需要会员"
            },
            checked = vm.quality == key,
            onClick = { vm.useQuality(key) },
        )
    }

    GroupTitle("播放")
    SettingSwitch(
        title = "重启后接着上次的位置",
        sub = "关掉应用再打开，进度条会停在上次听到的地方",
        checked = vm.prefs.rememberProgress,
        onChange = { vm.prefs.rememberProgress = it },
    )
    SettingSwitch(
        title = "播放失败自动跳过",
        sub = "一首歌取不到流时自动下一首，而不是停在错误上",
        checked = vm.autoSkip,
        onChange = { vm.updateAutoSkip(it) },
    )
    SettingSwitch(
        title = "启动时自动签到",
        sub = "打开应用就顺手把每日签到点掉",
        checked = vm.autoCheckin,
        onChange = { vm.updateAutoCheckin(it) },
    )

    GroupTitle("启动时打开")
    // 「上次离开的那一页」放最后一项：它是唯一一个不是具体页面的选项。
    for ((key, label) in Prefs.START_PAGES) {
        SettingRow(title = label, sub = "", checked = vm.startPage == key, onClick = { vm.updateStartPage(key) })
    }

    GroupTitle("缓存")
    for (mb in listOf(256, 512, 1024, 2048)) {
        SettingRow(
            title = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB",
            sub = if (mb == 2048) "上限" else "",
            checked = vm.cacheMax == mb,
            onClick = { vm.updateCacheMax(mb) },
        )
    }
}

// ------------------------------------------------------------------ 设置：下载

@Composable
private fun DownloadSettings(vm: AppViewModel) {
    GroupTitle("默认下载音质")
    for ((key, label) in Prefs.QUALITY_LABEL) {
        SettingRow(
            title = label,
            sub = if (key == "lossless") "FLAC，需要会员" else "",
            checked = vm.downloadQuality == key,
            onClick = { vm.updateDownloadQuality(key) },
        )
    }

    GroupTitle("分类方式")
    for ((key, label) in listOf("none" to "全部放在一起", "artist" to "按歌手分文件夹", "album" to "按专辑分文件夹")) {
        SettingRow(title = label, sub = "", checked = vm.downloadOrganize == key, onClick = { vm.updateDownloadOrganize(key) })
    }

    GroupTitle("文件名格式")
    var tpl by remember { mutableStateOf(vm.downloadTemplate) }
    OutlinedTextField(
        value = tpl,
        onValueChange = { tpl = it },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = skin.text0),
    )
    // 占位符与桌面版 `download.js` 的 `applyTemplate` 是同一套，改一处就得改两处。
    Text(
        "可用占位符：{首歌手名} {歌曲名} {歌手名} {专辑名} {音质}",
        color = skin.text3,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("保存", primary = true) {
            // 空白模板会让所有文件都变成「 - .mp3」，直接回退默认值比存下去好。
            vm.updateDownloadTemplate(tpl.ifBlank { Prefs.DEFAULT_DL_TEMPLATE })
            tpl = vm.downloadTemplate
            // 用户 m03577 第③点：点保存要有回音。这个输入框失焦后看不出任何变化
            // （值本来就在框里），不弹一句「已保存」用户会以为没点上，反复点。
            vm.notify("已保存")
        }
        PillButton("恢复默认") {
            vm.updateDownloadTemplate(Prefs.DEFAULT_DL_TEMPLATE)
            tpl = Prefs.DEFAULT_DL_TEMPLATE
            vm.notify("已恢复默认格式")
        }
    }

    GroupTitle("下载地址")
    // 用户 m00932 第①点：这一节只留一个按钮，让用户自己挑文件夹。
    //
    // 之前这里是「相对路径输入框 + 保存」和一个 SAF 选目录并存。路径那条路受
    // 系统媒体库限制 —— 首段必须是音频表认的那六个（见 `Downloader.PUBLIC_ROOTS`），
    // 用户填 `Download/` 之类会下不下来，得靠一句系统原话才解释得清。现在整条删掉，
    // 只留 SAF：系统自己弹文件夹选择器，选哪儿存哪儿（SD 卡、U 盘、别的 App 的
    // 目录都行），不受那套首段白名单约束。
    //
    // 没选文件夹时仍走默认的 `Music/网易云音乐 Lite`，所以「不选也能用」。
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.updateDownloadTree(uri.toString())
    }
    // 树 URI 是 `content://…/tree/primary%3AMusic%2FLite`，直接显示没人看得懂；
    // `treePathOf` 把它还原成「内部存储/Music/Lite」。纯字符串处理，不查 provider，
    // 所以放在 composition 里是安全的。
    val treePath = remember(vm.downloadTree) { treePathOf(vm.downloadTree) }
    val hasTree = vm.downloadTree.isNotEmpty()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (hasTree) treePath.substringAfterLast('/').ifEmpty { treePath } else "默认位置",
                color = skin.text0,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (hasTree) treePath else Downloader.DEFAULT_BASE_DIR,
                color = if (hasTree) skin.accent else skin.text3,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        PillButton(if (hasTree) "更改文件夹…" else "选择文件夹…", primary = true) {
            // 已经选过目录时，让系统选择器**直接开在那个目录里**，而不是回到
            // 上次的记忆位置（可能是根目录，也可能是别的 App 的目录）。用户点
            // 「更改文件夹…」多半是想在同一片区域里换个夹子，从当前目录起步最省事；
            // 想换到别处也能靠左边的面包屑往上走。
            //
            // `OpenDocumentTree` 的输入参数就是「初始位置」：SDK ≥ 26 时它会把
            // 这个 Uri 塞进 `EXTRA_INITIAL_URI`（见 androidx.activity 1.8.2 的
            // `ActivityResultContracts$OpenDocumentTree.createIntent`），低版本直接忽略。
            // 只在**已经有树**时传：没授权过的 Uri 系统不一定认，传了反而可能开成空白页。
            treePicker.launch(initialPickerUri(vm.downloadTree))
        }
    }
    if (hasTree) {
        // 只有一个主按钮，但必须留一条回到默认的路，否则用户选错了就再也回不去。
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            PillButton("恢复默认位置") { vm.updateDownloadTree("") }
        }
    }
    Text(
        "下载的歌会存进这个文件夹，点「" + (if (hasTree) "更改文件夹…" else "选择文件夹…") +
            "」在系统选择器里挑一个就行（SD 卡、U 盘都行）；不选则存到 " + Downloader.DEFAULT_BASE_DIR + "。",
        color = skin.text3,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Text(
        "改地址只影响之后下载的歌，已经下好的不会被搬走；下载完成的歌会自动登记进本地索引，可直接离线播放。",
        color = skin.text3,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * 把 SAF 的树 URI 说成人话。
 *
 * 树 URI 形如 `content://com.android.externalstorage.documents/tree/primary%3AMusic%2FLite`，
 * 显示出来没法看。真正的路径在**文档 id** 里（`primary:Music/Lite`）：
 * `primary` 是内置存储，别的十六进制串是 SD 卡/OTG 的卷 id。
 *
 * 只做字符串处理，不碰 `DocumentFile` —— 那个每次都会去查 provider，
 * 放在 composition 里会一顿一顿的。
 */
private fun treePathOf(treeUri: String): String {
    if (treeUri.isEmpty()) return ""
    val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull() ?: return ""
    val parts = id.split(":", limit = 2)
    val volume = when (parts[0]) {
        "primary" -> "内部存储"
        "home" -> "文档"
        else -> parts[0]
    }
    val rest = parts.getOrNull(1).orEmpty().trim('/')
    return if (rest.isEmpty()) volume else "$volume/$rest"
}

/**
 * 树 URI → 选择器的「初始位置」。
 *
 * 两者**不是同一种 URI**：树是 `…/tree/<id>`，而 `EXTRA_INITIAL_URI` 要的是
 * 文档 `…/document/<id>`。实测把树 URI 直接塞进去，选择器会退回根目录（等于没传）；
 * 换成文档 URI 才会真的开在那一层。
 *
 * 返回 null 表示「别传」，选择器按系统记忆的位置打开。
 */
private fun initialPickerUri(treeUri: String): Uri? {
    if (treeUri.isEmpty()) return null
    val uri = Uri.parse(treeUri)
    val authority = uri.authority ?: return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    return runCatching { DocumentsContract.buildDocumentUri(authority, id) }.getOrNull()
}

// ------------------------------------------------------------------ 设置：外观

@Composable
private fun LookSettings(vm: AppViewModel) {
    GroupTitle("皮肤")
    // 五款皮肤横着排，格子里左半强调色、右半底色 —— 一眼能看出这款长什么样。
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (s in SKINS) {
            val on = vm.skin == s.id
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.useSkin(s.id) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(s.swatch.second),
                ) {
                    Box(Modifier.weight(1f).fillMaxSize().background(s.swatch.first))
                    Box(Modifier.weight(1f).fillMaxSize().background(s.swatch.second))
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    s.name,
                    color = if (on) s.accent else skin.text2,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
    Text(
        skinById(vm.skin).desc,
        color = skin.text3,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )

    GroupTitle("界面")
    SettingSwitch(
        title = "界面动效",
        sub = "页面切换的过渡动画；关掉更省电",
        checked = vm.animations,
        onChange = { vm.updateAnimations(it) },
    )
    SettingSwitch(
        title = "歌曲列表显示封面",
        sub = "",
        checked = vm.listCover,
        onChange = { vm.updateListCover(it) },
    )
    SettingSwitch(
        title = "侧栏歌单显示封面",
        sub = "关掉后统一用唱片图标",
        checked = vm.sideCover,
        onChange = { vm.updateSideCover(it) },
    )
}

// ------------------------------------------------------------------ 设置：侧边栏

@Composable
private fun SideSettings(vm: AppViewModel) {
    GroupTitle("侧栏条目")
    Text(
        "关掉的条目不会出现在侧栏，页面本身仍然可达（比如从搜索结果进歌手页）。",
        color = skin.text3,
        fontSize = 11.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    for ((key, label) in Prefs.SIDE_ITEMS) {
        SettingSwitch(
            title = label,
            sub = "",
            checked = key !in vm.sideHidden,
            onChange = { vm.toggleSideItem(key) },
        )
    }
}

// ------------------------------------------------------------------ 设置：关于

@Composable
private fun AboutSettings(vm: AppViewModel) {
    var showLogout by remember { mutableStateOf(false) }
    GroupTitle("账号")
    SettingRow(
        title = if (vm.loggedIn) (vm.me?.nickname ?: "已登录") else "未登录",
        sub = if (vm.loggedIn) "点这里退出登录" else "在启动页扫码或手机号登录",
        checked = false,
        onClick = { if (vm.loggedIn) showLogout = true },
    )

    GroupTitle("关于")
    // 版本号从 PackageManager 读，不再写死 —— 写死的结果就是升级后还显示 1.0.0。
    val ctx = LocalContext.current
    val version = remember {
        runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    val open = { url: String ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        Unit
    }
    SettingRow(title = "版本", sub = version, checked = false, onClick = {})
    // 仓库地址（用户 m05071 第③点）：点一下用浏览器打开。
    SettingRow(
        title = "GitHub 仓库",
        sub = "github.com/Airisey/netease-lite-android",
        checked = false,
        onClick = { open("https://github.com/Airisey/netease-lite-android") },
    )
    SettingRow(
        title = "电脑版",
        sub = "github.com/Airisey/netease-lite",
        checked = false,
        onClick = { open("https://github.com/Airisey/netease-lite") },
    )
    SettingRow(title = "恢复默认设置", sub = "皮肤与账号不受影响", checked = false, onClick = { vm.resetSettings() })
    Text(
        "网易云音乐 Lite · Android\n非官方客户端，仅供学习交流。",
        color = skin.text3,
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
    )

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录？") },
            text = { Text("歌单与收藏会从界面上移除，本地索引和设置保留。") },
            confirmButton = {
                TextButton(onClick = { vm.logout(); showLogout = false }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
fun SearchPage(vm: AppViewModel) {
    var kw by remember { mutableStateOf(vm.searchKeyword) }
    // tab 用服务端的 type 编号：1 单曲 / 1000 歌单 / 100 歌手 / 10 专辑 / 1002 用户。
    // 桌面版搜索页也是这几栏，编号直接对着接口，省掉一层映射。
    var tab by remember { mutableStateOf(vm.searchType) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val req = remember { FocusRequester() }
            // 用户 m15635 第⑤点：软键盘上的「搜索 / 回车」键要能直接搜。
            // 原来只有右边那颗「搜索」按钮，用户习惯性按回车时什么都没发生。
            // imeAction=Search 把右下角键换成放大镜，onSearch 走同一条 vm.search。
            val kb = LocalSoftwareKeyboardController.current
            fun submit() {
                if (kw.isNotBlank()) {
                    vm.search(kw, tab)
                    kb?.hide()
                }
            }
            OutlinedTextField(
                value = kw,
                onValueChange = { kw = it },
                singleLine = true,
                placeholder = { Text("搜歌曲 / 歌手 / 歌单 / 专辑 / 用户", fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(req)
                    // 硬件键盘/外接键盘的 Enter 走不到 IME 那条路（没有软键盘就没有
                    // ImeAction.Search 可触发），所以在按键层再兜一次。两者都调 submit()。
                    .onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown &&
                            (e.key == Key.Enter || e.key == Key.NumPadEnter)
                        ) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
            )
            Spacer(Modifier.width(8.dp))
            PillButton("搜索", primary = true) { submit() }
        }
        Row(
            Modifier
                .padding(horizontal = 12.dp, vertical = 2.dp)
                // 加「用户」之后是五颗按钮，窄屏（360dp 那一档）一行放不下，
                // 所以整条可横向滚动，而不是让最后一颗被裁掉一半。
                .horizontalScroll(rememberScrollState()),
        ) {
            listOf(1 to "单曲", 1000 to "歌单", 100 to "歌手", 10 to "专辑", 1002 to "用户").forEach { (t, label) ->
                if (t != 1) Spacer(Modifier.width(8.dp))
                PillButton(label, primary = tab == t) {
                    tab = t
                    if (kw.isNotBlank()) vm.search(kw, t)
                }
            }
        }
        when (tab) {
            1 -> PageShell(vm, vm.searchSongs.isEmpty(), "没有搜到歌曲") {
                SongList(vm, vm.searchSongs, contextId = -5, contextName = "搜索：$kw")
            }

            1000 -> PageShell(vm, vm.searchPlaylists.isEmpty(), "没有搜到歌单") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.searchPlaylists.size) { i ->
                        val p = vm.searchPlaylists[i]
                        PlaylistCard(p) { vm.go(Route.Playlist(p.id, p.name)) }
                    }
                }
            }

            100 -> PageShell(vm, vm.searchArtists.isEmpty(), "没有搜到歌手") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.searchArtists.size) { i ->
                        val a = vm.searchArtists[i]
                        ArtistCard(a) { vm.go(Route.Artist(a.id, a.name)) }
                    }
                }
            }

            // 用户（m03593 第②点）：搜索页新增的一栏，点进任意一位就是他的个人主页。
            // 用和「关注 / 粉丝」列表同一个 [UserRow]，扫一列人比网格卡片快。
            1002 -> PageShell(vm, vm.searchUsers.isEmpty(), "没有搜到用户") {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.searchUsers.size) { i ->
                        val u = vm.searchUsers[i]
                        UserRow(u) { vm.go(Route.User(u.id, u.nickname)) }
                    }
                }
            }

            else -> PageShell(vm, vm.searchAlbums.isEmpty(), "没有搜到专辑") {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.searchAlbums.size) { i ->
                        val al = vm.searchAlbums[i]
                        Column(
                            Modifier
                                .width(112.dp)
                                .clickable { vm.go(Route.Album(al.id, al.name)) },
                        ) {
                            HardShadowBox(radius = skin.radii.baseDp) {
                                Box(Modifier.skinBorder(skin.radii.baseDp).clip(RoundedCornerShape(skin.radii.baseDp))) {
                                    Cover(al.cover, 112.dp, skin.radii.baseDp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                al.name,
                                color = skin.text0,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp,
                            )
                            if (!al.artistText.isNullOrEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    al.artistText,
                                    color = skin.text3,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistPage(vm: AppViewModel, id: Long) {
    val pl = vm.playlistDetail
    PageShell(vm, vm.playlistSongs.isEmpty(), "这个歌单里还没有歌") {
        SongList(
            vm,
            vm.playlistSongs,
            contextId = id,
            contextName = pl?.name ?: "歌单",
            playlistId = id,
            batchable = true,
        ) { startPick ->
            PlaylistHero(
                vm = vm,
                playlistId = id,
                pl = pl,
                songs = vm.playlistSongs,
                onComment = { vm.go(Route.Comments("playlist", id, pl?.name ?: "歌单")) },
                onMulti = if (pl?.subscribed == true) null else startPick,
            )
        }
    }
}

/**
 * 歌单页顶部的大图头部（第⑪点）。
 *
 * 桌面版是「方形封面 + 右侧信息」的横排（`.pl-head`）。搬到手机上照抄会把封面
 * 挤到 110dp、信息挤成三行小字 —— 竖屏的宽度全给了封面，歌单名反而读不全。
 * 这里改成：**第一首歌的封面铺满整宽当底图**（叠一层从透明到页面底色的渐变，
 * 保证压在图片上的白字可读），信息压在图片下半部分。
 *
 * 底图用「第一首歌的封面」而不是歌单封面：自定义歌单的封面在网易云里常常是
 * 一张默认的灰格子图，而用户记得住的是「这个歌单第一首是什么」。
 */
@Composable
private fun PlaylistHero(
    vm: AppViewModel,
    playlistId: Long,
    pl: com.netease.lite.model.Playlist?,
    songs: List<Song>,
    /** 「评论」按钮跳哪儿 —— 歌单页与「我喜欢的音乐」页的目标不同。 */
    onComment: () -> Unit,
    /**
     * 「多选」入口。null 表示这个列表不给多选。
     *
     * 收藏来的歌单（别人的）不给多选：多选里那两颗能改内容的按钮（移动 / 移除）
     * 对它都是无效的，留着入口只会让用户点出一串失败提示。
     */
    onMulti: (() -> Unit)?,
) {
    val s = skin
    val bg = songs.firstOrNull()?.cover?.takeIf { it.isNotEmpty() } ?: pl?.cover.orEmpty()
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(238.dp),
        ) {
            if (bg.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(bg)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 渐变遮罩：上半透明保住图，下半交给页面底色，让标题读得清。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to s.bg0.copy(alpha = 0.28f),
                            0.46f to s.bg0.copy(alpha = 0.72f),
                            1.0f to s.bg0,
                        ),
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // 大封面也要带皮肤形态：桌面版街机风给 `.pl-cover` 加的是
                // 「2px 墨黑描边 + 4px 硬投影」，其余四款 hardShadow=0、
                // skinBorder 退化成 1px 细线，画出来和改动前一致。
                HardShadowBox(radius = s.radii.baseDp) {
                    Box(Modifier.skinBorder(s.radii.baseDp)) {
                        Cover(
                            if (bg.isEmpty()) null else bg,
                            104.dp,
                            s.radii.baseDp,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    // 标签：我喜欢的音乐用红心，其余用「歌单」。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (pl?.isLiked == true) Icons.HeartFilled else Icons.Queue,
                            null,
                            tint = s.accent,
                            modifier = Modifier.size(11.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (pl?.isLiked == true) "我喜欢的音乐" else "歌单",
                            color = s.accent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        pl?.name ?: "歌单",
                        color = s.text0,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    // 创建者头像 + 昵称 + 创建时间，与桌面版 `.pl-creator` 同一行。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!pl?.creatorAvatar.isNullOrEmpty()) {
                            Avatar(pl?.creatorAvatar, 20.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            pl?.creatorName?.takeIf { it.isNotEmpty() } ?: "网易云音乐",
                            color = s.text1,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val time = fmtDate(pl?.createTime ?: 0)
                        if (time.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("$time 创建", color = s.text3, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 歌曲数与播放量：与桌面版 `.pl-stats` 一致，数字加粗。
                    Row {
                        Text("${pl?.trackCount ?: songs.size}", color = s.text0, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(" 首", color = s.text2, fontSize = 12.sp)
                        val pc = pl?.playCount ?: 0
                        if (pc > 0) {
                            Spacer(Modifier.width(14.dp))
                            Text("播放 ", color = s.text2, fontSize = 12.sp)
                            Text(fmtCount(pc), color = s.text0, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        // 动作行单独一排：五颗按钮挤在封面右边的 100dp 里会全部折成两行。
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton("播放全部", primary = true) {
                vm.playSongs(songs, 0, playlistId, pl?.name ?: "歌单")
            }
            Spacer(Modifier.width(9.dp))
            PillButton("随机播放") { vm.playShuffle(songs, playlistId, pl?.name ?: "歌单") }
            Spacer(Modifier.width(9.dp))
            if (pl != null && !pl.isLiked) {
                PillButton(if (pl.subscribed) "已收藏" else "收藏") {
                    vm.setPlaylistSubscribed(pl.id, !pl.subscribed)
                }
                Spacer(Modifier.width(9.dp))
            }
            PillButton("评论", onClick = onComment)
            // 第⑪点：多选挪到「评论」右边，不再单独占一行。
            if (onMulti != null) {
                Spacer(Modifier.width(9.dp))
                PillButton("多选", onClick = onMulti)
            }
        }
    }
}


@Composable
fun AlbumPage(vm: AppViewModel, id: Long) {
    val a = vm.albumDetail
    // 「专辑简介」默认折叠成三行，点「展开」看全文（第⑮点）。
    // 有的专辑简介上千字，直接铺开会把歌单挤到屏幕外；但折叠起来又不该
    // 让人以为「这张专辑没有简介」，所以留一个明确的展开入口。
    var bioOpen by remember(id) { mutableStateOf(false) }
    PageShell(vm, vm.albumSongs.isEmpty(), "这张专辑里还没有歌") {
        SongList(vm, vm.albumSongs, contextId = id, contextName = a?.name ?: "专辑") {
            HeaderBlock(
                cover = a?.cover,
                title = a?.name ?: "专辑",
                sub = buildString {
                    if (!a?.artistText.isNullOrEmpty()) append("${a!!.artistText} · ")
                    append("${vm.albumSongs.size} 首")
                    // 第⑮点：发行时间接在「N 首」后面。`publishTime` 是毫秒时间戳，
                    // 老专辑有些只到年份（接口回同一天的 00:00），fmtDate 统一成 YYYY-MM-DD。
                    val time = fmtDate(a?.publishTime ?: 0)
                    if (time.isNotEmpty()) append(" · $time 发行")
                },
                actions = {
                    PillButton("播放全部", primary = true) {
                        vm.playSongs(vm.albumSongs, 0, id, a?.name ?: "专辑")
                    }
                    Spacer(Modifier.width(10.dp))
                    PillButton("评论") {
                        vm.go(Route.Comments("album", id, a?.name ?: "专辑"))
                    }
                },
            )
            val desc = a?.description.orEmpty().trim()
            if (desc.isNotEmpty()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("专辑简介", color = skin.text0, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (bioOpen) "收起" else "展开",
                            color = skin.accent,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { bioOpen = !bioOpen },
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        desc,
                        color = skin.text2,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        maxLines = if (bioOpen) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistPage(vm: AppViewModel, id: Long) {
    val a = vm.artistDetail
    // 「歌手简介」弹窗（第⑭点）。简介可能很长（有的歌手几千字），
    // 所以对话框正文自己带滚动，不指望 AlertDialog 撑开。
    var showBio by remember { mutableStateOf(false) }
    if (showBio) {
        val s = skin
        AlertDialog(
            onDismissRequest = { showBio = false },
            containerColor = s.bg1,
            title = { Text("${a?.name ?: "歌手"} 简介", color = s.text0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                val bio = a?.briefDesc.orEmpty()
                if (bio.isBlank()) {
                    Text("这位歌手还没有填写简介", color = s.text3, fontSize = 13.sp)
                } else {
                    Text(
                        bio,
                        color = s.text1,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showBio = false }) { Text("关闭", color = s.accent) } },
        )
    }
    PageShell(vm, vm.artistSongs.isEmpty(), "没有找到这位歌手的歌") {
        SongList(
            vm,
            vm.artistSongs,
            contextId = id,
            contextName = a?.name ?: "歌手",
            // 热门歌曲下面是「查看全部歌曲」那一栏：作品超过 100 首才给 ——
            // 总共就 12 首的歌手，多出来的入口点进去和这一页一模一样。
            footer = if ((a?.musicSize ?: 0) > 100) {
                { ViewAllSongsRow(a?.musicSize ?: 0) { vm.go(Route.ArtistAll(id, a?.name ?: "")) } }
            } else {
                null
            },
        ) {
            // 歌手页的头部把头像也带上：桌面版是圆形头像 + 名字 + 三行数字，
            // 只有一列歌的时候根本认不出这是谁的页。
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(a?.avatar, 84.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        a?.name ?: "歌手",
                        color = skin.text0,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!a?.alias.isNullOrEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            a.alias.joinToString(" / "),
                            color = skin.text2,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (a != null && a.musicSize > 0) append("${a.musicSize} 首歌")
                            if (a != null && a.albumSize > 0) append(" · ${a.albumSize} 张专辑")
                            if (a != null && a.mvSize > 0) append(" · ${a.mvSize} 个 MV")
                        },
                        color = skin.text3,
                        fontSize = 11.5.sp,
                    )
                }
                // 用户 m00932 第⑭点：关注按钮从下面那排按钮里挪到**头像这一行的最右侧**。
                // 理由：下面那排已经有「播放全部 / 歌手简介」两个「看内容」的动作，
                // 关注混在里面找起来慢；而「关注」和唱歌的人绑在一起，
                // 贴在头像右边最符合直觉（用户页第⑤点也是同一个位置）。
                if (a != null) {
                    Spacer(Modifier.width(10.dp))
                    PillButton(if (a.followed) "已关注" else "关注", primary = !a.followed) {
                        vm.followArtist(a.id, !a.followed)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                PillButton("播放全部", primary = true) {
                    vm.playSongs(vm.artistSongs, 0, id, a?.name ?: "歌手")
                }
                Spacer(Modifier.width(10.dp))
                // 第⑭点：删掉「热门单曲」，换成「歌手简介」。
                // 热歌本来就排在这一页最上面（`artistSongs` 按 hot 排序），
                // 那颗按钮等于把用户已经看到的列表再播一遍。
                // 关注按钮已按用户 m00932 第⑭点挪到上面的头像行。
                PillButton("歌手简介") { showBio = true }
            }
            if (vm.artistAlbums.isNotEmpty()) {
                // 专辑墙用横向滚动，不占整屏高度（桌面版也是横向一排）。
                SectionTitle("专辑", "共 ${vm.artistAlbums.size} 张")
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(vm.artistAlbums.size) { i ->
                        val al = vm.artistAlbums[i]
                        Column(
                            Modifier
                                .width(112.dp)
                                .clickable { vm.go(Route.Album(al.id, al.name)) },
                        ) {
                            HardShadowBox(radius = skin.radii.baseDp) {
                                Box(Modifier.skinBorder(skin.radii.baseDp).clip(RoundedCornerShape(skin.radii.baseDp))) {
                                    Cover(al.cover, 112.dp, skin.radii.baseDp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                al.name,
                                color = skin.text0,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            SectionTitle("热门歌曲", "共 ${vm.artistSongs.size} 首")
        }
    }
}

/**
 * 歌手页热门歌曲最下面那一栏「查看全部歌曲」。
 *
 * 做成整行可点、右侧带箭头：它是「进下一页」而不是「当前页的动作」，
 * 跟旁边那些药丸按钮混在一排里会看不出区别。
 */
@Composable
private fun ViewAllSongsRow(total: Int, onClick: () -> Unit) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .skinBorder(s.radii.baseDp)
            .clip(RoundedCornerShape(s.radii.baseDp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("查看全部歌曲", color = s.text0, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text("共 $total 首", color = s.text3, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("›", color = s.text2, fontSize = 18.sp)
    }
}

/**
 * 歌手的「全部歌曲」页（[Route.ArtistAll]）。
 *
 * 入口是歌手页热门歌曲最下面那一栏（只在总曲数 > 100 时出现）：热门这一页最多
 * 就 50 首，作品多的歌手剩下的全在这一页里，一页 100 首往下翻。
 * 做成分页而不是一次拉完 —— 两三千首一次进来，进页面先卡住，还没人真会翻到底。
 */
@Composable
fun ArtistAllSongsPage(vm: AppViewModel, id: Long, name: String) {
    val who = name.ifEmpty { "歌手" }
    PageShell(vm, vm.artistAllSongs.isEmpty() && !vm.artistAllLoading, "这位歌手还没有歌") {
        // 第一页还没回来时，别让页头先写「已加载 0 首」再跳成 100 —— 先给个加载块。
        if (vm.artistAllSongs.isEmpty() && vm.artistAllLoading) {
            LoadingBlock("正在取全部歌曲")
            return@PageShell
        }
        SongList(
            vm,
            vm.artistAllSongs,
            contextId = id,
            contextName = who,
            header = {
                PageTitle(
                    "全部歌曲",
                    if (vm.artistAllTotal > 0) {
                        "共 ${vm.artistAllTotal} 首，已加载 ${vm.artistAllSongs.size} 首"
                    } else {
                        "已加载 ${vm.artistAllSongs.size} 首"
                    },
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    PillButton("播放全部", primary = true) {
                        vm.playSongs(vm.artistAllSongs, 0, id, who)
                    }
                    Spacer(Modifier.width(10.dp))
                    PillButton("下载全部") { vm.downloadMany(vm.artistAllSongs) }
                }
            },
            footer = {
                if (vm.artistAllMore || vm.artistAllLoading) {
                    LoadMoreRow(
                        loading = vm.artistAllLoading,
                        label = if (vm.artistAllTotal > 0) {
                            "加载更多（已 ${vm.artistAllSongs.size} / 共 ${vm.artistAllTotal} 首）"
                        } else {
                            "加载更多"
                        },
                        onClick = { vm.loadMoreArtistSongs() },
                    )
                } else {
                    Text(
                        "已经到底了，共 ${vm.artistAllSongs.size} 首",
                        color = skin.text3,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                    )
                }
            },
        )
    }
}

/**
 * 列表底部那颗「加载更多」。
 *
 * 加载中转圈、同时把点击吞掉（[onClick] 里 vm 也会拦一次，这里是别让按钮看着能点）。
 */
@Composable
private fun LoadMoreRow(loading: Boolean, label: String, onClick: () -> Unit) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .skinBorder(s.radii.baseDp)
            .clip(RoundedCornerShape(s.radii.baseDp))
            .clickable(enabled = !loading) { onClick() }
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = s.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (loading) "正在加载…" else label,
            color = if (loading) s.text3 else s.accent,
            fontSize = 13.sp,
        )
    }
}

/**
 * 用户的关注 / 粉丝列表。
 *
 * 桌面版这两页在用户页的「关注 / 粉丝」两个数字上点进去，Android 侧沿同一条路径。
 */
@Composable
fun UserListPage(vm: AppViewModel, uid: Long, kind: String) {
    val title = if (kind == "followeds") "粉丝" else "关注"
    PageShell(vm, vm.userListUsers.isEmpty(), "这里还没有人") {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                PageTitle(title, "共 ${vm.userListUsers.size} 人")
            }
            items(vm.userListUsers.size) { i ->
                val u = vm.userListUsers[i]
                UserRow(u) { vm.go(Route.User(u.id, u.nickname)) }
            }
        }
    }
}

/**
 * 播客电台的节目列表。
 *
 * 桌面版把电台当歌单：头部是电台封面/主播/节目数，下面一列节目。节目在接口里
 * 是 `program`，[com.netease.lite.api.Endpoints.podcastPrograms] 已经把它们
 * 解析成 [Song]（`mainSong`），所以这里可以直接复用 [SongList] —— 播放、收藏、
 * 加歌单这些行为全部自动一致，不必为播客再写一套。
 */
@Composable
fun PodcastPage(vm: AppViewModel, id: Long) {
    val r = vm.podcastRadio
    PageShell(vm, vm.podcastSongs.isEmpty(), "这个电台还没有节目") {
        SongList(vm, vm.podcastSongs, contextId = id, contextName = r?.name ?: "播客") {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(r?.cover, 96.dp, skin.radii.baseDp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r?.name ?: "播客",
                        color = skin.text0,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        buildString {
                            if (!r?.djName.isNullOrEmpty()) append("主播 ${r!!.djName}")
                            if (r != null && r.programCount > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${r.programCount} 期")
                            }
                        },
                        color = skin.text2,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        PillButton("播放全部", primary = true) {
                            vm.playSongs(vm.podcastSongs, 0, id, r?.name ?: "播客")
                        }
                        if (r != null && r.djId > 0) {
                            Spacer(Modifier.width(10.dp))
                            PillButton("主播主页") { vm.go(Route.User(r.djId, r.djName)) }
                        }
                    }
                }
            }
            if (!r?.desc.isNullOrBlank()) {
                Text(
                    r.desc,
                    color = skin.text2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            SectionTitle("节目", "共 ${vm.podcastSongs.size} 期")
        }
    }
}

@Composable
fun UserPage(vm: AppViewModel, id: Long) {
    val u = vm.userDetail
    PageShell(vm, vm.userSongs.isEmpty() && vm.userPlaylists.isEmpty(), "这个用户最近没有公开的播放记录") {
        SongList(vm, vm.userSongs, contextId = id, contextName = u?.nickname ?: "用户") {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(u?.avatar, 72.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            u?.nickname ?: "用户",
                            color = skin.text0,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                if (u != null && u.level > 0) append("Lv.${u.level}")
                                if (u != null && u.listenSongs > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("累计听歌 ${u.listenSongs} 首")
                                }
                            },
                            color = skin.text2,
                            fontSize = 12.sp,
                        )
                        // 第⑯点：加入时间放在等级下方。`createTime` 接口本来就有，
                        // 只是之前没显示；取不到（0）就整行不画，不留一个空日期。
                        if (u != null && u.createTime > 0) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${fmtDate(u.createTime)} 加入",
                                color = skin.text3,
                                fontSize = 11.5.sp,
                            )
                        }
                        // 个性签名跟着挪到加入时间下面，两行小字排在一起。
                        if (!u?.signature.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                u.signature,
                                color = skin.text3,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // 第⑯点：签到按钮移到头像这一行的最右侧 —— 它是这一页唯一的
                    // 「动作」，贴在头像右边比混在按钮堆里更容易找到。
                    if (u != null && u.id == vm.me?.id) {
                        Spacer(Modifier.width(10.dp))
                        val done = u.signedToday
                        PillButton(if (done) "今日已签到" else "签到", primary = !done) {
                            if (!done) vm.checkin()
                        }
                    }
                    // 用户 m00932 第⑤点：**别人**的主页，头像这一行最右侧给一颗关注按钮。
                    // 自己的主页不画（关注自己没有意义），那一侧留给上面的签到。
                    // 状态取自 `vm.userDetail.followed`，`followUser` 里是乐观更新 +
                    // 失败回滚（见它的 KDoc），所以这里只管把目标状态翻过去。
                    if (u != null && u.id > 0 && u.id != vm.me?.id) {
                        Spacer(Modifier.width(10.dp))
                        PillButton(if (u.followed) "已关注" else "关注", primary = !u.followed) {
                            vm.followUser(u.id, !u.followed)
                        }
                    }
                }
                // 关注 / 粉丝两个数字本身就是入口（桌面版也是这样点进去的）。
                if (u != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PillButton("${u.follows} 关注") {
                            vm.go(Route.UserList(u.id, "follows", u.nickname))
                        }
                        Spacer(Modifier.width(8.dp))
                        PillButton("${u.followeds} 粉丝") {
                            vm.go(Route.UserList(u.id, "followeds", u.nickname))
                        }
                        Spacer(Modifier.width(8.dp))
                        PillButton("Lv.${u.level}") {
                            vm.go(Route.UserLevel(u.id, u.nickname))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (vm.userPlaylists.isNotEmpty()) {
                SectionTitle("歌单", "共 ${vm.userPlaylists.size} 个")
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(vm.userPlaylists.size) { i ->
                        val p = vm.userPlaylists[i]
                        PlaylistCard(p) { vm.go(Route.Playlist(p.id, p.name)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            // 第⑯点：「最近播放」改名「播放排行」，并把「播放全部」挪到这一行的最右侧。
            // 这一页的歌单来自 `playRecord`（按播放次数排序），叫「最近播放」名不副实。
            if (u != null && !u.recordVisible) {
                Text(
                    "这位用户的听歌排行是私密的",
                    color = skin.text3,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("播放排行", color = skin.text0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("共 ${vm.userSongs.size} 首", color = skin.text3, fontSize = 11.5.sp)
                    }
                    PillButton("播放全部", primary = true) {
                        vm.playSongs(vm.userSongs, 0, id, u?.nickname ?: "用户")
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 片段

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        color = skin.text3,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(title: String, sub: String?, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = skin.text0, fontSize = 14.sp)
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(sub, color = skin.text3, fontSize = 11.5.sp)
            }
        }
        if (checked) {
            Text("✓", color = skin.accent, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SettingSwitch(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // 整行可点，不只是那颗开关：开关本体的命中区只有 52x32dp，而这一行的标题
    // 文字很长，用户很自然会去点文字（实测点「榜单」文字没反应）。
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = skin.text0, fontSize = 14.sp)
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(sub, color = skin.text3, fontSize = 11.5.sp)
            }
        }
        // 开关自己也接 onCheckedChange：两者都调到同一个 onChange，
        // 而 onChange 是「取反」语义，所以点开关时行不会也跟着翻一次
        // （Switch 的点击会被自己消费掉，不会冒泡到 Row）。
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 圆角胶囊按钮，对应桌面版的 `.btn-play-all` / `.btn-outline`。 */
@Composable
fun PillButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    val s = skin
    // 硬投影要求按钮自己不透明，否则阴影块会从按钮里透出来，看起来像脏影。
    // 桌面版 skins.css 给街机风的 `.btn-outline / .btn-side` 补的就是
    // `background: var(--bg-2)`；其余四款 hardShadow = 0，仍然走透明。
    val bg = when {
        primary -> s.accent
        s.hardShadow > 0 -> s.bg2
        else -> Color.Transparent
    }
    val fg = if (primary) s.onAccent else if (s.hardShadow > 0) s.text0 else s.text1
    // 形状由皮肤决定：瑞士风与街机风是直角矩形，其余三款是胶囊。
    // 原来这里判的是 `s.stroke > 0`，等于「只有街机风是矩形」——
    // 瑞士风在这种判据下一直是胶囊，而它本来是五款里最强调直角的。
    val shape = if (s.style.button == SkinStyle.Button.Rect) s.radii.baseDp else 17.dp
    // 按钮下阴影同样是皮肤的一部分：桌面版 skins.css 给街机风的
    // `.btn-play-all / .btn-outline / .btn-primary` 都加了 `4px 4px 0 var(--ink)`，
    // 按压时整体挪 2px、阴影收成 0。这里用 HardShadowBox + 按压位移复刻，
    // 其余四款 hardShadow = 0，直接透传，画出来和改动前一模一样。
    // 注意：投影靠「错位的实心块」实现，块是 matchParentSize 的，
    // 所以按压位移必须落在被包住的那层 Box 上，否则阴影会跟着一起挪。
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    HardShadowBox(offset = s.hardShadow, radius = shape) {
        Box(
            Modifier
                .offset(x = if (pressed) 2.dp else 0.dp, y = if (pressed) 2.dp else 0.dp)
                .height(33.dp)
                .clip(RoundedCornerShape(shape))
                .background(bg)
                .skinBorder(shape, hairline = !primary)
                .clickable(interactionSource = press, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = fg, fontSize = 13.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal)
        }
    }
}

/** 歌单/专辑/用户页的头部：封面 + 标题 + 副标题 + 动作行。 */
@Composable
private fun HeaderBlock(
    cover: String?,
    title: String,
    sub: String,
    actions: @Composable () -> Unit,
) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Cover(cover, 110.dp, skin.radii.baseDp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = skin.text0, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = skin.text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}
