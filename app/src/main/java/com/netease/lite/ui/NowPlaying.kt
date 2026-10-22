package com.netease.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netease.lite.AppViewModel
import com.netease.lite.Route
import com.netease.lite.data.Prefs
import com.netease.lite.model.LyricLine
import com.netease.lite.model.Song
import com.netease.lite.play.Playback
import kotlin.math.abs
import kotlin.math.exp

/**
 * 「正在播放」整页：大封面 + 可拖动的进度条 + 逐行歌词。
 *
 * 为什么要有这一页：迷你播放条只能切歌和暂停，看歌词、拖到某一句、点红心都得
 * 有个落脚的地方。桌面版是靠底栏点开的大播放页承担这件事，手机端同理。
 *
 * 两个取舍：
 *  - 封面用固定方形而不是随屏幕宽度拉伸 —— 平板 800dp 宽时一张拉满的封面会把
 *    歌词和控制键挤出屏幕。
 *  - 循环模式不再是三个按钮，而是**同一颗按钮轮换三种图标**
 *    （顺序播放 / 单曲循环 / 随机播放）。播放模式是「当前在哪一档」的状态，不是三个动作；
 *    并排三颗按钮既占位置，又让人以为要按对应那颗才生效。
 *    文字标签（[Playback.repeatLabel]）降级成 `contentDescription`，读屏仍然读得到。
 *    三档的口径见用户 m00001 第④点：只保留顺序、单曲循环、随机（详见 [Playback.normalize]）。
 *
 * 进度条拖动：`positionMs` 每 500ms 被心跳刷新一次，如果直接把它绑给 Slider，
 * 手指按着的时候会被心跳拽回去。所以拖动期间用本地 [dragPos]，松手才 seek
 * （松手才 seek 也顺带避免了拖动过程中一路发 seek 请求）。
 */
@Composable
fun NowPlayingPage(vm: AppViewModel) {
    val s = skin
    val song = vm.player.currentSong

    // 系统返回手势等价于页面上的收起箭头：走的是同一条回退栈。
    BackHandler(enabled = true) { vm.goBack() }

    if (song == null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Icons.ChevronDown, s.text1, { vm.goBack() })
                Text("正在播放", color = s.text0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            EmptyState("还没有在播放的歌曲", "到任意一个列表里点一首歌就会出现在这里。")
        }
        return
    }

    val isPlaying = vm.player.isPlaying
    val liked = vm.likedIds[song.id] == true
    val duration = vm.player.durationMs.coerceAtLeast(0L)

    // 拖动中的本地进度。key 里带 song.id：换歌时归零，免得新歌的进度条停在上一首的位置。
    var dragPos by remember(song.id) { mutableStateOf(-1L) }
    val shownPos = if (dragPos >= 0) dragPos else vm.player.positionMs

    // 右上角「三个点」的展开菜单（第⑨点）
    var menuOpen by remember { mutableStateOf(false) }

    // 第⑦点：音质档位选择。入口在这一页最下面那行（喜欢与评论之间）。
    // 与 menuOpen 分开，是因为它的入口不在 ⋯ 菜单里，而在页面上常驻。
    var qualityOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------- 顶部
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(Icons.ChevronDown, s.text1, { vm.goBack() })
            Text(
                "正在播放",
                color = s.text0,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // 第⑨点：这里原来是红心，改成「三个点」的展开菜单。
            // 理由是这一页能做的事远不止喜欢一首歌，而顶栏最右只有一格 ——
            // 喜欢被移到底栏左侧常驻（第⑧点），收藏/下载/分享/音质/皮肤都收进这里。
            IconButton(Icons.More, s.text1, { menuOpen = true })
        }

        // ---------------------------------------------------------- 封面 + 歌名
        // 封面边长同时受宽度和高度约束：只看宽度的话，20:9 的高瘦手机上一张
        // 占满宽度的方形封面（约 320dp 高）能把歌词区压到只剩 110dp —— 那点高度
        // 连「当前句 + 下一句」都放不下，歌词页就退化成一行跑马灯了。
        // 高度取 0.30 是照着「歌词区至少留 170dp」反推的：固定部分（标题行、歌名
        // 三行、进度条、控制键、底栏）合计约 340dp，829dp 的可用高度减去它还剩
        // 约 490dp，封面给 250dp、留白留出余量。平板/折叠屏展开时高度不是瓶颈，
        // 这时 300dp 的上限和 0.82 倍宽才是起作用的那两项。
        val cfg = LocalConfiguration.current
        val coverSize = minOf(
            cfg.screenWidthDp.dp * 0.82f,
            cfg.screenHeightDp.dp * 0.30f,
            300.dp,
        ).coerceAtLeast(140.dp)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 大封面在大屏上是主角，桌面版街机风给 `.np-cover` 也加了
            // 「2px 墨黑描边 + 4px 硬投影」（skins.css 5.3 的硬投影名单里有它），
            // 所以这里同样套 HardShadowBox；其余四款 offset=0 直接透传。
            HardShadowBox(radius = s.radii.lgDp) {
                Box(Modifier.skinBorder(s.radii.lgDp)) {
                    Cover(song.cover, coverSize, s.radii.lgDp)
                }
            }
            Spacer(Modifier.height(18.dp))
            // 用户 m09508 第⑦点：长按歌名弹复制选项。
            //
            // 用 combinedClickable 而不是长按选中文本（`SelectionContainer`）：这一页
            // 是整块手势区（拖进度条、点歌词跳转），再叠一层文本选择会和横向拖拽
            // 抢手势，而且选择手柄会盖住下面的歌词。弹一个小面板反而明确 ——
            // 「复制歌名」和「复制歌名 - 歌手」是两个不同的用途，让用户挑。
            var copyTitleOpen by remember { mutableStateOf(false) }
            Text(
                song.name,
                color = s.text0,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { copyTitleOpen = true },
                ),
            )
            if (copyTitleOpen) {
                CopySheet(
                    title = "复制",
                    items = listOf(
                        "复制歌名" to song.name,
                        "复制「歌名 - 歌手」" to "${song.name} - ${song.artistText}",
                    ),
                    onDismiss = { copyTitleOpen = false },
                    onCopy = { vm.notify("已复制") },
                )
            }
            Spacer(Modifier.height(6.dp))
            // 第⑩点：歌手名、专辑名都要能点进去。桌面版 `.np-artist` / `.np-album`
            // 是 <a>，点了跳歌手页 / 专辑页 —— 听一首陌生歌时「这人还有别的什么歌」
            // 是仅次于播放的第二个念头，只显示成死文字等于把这个入口藏了。
            // 没有 id 时（电台节目、部分 VIP 歌）仍渲染成文字但不接点击，
            // 而不是把整行藏掉：歌名下面突然少一行会让人以为界面出错。
            // 第⑬点：一位歌手直接进主页，多位歌手由 [AppViewModel.openArtistChoice]
            // 弹一个从下方升起的面板让人挑 —— 合唱曲目以前只能进第一位歌手，
            // 另外几位在界面上等于不存在。
            val firstArtist = song.artists.firstOrNull { it.id > 0 }
            // 用户 m03577 第④点：歌手名与专辑名放到同一行，中间用「·」隔开。
            // 原来是上下两行（歌手 12.5sp / 专辑 11sp），两行都只有一两个字，
            // 竖向占掉 30dp 却几乎全是空白 —— 挤成一行把高度还给歌词。
            // 一行两段各有各的点击目标（歌手弹选择器、专辑跳专辑页），所以用
            // Row + 两段可点 Text，而不是拼成一个字符串（那样点哪都进同一处）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artistText,
                    color = s.text2,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (firstArtist != null) {
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable { vm.openArtistChoice(song) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            } else {
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            },
                        ),
                )
                if (song.albumName.isNotEmpty()) {
                    Text(
                        "·",
                        color = s.text3,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(horizontal = 1.dp),
                    )
                    val albumClickable = song.albumId > 0
                    Text(
                        song.albumName,
                        color = s.text3,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (albumClickable) {
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable { vm.go(Route.Album(song.albumId, song.albumName)) }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                } else {
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                },
                            ),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 歌词
        if (vm.prefs.showLyrics) {
            LyricPanel(vm, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }

        // ---------------------------------------------------------- 进度条
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Slider(
                value = shownPos.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(),
                onValueChange = { dragPos = it.toLong() },
                onValueChangeFinished = {
                    if (dragPos >= 0) {
                        vm.seekTo(dragPos)
                        dragPos = -1L
                    }
                },
                enabled = duration > 0,
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = s.accent,
                    activeTrackColor = s.accent,
                    inactiveTrackColor = s.bg3,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmtDuration(shownPos), color = s.text3, fontSize = 11.sp)
                Text(fmtDuration(duration), color = s.text3, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------- 控制键
        // 第⑧点：循环模式与播放队列改成图标，分列播放键左右。
        // 桌面版底栏就是这个顺序（`renderer\index.html` 的 `.pb-center`：
        // 随机 / 上一首 / 播放 / 下一首 / 循环），移动端把「随机」并进循环图标后
        // 只剩一颗图标 —— 三种状态轮换：顺序、列表循环、随机（用户 m48583 第④点）。
        // 第⑦点：这一行五颗按钮要「分散一点，左右对齐填充满这一行」，
        // 所以从 Arrangement.Center 换成 SpaceBetween，同时把中间那些固定宽度的
        // Spacer 全去掉 —— 留着它们就等于在 SpaceBetween 分出来的间距之外再叠一层，
        // 五颗会挤在中间、两侧留白，正是要改掉的形状。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 循环图标：同一颗位置轮换三种图形，而不是并排三颗按钮。
            // 理由与桌面版一致 —— 播放模式是「当前是哪一档」的状态，不是三个动作。
            // 用户 m00001 第④点：只有 顺序 / 单曲循环 / 随机 三档。
            // 上一轮残留的「列表循环」不再是循环里的一站，它的图标也不该再出现 ——
            // 兜底走到 RepeatOff（顺序）而不是列表循环，宁可显示成顺序。
            val mode = vm.player.repeatMode
            // 三档一一对应，`else` 只是兜底：真正保证不会有第四种的是
            // `Playback.normalize()`，在状态进出的每个口子上折一次。
            val nmode = Playback.normalize(mode)
            val repeatIcon = when (nmode) {
                Playback.REPEAT_ONE -> Icons.RepeatOne
                Playback.SHUFFLE -> Icons.Shuffle
                else -> Icons.RepeatOff
            }
            IconButton(
                repeatIcon,
                if (nmode == Playback.REPEAT_ORDER) s.text1 else s.accent,
                { vm.cycleRepeat() },
                size = 22.dp,
                contentDescription = Playback.repeatLabel(nmode),
            )
            IconButton(Icons.Previous, s.text0, { vm.previous() }, size = 32.dp)
            // 播放键做成实心大圆：这是这一页上唯一一个「不用看也知道在哪」的按钮。
            // 街机风下圆形失去意义（那一款全是直角），改成方块 + 墨黑描边；
            // 落日余晖给它一圈橙色光晕（桌面版 .pb-play 的 box-shadow）。
            // 街机风还要加硬投影 —— skins.css 里 `.card-play, .pb-play` 是和
            // 卡片同一档的 `.pb-play { box-shadow: 4px 4px 0 var(--ink) }`。
            val playShape = RoundedCornerShape(if (s.style.button == SkinStyle.Button.Rect) s.radii.baseDp else 50.dp)
            HardShadowBox(
                offset = s.hardShadow,
                radius = if (s.style.button == SkinStyle.Button.Rect) s.radii.baseDp else 30.dp,
            ) {
            Box(
                Modifier
                    .size(60.dp)
                    .clip(playShape)
                    .background(s.accent)
                    .then(
                        if (s.style.glow > 0) {
                            Modifier
                                .shadow(
                                    elevation = s.style.glow.dp,
                                    shape = RoundedCornerShape(50),
                                    ambientColor = s.accentHi,
                                    spotColor = s.accentHi,
                                )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (s.style.button == SkinStyle.Button.Rect) {
                            Modifier.skinBorder(s.radii.baseDp, hairline = false)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { vm.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) Icons.Pause else Icons.Play,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = s.onAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            }
            IconButton(Icons.Next, s.text0, { vm.next() }, size = 32.dp)
            // 队列：第⑦点起不再显示队列里的歌曲数目，只留一颗图标。
            // 那个「i/n」原来挂在图标右边，让这一格的宽度随队列长度跳动，
            // SpaceBetween 下会把左右四颗一起推来推去；队列里几首歌，
            // 点进去看比在播放页读一个 10sp 的小数字更直接。
            IconButton(Icons.Queue, s.text1, { vm.go(Route.Queue) }, size = 22.dp, contentDescription = "播放队列")
        }

        // ---------------------------------------------------------- 底栏
        // 第⑦点：这三颗归到最下面一行 —— 喜欢（左）、音质（中）、评论（右）。
        // 音质原来常驻在播放键下面，白白占了歌词区下面一整行的高度；搬到这一行之后，
        // 上面「播放 / 暂停那一行」整体下沉，歌词区就多出约一行半的高度 ——
        // 第⑦点要的「给歌词部分更多空间」是这么腾出来的，不是靠缩字号。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                if (liked) Icons.HeartFilled else Icons.HeartOutline,
                if (liked) s.accent else s.text2,
                { vm.toggleLike(song) },
                size = 26.dp,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { qualityOpen = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 用户 m08537 第④点：字号放大一点。12sp 在 1080p 手机上要眯眼看，
                // 而这颗按钮现在是「切音质」的唯一入口（右上角菜单里那一项已删），
                // 得让人一眼找到。14sp 与旁边歌名行的小字同档，不再像个注释。
                Text(
                    "音质：" + (Prefs.QUALITY_LABEL[vm.quality] ?: vm.quality),
                    color = s.text2,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.ChevronDown, null, tint = s.text3, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                Icons.Comment,
                s.text2,
                { vm.go(Route.Comments("song", song.id, song.name)) },
                size = 24.dp,
            )
        }

        Spacer(Modifier.height(12.dp))
    }

    if (menuOpen) {
        NowPlayingMenu(vm, song) { menuOpen = false }
    }

    if (qualityOpen) {
        QualitySheet(vm) { qualityOpen = false }
    }

    // 第⑬点：多位歌手时的选择面板。
    if (vm.artistChoices.isNotEmpty()) {
        ArtistChoiceSheet(vm)
    }
}

/**
 * 第⑬点：多位歌手时「要看哪一位」的选择面板，从屏幕下方升起。
 *
 * 为什么用 [ModalBottomSheet] 而不是这一页其它弹窗用的 `AlertDialog`：
 * 这是一份「从列表里挑一个」的东西，不是一次确认。从下方升起时拇指够得着，
 * 而且它压在歌手名那一行下面，归属一眼可见；居中对话框在单手操作时上半屏的
 * 选项够不到，挑完还得把手挪回去。
 *
 * 头像可能是空的：`Song.artists` 只有 id 和名字，头像由
 * [AppViewModel.openArtistChoice] 并发补回来。所以这里不能用占位符——
 * `Avatar` 在 url 为空时自己会画一个灰底剪影。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistChoiceSheet(vm: AppViewModel) {
    val s = skin
    ModalBottomSheet(
        onDismissRequest = { vm.closeArtistChoice() },
        containerColor = s.bg1,
        contentColor = s.text0,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
        ) {
            Text("这首歌的歌手", color = s.text0, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text("选择要查看哪一位", color = s.text3, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            vm.artistChoices.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(s.radii.baseDp))
                        .clickable {
                            // 先关面板再导航：面板压在页面上，不关掉会跟着一起进歌手页。
                            vm.closeArtistChoice()
                            vm.go(Route.Artist(a.id, a.name))
                        }
                        .padding(vertical = 9.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(a.avatar, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        a.name,
                        color = s.text0,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 「正在播放」右上角三点展开的菜单（第⑨点）。
 *
 * 桌面版这一页的 ⋯ 里是「喜欢 / 加入歌单 / 下载 / 分享 / 音质 / 皮肤 / 查看专辑歌手」，
 * 移动端这里做同样的那几项。取舍：不做「移除队列」—— 这一页认的是「正在听的那首」，
 * 把它从队列里删掉等于把界面本身抽掉；那个操作留给队列页。
 */
@Composable
private fun NowPlayingMenu(vm: AppViewModel, song: Song, onDismiss: () -> Unit) {
    val s = skin
    val liked = vm.likedIds[song.id] == true
    var pickPlaylist by remember { mutableStateOf(false) }

    if (pickPlaylist) {
        AddToPlaylistSheet(vm, listOf(song), onDismiss = { pickPlaylist = false; onDismiss() })
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = {
            Column {
                Text(song.name, color = s.text0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistText, color = s.text3, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column {
                MenuRow(if (liked) "取消喜欢" else "喜欢", if (liked) Icons.HeartFilled else Icons.HeartOutline) {
                    vm.toggleLike(song); onDismiss()
                }
                MenuRow("下一首播放", Icons.Next) { vm.player.playNext(listOf(song)); vm.notify("已加入下一首"); onDismiss() }
                MenuRow("添加到歌单…", Icons.Add) { pickPlaylist = true }
                MenuRow(if (vm.local.has(song.id)) "重新下载" else "下载", Icons.Download) {
                    // 同 Pages.kt 那处：「重新下载」出现时本地一定已有，必须 force。
                    vm.download(song, force = vm.local.has(song.id)); onDismiss()
                }
                MenuRow("查看专辑", Icons.Album) {
                    if (song.albumId > 0) vm.go(Route.Album(song.albumId, song.albumName))
                    onDismiss()
                }
                MenuRow("查看歌手", Icons.Artist) {
                    val a = song.artists.firstOrNull()
                    if (a != null && a.id > 0) vm.go(Route.Artist(a.id, a.name))
                    onDismiss()
                }
                MenuRow("查看评论", Icons.Comment) { vm.go(Route.Comments("song", song.id, song.name)); onDismiss() }
                // 第⑨点：这里原来还有「音质：…」和「皮肤：…」两项，都进设置页。
                // 已删：音质那档现在常驻在这一页最下面一行（第⑦点），
                // 而皮肤是「换一次就不动」的设置，不值得占 ⋯ 菜单位。
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = s.text2) } },
    )
}

@Composable
private fun MenuRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(s.radii.baseDp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = s.text2, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = s.text0, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 播放页「音质」档位选择（第⑨a点）。
 *
 * 为什么不是直接循环切换：档位有四档，盲点三次才知道自己切到了哪一档，
 * 而且「无损」需要会员 —— 选中之后流可能回落到「极高」，得让用户看见真实结果。
 * 所以这里列全四档、标出当前档，选完由 [AppViewModel.useQuality] 落盘。
 *
 * 与设置页那个「默认播放音质」是同一个 prefs 键：两处改的是同一件事，
 * 不该出现「播放页选了无损、设置页还显示极高」。
 */
@Composable
private fun QualitySheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val s = skin
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = { Text("播放音质", color = s.text0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                for ((key, label) in Prefs.QUALITY_LABEL) {
                    val on = vm.quality == key
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(s.radii.baseDp))
                            .clickable { vm.useQuality(key); onDismiss() }
                            .padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label + if (key == "lossless") "  VIP" else "",
                            color = if (on) s.accent else s.text0,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (on) Icon(Icons.Check, null, tint = s.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = s.text2) } },
    )
}

/**
 * 复制面板（用户 m09508 第⑦点）。
 *
 * 播放页的长按只做一件事：把这一行文字复制走。为什么不是系统的文本选择菜单：
 * 这一页整块都是手势区（拖进度条、点歌词跳转），再叠一层选择手柄会和这些手势
 * 抢输入，而且手柄会盖住歌词。这里给的是「复制哪一份」的选择 ——
 * 歌名单独一份、歌名带歌手一份，歌词则连带翻译一起复制。
 *
 * 复制成功后由调用方 `vm.notify("已复制")` 弹一下 toast —— 面板关掉之后
 * 用户手上没有任何可见变化，不提示会以为没点上。
 */
@Composable
private fun CopySheet(
    title: String,
    items: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    val s = skin
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = { Text(title, color = s.text0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                for ((label, value) in items) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(s.radii.baseDp))
                            .clickable {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("netease-lite", value),
                                )
                                onDismiss()
                                onCopy()
                            }
                            .padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, color = s.text0, fontSize = 14.sp)
                            // 把要复制的内容原样露一行：歌词长句截断，用户至少能
                            // 看出自己点的是哪一句，而不是盲选。
                            Text(
                                value,
                                color = s.text3,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Copy, null, tint = s.text2, modifier = Modifier.size(15.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭", color = s.text2) } },
    )
}

/**
 * 逐行歌词。
 *
 * 上下留一大块 padding，正在唱的那一行落在面板正中 —— 这是歌词界面唯一能被
 * 一眼读懂的信息层级，靠居中的位置表达，而不是靠字号（字号一变，长句就会折行）。
 * 点某一行跳到那句：这是歌词界面最常被用到的交互，不做的话歌词就只是装饰。
 */
@Composable
private fun LyricPanel(vm: AppViewModel, modifier: Modifier = Modifier) {
    val s = skin
    val lines = vm.lyrics.lines
    val listState = rememberLazyListState()
    // 长按哪一句要复制（用户 m09508 第⑦点）。-1 表示没弹面板。
    var copyLine by remember { mutableStateOf(-1) }

    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (vm.lyricsLoading) "歌词加载中…" else "这首歌没有歌词",
                color = s.text3,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
        return
    }

    val pos = vm.player.positionMs
    // 最后一条时间戳不晚于当前进度的行就是正在唱的那句。
    val active = lines.indexOfLast { it.timeMs <= pos }.coerceAtLeast(0)

    // 歌词面板：选中行居中，上下还能看见前后文。
    //
    // 居中不能用「上下各 96dp 的 contentPadding」写死（原来的写法）：contentPadding
    // 是留在内容首尾的空白，面板矮的时候（手机 1080x2280@440dpi，扣掉封面、歌名、
    // 进度条、控制键之后只剩 100dp 上下）上下各 96dp 直接把可见区吃光 ——
    // 屏幕上只剩当前那一行，当前行没有文字时就是一个孤零零的「♪」。
    //
    // 也不能改成「上下各半个面板高」：那是把整块面板都当留白，等于同一件事。
    // 居中要用 scrollOffset —— 负值把目标行往下推，推半个面板高它就在正中，
    // 而首尾留白只留 12dp，可见行数全部还给内容。代价是列表最前面几句滚不动
    // （前面没有内容可滚），会被钳在顶部，这是可接受的自愈行为。
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        // 再减 14dp（约半行高），让这一行的**中心**落在正中线上，
        // 而不是它的顶端贴住中心线。
        val shift = with(density) { -(maxHeight / 2 - 14.dp).roundToPx() }

        // 用户 m00932 第⑧点、m07048 第②点：滚动完全照搬电脑端。
        //
        // 电脑端（`renderer/js/lrc.js` 的 `_follow()`）不用原生 smooth 滚动，而是
        // 自己开一个 requestAnimationFrame 循环，**每帧重新量一次**「此刻该在的位置」，
        // 再让偏差按指数衰减逼近。三个参数：时间常数 110ms、速度上限 4200px/s、
        // 换行后至少跑 300ms（盖住字号过渡）。
        //
        // 为什么必须每帧重量：当前行的字号在过渡期里一路上涨，行高跟着变，目标位置
        // 每帧都在动。原生 smooth 只在调用那一刻算一次目标，等滚完才发现偏了几像素
        // —— 看着就是「滚过去了又跳一下」。安卓这边 `animateScrollBy` 也是同样的毛病，
        // 所以这里改成和电脑端同构的逐帧循环。
        //
        // 还有两个从电脑端抄来的细节：
        //  - **起步缓冲**（ramp）：换行瞬间偏差最大（约一行高），纯指数缓动第一帧就会
        //    迈出 6~7px，单帧跨度太大看着还是「顿」一下。前 90ms 把步长按 (t/90)² 放开，
        //    位移从 1px 左右平滑加到正常速度。
        //  - **浮点累加器**：不能每帧「回读 scrollTop 再算增量」，因为滚动位置会被吸附到
        //    整数像素，偏差衰减到亚像素时增量被舍成 0，位置再也动不了、循环永不收敛
        //    （电脑端实测留下 5~9px 永久偏差）。这里用 `Float` 自己记位置。
        LaunchedEffect(active) {
            if (active < 0) return@LaunchedEffect
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == active }
            if (item == null) {
                // 目标行还不在视口里（用户点了一句很远的歌词、或列表刚装好）。
                // 这种时候没有距离可算，做长动画只会让人盯着空白等：直接瞬移到位。
                listState.scrollToItem(active, shift)
                return@LaunchedEffect
            }
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            // 电脑端：一次跳几十行（拖进度条 / 切歌）不做缓动，几万像素的动画纯属折磨。
            val lineH = item.size.coerceAtLeast(1)
            val firstDelta = (item.offset + item.size / 2f) - viewportCenter
            if (abs(firstDelta) > lineH * 60) {
                listState.scrollToItem(active, shift)
                return@LaunchedEffect
            }
            // 已经在正中就不动 —— 电脑端也有一模一样的空转保护
            // （`if (Math.abs(delta) < 1 && !this._raf) return`）。
            if (abs(firstDelta) < 1f) return@LaunchedEffect

            // 累加器：自己记浮点位置，不依赖回读值（见上面第 3 条）。
            var top = 0f
            var lastStep = 0L
            val t0 = withFrameNanos { it }
            // 至少跑到字号过渡结束：电脑端是 `_followUntil = now + 300`，
            // 字号过渡 420ms 比桌面端的 240ms 长，这里取 440ms。
            val followUntil = t0 + 440_000_000L
            while (true) {
                val ts = withFrameNanos { it }
                val dt = if (lastStep == 0L) 16f else ((ts - lastStep) / 1_000_000f).coerceIn(1f, 64f)
                lastStep = ts
                // 每帧重新量「此刻该在的位置」：字号过渡期间目标一直在动。
                val li = listState.layoutInfo
                val it2 = li.visibleItemsInfo.firstOrNull { it.index == active } ?: break
                val vc = (li.viewportStartOffset + li.viewportEndOffset) / 2f
                val delta = (it2.offset + it2.size / 2f) - vc
                // 收工判据在写之前用「刚量到的」偏差判断：至少跑到字号过渡结束，
                // 并且已经贴到半像素以内。收工时精确对齐一次，把吸附留下的零头抹掉。
                if (abs(delta) <= 0.5f && ts > followUntil) {
                    if (abs(delta) > 0.01f) listState.dispatchRawDelta(delta)
                    break
                }
                val target = top + delta
                var k = 1f - exp(-dt / 110f)
                // 起步缓冲：前 90ms 把步长按 (t/90)² 放开。
                val ramp = ((ts - t0) / 90_000_000f).coerceIn(0f, 1f)
                k *= ramp * ramp
                var next = top + (target - top) * k
                // 速度上限：一次跳过几十行时别让缓动拖太久。
                val cap = 4200f * dt / 1000f
                if (next - top > cap) next = top + cap
                else if (top - next > cap) next = top - cap
                val move = next - top
                top = next
                if (abs(move) > 0.01f) listState.dispatchRawDelta(move)
                // 兜底超时：任何意外都不能让循环一直挂着。
                if (ts - t0 > 2_600_000_000L) {
                    val again = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == active }
                    if (again != null) {
                        val vc2 = (listState.layoutInfo.viewportStartOffset +
                            listState.layoutInfo.viewportEndOffset) / 2f
                        val d2 = (again.offset + again.size / 2f) - vc2
                        if (abs(d2) > 0.01f) listState.dispatchRawDelta(d2)
                    }
                    break
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        ) {
            items(lines.size) { i ->
                val line: LyricLine = lines[i]
                val on = i == active
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 用户 m09508 第⑦点：长按某一句歌词可以复制。
                        // 点击仍然是「跳到这一句」—— 这两个手势在这一页都很常用，
                        // 所以合并到 combinedClickable，而不是把跳转挪到双击。
                        .combinedClickable(
                            onClick = { vm.seekTo(line.timeMs) },
                            onLongClick = { copyLine = i },
                        )
                        .padding(vertical = 7.dp),
                ) {
                    // 第⑨b点：正在唱的那一句**只加粗放大、颜色不变**。
                    //
                    // 原来这一行是「换成强调色 + 加粗 + 放大」，用户要求对齐桌面端：
                    // 桌面 `renderer\styles.css` 的 `.lyric-line.active` 只动
                    // font-size 与 font-weight，颜色始终是 --text-0。理由很实际 ——
                    // 歌词整段变红之后，长句里本来就不多的视觉层级全被颜色吃掉了，
                    // 眼睛会先看到「红」，再去找「哪句」，反而更慢。
                    //
                    // 第⑧点：字号比例也照桌面端来。桌面是 17px → 22px（1.294 倍）、
                    // 行高恒定 1.6 倍字号、过渡 240ms。
                    //
                    // 用户 m03577 第⑤点要求「放大比例增大一点、加粗更粗、过渡更流畅、
                    // 动画时间延长一些，参考电脑端」。桌面端这次的 1.294 倍在手机上
                    // 偏弱（手机屏小、视线距离近，1.26→1.29 的差别基本看不出来），
                    // 所以基准仍取 13.5sp，当前句提到 19sp（1.41 倍）—— 比桌面端的
                    // 比例更夸张一点，但视觉层级与桌面端一致：当前句明显跳出来、
                    // 其余行整齐一致。
                    // 字重：用户 m08537 第②点说「当前句的加粗程度少一点」。
                    // 原来提到 ExtraBold，配上 19sp 在中文笔画密集的字上糊成一团
                    // （「聽」「戀」这类字尤其明显），现在退回 Bold —— 与桌面端
                    // `.lyric-line.active { font-weight: 700 }` 一致，层级靠字号
                    // 和颜色拉，不靠字重硬顶。
                    val fs by animateFloatAsState(
                        targetValue = if (on) 19f else 13.5f,
                        animationSpec = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                        label = "lyricFontSize",
                    )
                    val glow = s.style.lyricGlow
                    Text(
                        line.text.ifBlank { "♪" },
                        color = if (on) s.text0 else s.text3,
                        fontSize = fs.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = (fs * 1.6f).sp,
                        // 只有深色底那几款给当前句加发光（见 SkinStyle.lyricGlow）。
                        style = if (on && glow != null) {
                            LocalTextStyle.current.copy(
                                shadow = Shadow(color = glow, offset = Offset.Zero, blurRadius = 18f),
                            )
                        } else {
                            LocalTextStyle.current
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 翻译逐行对齐原词，空串表示这句没有翻译。
                    // 桌面端 `.lyric-trans` 是 `0.82em`，也就是**跟着原词缩放**的，
                    // 所以这里也按 0.82 倍算，而不是写死两个字号。
                    val trans = vm.lyrics.translated.getOrNull(i).orEmpty()
                    if (trans.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        val tfs by animateFloatAsState(
                            targetValue = if (on) 19f * 0.82f else 13.5f * 0.82f,
                            animationSpec = tween(420, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                            label = "lyricTransFontSize",
                        )
                        Text(
                            trans,
                            color = if (on) s.text2 else s.text3,
                            fontSize = tfs.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            lineHeight = (tfs * 1.6f).sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    // 长按某句歌词后的复制面板（用户 m09508 第⑦点）。
    if (copyLine in lines.indices) {
        val i = copyLine
        val line = lines[i]
        val trans = vm.lyrics.translated.getOrNull(i).orEmpty()
        // 有翻译时把翻译一起带上：中文歌的翻译往往才是用户想要的那一句
        // （日文/英文歌尤其明显），只复制原文等于让他自己再打一遍。
        val both = if (trans.isNotEmpty()) "${line.text}\n$trans" else line.text
        val items = buildList {
            add("复制歌词" to line.text)
            if (trans.isNotEmpty()) add("复制歌词与翻译" to both)
        }
        CopySheet(
            title = "复制歌词",
            items = items,
            onDismiss = { copyLine = -1 },
            onCopy = { vm.notify("已复制") },
        )
    }
}
