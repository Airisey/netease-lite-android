package com.netease.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.netease.lite.AppViewModel
import com.netease.lite.Route
import com.netease.lite.ScanProgress
import com.netease.lite.api.Endpoints
import com.netease.lite.api.RiskQr
import com.netease.lite.api.RiskVerify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 应用外壳：侧栏抽屉 + 内容区 + 底部迷你播放条。
 *
 * 桌面版是「左边固定侧栏 + 中间内容 + 底部播放条」；手机宽度放不下固定侧栏，
 * 所以侧栏改成抽屉（从左边滑出），内容区独占整屏，迷你播放条固定在底部。
 * 平板与折叠屏展开时**不做两套布局** —— 抽屉在这些设备上一样好用，
 * 多一套布局就多一套要维护的间距和断点。
 */
@Composable
fun AppShell(vm: AppViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 用户 m03593 第①点：登录后侧栏里「创建的歌单 / 收藏的歌单」是空的，重开一次
    // 侧栏（或者做点什么别的刷新动作）才出来。根因在数据层：拉歌单列表那一步只在
    // 登录成功那一刻跑过一次，失败了也没人再试。这里让抽屉每次打开都问一句
    // 「要不要补一次」——`ensureLibrary()` 在列表已有内容时直接返回，不会白发请求。
    LaunchedEffect(drawer.currentValue) {
        if (drawer.isOpen) vm.ensureLibrary()
    }

    if (!vm.loggedIn && vm.route == Route.Startup) {
        // 登录页也要让开状态栏，否则「网易云音乐 Lite」那个大标题会压到挖孔里。
        // 背景必须画在让位后的容器上：只给 content 加背景时，状态栏那条带子是
        // 窗口底色（透明 → 纯黑），全面屏下会看到顶上一条黑边。
        Column(
            Modifier
                .fillMaxSize()
                .background(skin.bg0)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            LoginScreen(vm)
        }
        return
    }

    // 「正在播放」原来在这里提前 return，放在抽屉外面。第①③点之后不能这么做了：
    // ① 它要有进场/退场动效，而「旧页退场 + 新页进场」这一对关系只有 AnimatedContent
    //    知道，提前 return 等于绕开了那个容器；
    // ③ 用户要求「全局右划都能打开左边栏」，播放页也得能滑出抽屉。
    // 所以它改成和别的页面一样待在 AnimatedContent 里，靠下面两处 `!= NowPlaying`
    // 判断把顶栏和迷你播放条藏起来，保持「整页盖住外壳」的观感。

    // 系统返回（手势或三键）等价于自带返回箭头（第⑦点）：
    // 抽屉开着就关抽屉，否则回退栈里退一层；栈空时才交回系统（退出应用）。
    BackHandler(enabled = drawer.isOpen || vm.canGoBack) {
        if (drawer.isOpen) scope.launch { drawer.close() } else vm.goBack()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = skin.bg1,
                modifier = Modifier
                    .width(272.dp)
                    // 街机风把整个抽屉当一张硬卡片：右侧 + 底部描边，
                    // 桌面版对应 .sidebar { border-right: 2px solid var(--ink) }。
                    .then(
                        if (skin.style.panelBorder) {
                            Modifier
                                .skinBorder(0.dp, hairline = false)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Sidebar(vm) { scope.launch { drawer.close() } }
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(skin.bg0)
                // 全面屏（第①点）：顶栏让开状态栏/挖孔，迷你条让开手势导航条。
                // 放在这一层而不是每页各加一次 —— 内容区要铺满，只有壳的两端需要让位。
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // 播放页是整页，它自己画背景、自己带收起箭头 —— 顶栏留着会在它上面
            // 多出一条 52dp 的标题栏。这里靠 route 判断而不是把它挪出 AnimatedContent，
            // 是为了让第①点的「向上浮现 / 向下渐隐」能作用在换页这一对关系上。
            val onNowPlaying = vm.route == Route.NowPlaying
            if (!onNowPlaying) {
                TopBar(vm)
            }
            // 第③点：页面之间的过渡。桌面版切换视图有一段轻微的上浮 + 淡入
            // （`renderer/styles.css` 的 `.page { animation: pageIn .18s }`），
            // 这里用同一套节奏。
            //
            // 用 `AnimatedContent` 而不是给 `when` 的每个分支各加一次动画：动画要
            // 作用在「旧页退场、新页进场」这一对关系上，只有容器知道上一页是谁。
            // `contentKey` 取 route 的类名 + 参数，保证「同一个页面的不同歌单」也算
            // 换页 —— 否则从歌单 A 跳到歌单 B 会被当成同一状态，画面直接换内容、
            // 不动。
            val anims = vm.animations
            AnimatedContent(
                targetState = vm.route,
                modifier = Modifier.weight(1f),
                contentKey = { r -> r::class.simpleName + ":" + routeKey(r) },
                transitionSpec = {
                    val toPlaying = targetState == Route.NowPlaying
                    val fromPlaying = initialState == Route.NowPlaying
                    when {
                        !anims -> EnterTransition.None togetherWith ExitTransition.None
                        // 第①点：进出播放页走纵向。桌面版是 `.np { animation:
                        // npUp .2s }`（整块从下方 24px 处浮上来）与关闭时的反向渐隐，
                        // 用 1/12 屏高的位移配 240ms，节奏与桌面版一致。
                        // 这里不复用下面的横向规则：播放页是「从底部盖上来」的一层，
                        // 横向滑动会让人以为是同一层的左右邻居。
                        toPlaying -> (
                            fadeIn(tween(200)) +
                                slideInVertically(tween(240)) { h -> h / 12 }
                            ) togetherWith fadeOut(tween(160))
                        fromPlaying -> fadeIn(tween(160)) togetherWith (
                            fadeOut(tween(200)) +
                                slideOutVertically(tween(240)) { h -> h / 12 }
                            )
                        else -> {
                            val forward = routeDepth(targetState) >= routeDepth(initialState)
                            val dir = if (forward) 1 else -1
                            (
                                fadeIn(tween(180)) +
                                    slideInHorizontally(tween(200)) { w -> dir * w / 14 }
                                ) togetherWith (
                                fadeOut(tween(140)) +
                                    slideOutHorizontally(tween(200)) { w -> -dir * w / 22 }
                                )
                        }
                    }
                },
                label = "route",
            ) { route ->
                Box(Modifier.fillMaxSize()) {
                    when (route) {
                        is Route.Startup -> DailyPage(vm)
                        is Route.Daily -> DailyPage(vm)
                        is Route.Recommend -> RecommendPage(vm)
                        is Route.Charts -> ChartsPage(vm)
                        is Route.Podcasts -> PodcastsPage(vm)
                        is Route.Cloud -> CloudPage(vm)
                        is Route.Liked -> LikedPage(vm)
                        is Route.Recent -> RecentPage(vm)
                        is Route.Downloads -> DownloadsPage(vm)
                        is Route.Downloading -> DownloadingPage(vm)
                        is Route.Settings -> SettingsPage(vm)
                        is Route.Search -> SearchPage(vm)
                        is Route.Playlist -> PlaylistPage(vm, route.id)
                        is Route.Album -> AlbumPage(vm, route.id)
                        is Route.Artist -> ArtistPage(vm, route.id)
                        is Route.ArtistAll -> ArtistAllSongsPage(vm, route.id, route.name)
                        is Route.User -> UserPage(vm, route.id)
                        is Route.UserList -> UserListPage(vm, route.id, route.kind)
                        is Route.Podcast -> PodcastPage(vm, route.id)
                        is Route.Comments -> CommentsPage(vm, route.type, route.id, route.name)
                        is Route.UserLevel -> UserLevelPage(vm, route.id, route.name)
                        is Route.Queue -> QueuePage(vm)
                        // 上面那个提前 return 已经接住了这一支；写在这里只为让 when 穷尽。
                        is Route.NowPlaying -> NowPlayingPage(vm)
                    }
                }
            }
            // 用户 m48583 第③点：「播放页出现时隐藏底栏」。播放页本身就是全屏的
            // 播放界面，底下再挂一条迷你播放条等于把同一首歌说两遍；而且播放页
            // 底部已经有红心 / 评论 / 音质那几行，它一压上来会挤掉收起箭头那一行。
            // 只在播放页藏，别的页面（歌单、搜索、每日推荐）迷你条照常显示 ——
            // 那是用户从任意页面回到当前播放的唯一入口。
            if (!onNowPlaying) {
                MiniPlayer(vm)
            }
        }
    }

    Dialogs(vm)
}

/** 路由的比较键：同一类页面但换了参数（歌单 A → 歌单 B）也算一次换页。 */
private fun routeKey(r: Route): String = when (r) {
    is Route.Playlist -> r.id.toString()
    is Route.Album -> r.id.toString()
    is Route.Artist -> r.id.toString()
    is Route.ArtistAll -> "all" + r.id
    is Route.User -> r.id.toString()
    is Route.UserList -> r.id.toString() + r.kind
    is Route.Podcast -> r.id.toString()
    is Route.Comments -> r.type + r.id
    is Route.UserLevel -> r.id.toString()
    else -> ""
}

/**
 * 路由的「深度」，只用来决定过渡的方向：进得越深，新页从右侧滑进来。
 *
 * 不追求精确的层级树 —— 只需要「往详情走 = 前进，回到列表 = 后退」这一条成立。
 */
private fun routeDepth(r: Route): Int = when (r) {
    is Route.Daily, is Route.Startup, is Route.Recommend, is Route.Charts,
    is Route.Podcasts, is Route.Liked, is Route.Recent, is Route.Downloads,
    is Route.Downloading, is Route.Cloud,
    is Route.Settings -> 0
    is Route.Playlist, is Route.Album, is Route.Artist, is Route.User,
    is Route.Podcast, is Route.Search -> 1
    // 「全部歌曲」是从歌手页再往里一层，跟着 [Route.Artist] 编号，进/退的滑动方向才对。
    is Route.ArtistAll -> 2
    is Route.Queue, is Route.NowPlaying, is Route.UserList, is Route.UserLevel -> 2
    is Route.Comments -> 3
}

/**
 * 一次性提示走系统 Toast，错误仍走对话框。
 *
 * 第⑦点报的是「签到提示以 toast 形式呈现」。原来 [AppViewModel.toast] 是用
 * `AlertDialog` 弹的 —— 签到成功、已加入下一首、登录成功这类**不需要用户决策**
 * 的消息，却要伸手点一下「知道了」才能继续，等于把通知变成了拦路。
 * Android 上这件事本来就有专门的控件。
 *
 * 为什么错误还留着对话框：`vm.error` 是「这首歌播不了」「登录失败」这种
 * 用户必须看见并做点什么的消息，Toast 两秒就飘走了，容易错过。
 *
 * Toast 用 `LocalContext` 而不是 Activity —— 这里拿到的 Context 是 Activity
 * 级别的，`Toast.makeText` 内部会自己取 ApplicationContext，不会泄漏。
 */
@Composable
private fun Dialogs(vm: AppViewModel) {
    val ctx = LocalContext.current
    // LaunchedEffect 的 key 取消息本身：同一条消息连发两次时 state 不变，
    // 但用户可能真的想再看一眼（比如连点两次「已加入下一首」）。
    // 用时间戳做 key 才能每次都弹。
    val msg = vm.toast
    if (msg != null) {
        LaunchedEffect(msg) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }
    vm.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.consumeError() },
            title = { Text("出错了") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.consumeError() }) { Text("知道了") } },
        )
    }
    // 本地扫描进度（m00001 第②点）：盖在整页上，有进度条和当前文件名。
    vm.scanProgress?.let { ScanProgressDialog(it) }
}

/**
 * 本地扫描进度浮层（m00001 第②点）。
 *
 * 用 `Dialog` 而不是 AlertDialog：这里没有「确定/取消」，只有一条会自己走完的
 * 进度，塞两个按钮只会让人以为要做什么。`DialogProperties` 关掉点击外部关闭 ——
 * 扫描是后台任务，误触把它关掉并不能停掉扫描，只会让人以为扫完了。
 *
 * 布局顺序按用户要求：进度条在上，**当前正在扫描的歌曲文件名在进度条下方**。
 * 文件名用单行 + 省略号：`歌手 - 歌名 (Live).flac` 这类名字很长，换行会让卡片
 * 高度不停跳。
 */
@Composable
private fun ScanProgressDialog(p: ScanProgress) {
    val s = skin
    val shape = RoundedCornerShape(s.radii.lgDp)
    Dialog(
        onDismissRequest = { /* 扫描不能靠点外部取消 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(s.bg2)
                .skinBorder(s.radii.lgDp)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(p.title, color = s.text0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            // 进度条：街机风是黑框黄底方块，其余是胶囊。填充色就是皮肤的强调色，
            // 所以五款皮各自成立，不需要为这里再写一套色。
            val barShape = if (s.style.button == SkinStyle.Button.Rect) RoundedCornerShape(0.dp) else CircleShape
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (s.stroke > 0) 10.dp else 6.dp)
                    .clip(barShape)
                    .background(s.bg4)
                    .then(if (s.stroke > 0) Modifier.skinBorder(if (s.radii.base == 0) 0.dp else 3.dp) else Modifier),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(p.percent.coerceIn(0, 100) / 100f)
                        .clip(barShape)
                        .background(s.accent),
                )
            }
            Spacer(Modifier.height(11.dp))
            // 当前文件名（进度条**下方**，用户明确要求的位置）
            Text(
                p.file.ifBlank { "…" },
                color = s.text1,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (p.detail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    p.detail,
                    color = s.text3,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 顶栏

@Composable
private fun TopBar(vm: AppViewModel) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(s.bg1)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 第③点：左上角的三根横线与返回箭头都删掉了。
        // 侧栏改用「全局右划」打开（抽屉的 gesturesEnabled 覆盖整块内容区），
        // 返回交给系统返回键 / 返回手势 —— 两者在 Android 上是同一件事，
        // 顶栏再放一颗返回箭头等于让用户在两套返回逻辑之间做选择。
        // 省下来的横向空间给标题，长歌单名能多显示四五个字。
        Text(
            titleOf(vm.route),
            color = s.text0,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(Icons.Search, s.text1, { vm.go(Route.Search) })
        // 第⑤点：顶栏那颗齿轮删掉了。设置是低频入口，占着顶栏最右这个位置
        // 每天都会被误触；侧栏用户区右上角仍有一枚同样的齿轮，够用。
    }
    // 顶栏底边那道色带：瑞士风 2px 红、街机风 3px 墨黑，其余三款没有（0）。
    // 画成独立的 Box 而不是给上面那个 Row 加 border-bottom —— 52dp 是固定行高，
    // 边框会参与盒模型，把图标整体往上挤 2~3dp。
    val stripe = s.style.stripe
    if (stripe > 0) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(stripe.dp)
                .background(s.style.stripeColor ?: s.accent),
        )
    }
}

private fun titleOf(r: Route): String = when (r) {
    is Route.Startup -> "每日推荐"
    is Route.Daily -> "每日推荐"
    is Route.Recommend -> "推荐歌单"
    is Route.Charts -> "榜单"
    is Route.Podcasts -> "播客"
    is Route.Cloud -> "音乐云盘"
    is Route.Liked -> "我喜欢的音乐"
    is Route.Recent -> "最近播放"
    is Route.Downloads -> "已下载音乐"
    is Route.Downloading -> "正在下载"
    is Route.Settings -> "设置"
    is Route.Search -> "搜索"
    is Route.NowPlaying -> "正在播放"
    is Route.Queue -> "播放队列"
    is Route.Playlist -> r.name.ifEmpty { "歌单" }
    is Route.Album -> r.name.ifEmpty { "专辑" }
    is Route.Artist -> r.name.ifEmpty { "歌手" }
    // 标题带歌手名：从这一页再点进别的歌手，返回条上两页都叫「全部歌曲」会分不清。
    is Route.ArtistAll -> r.name.ifEmpty { "歌手" } + " · 全部歌曲"
    is Route.User -> r.name.ifEmpty { "用户" }
    is Route.UserList -> (r.name.ifEmpty { "用户" }) + " 的" + (if (r.kind == "followeds") "粉丝" else "关注")
    is Route.Podcast -> r.name.ifEmpty { "播客" }
    is Route.Comments -> "评论"
    is Route.UserLevel -> "听歌等级"
}

// ------------------------------------------------------------------ 侧栏

@Composable
private fun Sidebar(vm: AppViewModel, onPick: () -> Unit) {
    // 「新建歌单」对话框的开合状态要挂在 Sidebar 这一层：它的确认按钮会调
    // vm.createPlaylist() 再刷新歌单列表，而这个列表正是同一组合函数里读的。
    var showCreate by remember { mutableStateOf(false) }
    // 长按哪一行歌单就弹它的菜单（第⑫点）。null = 没弹。
    var menuFor by remember { mutableStateOf<com.netease.lite.model.Playlist?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(skin.bg1)
            .verticalScroll(rememberScrollState()),
    ) {
        // 用户区
        Row(
            Modifier
                .fillMaxWidth()
                // 第⑤点：这里原来跳设置页（当时还没有个人主页），用户点自己的头像
                // 想看的是自己，不是设置。已登录 → 自己的主页；未登录 → 登录页。
                .clickable {
                    val id = vm.me?.id
                    if (id != null && id > 0) vm.go(Route.User(id, vm.me?.nickname ?: "我")) else vm.go(Route.Startup)
                    onPick()
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(vm.me?.avatar, 44.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 用户 m00932 第⑫点：昵称右边按 VIP 状态显示标识；m02003 第⑤点
                    // 起换成会滚动的 NameMarquee —— 长昵称原地循环，不再用省略号，
                    // 也不再有机会把徽标挤成竖排。
                    NameMarquee(
                        text = vm.me?.nickname ?: "未登录",
                        color = skin.text0,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        // fill = false：昵称短的时候不要把右边的 VIP 标识推到天边，
                        // 长的时候自己缩（`VipTag` 不能被挤没）。
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 用户 m00932 第⑫点：昵称右边按 VIP 状态显示标识。
                    // 复用歌曲行/用户行那颗（第⑪点刚按皮肤重做过）——
                    // 会员标识在全应用只该有一种画法，否则用户会以为是两个东西。
                    if ((vm.me?.vipType ?: 0) > 0) VipTag()
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    if (vm.loggedIn) (vm.me?.signature?.ifBlank { "已登录" } ?: "已登录") else "点这里登录",
                    color = skin.text3,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 头像右边留一个明确的小齿轮：个人主页里有「退出登录 / 音质 / 皮肤」，
            // 但设置页仍然是那个入口，不能因为改了用户区的跳转就把它藏起来。
            IconButton(Icons.Gear, skin.text3, { vm.go(Route.Settings); onPick() }, size = 18.dp)
        }

        SideGroup("在线音乐")
        val hidden = vm.sideHidden
        if ("daily" !in hidden) {
            SideItem(Icons.Radio, "每日推荐", vm.route is Route.Daily) { vm.go(Route.Daily); onPick() }
        }
        if ("radio" !in hidden) {
            SideItem(Icons.Album, "推荐歌单", vm.route is Route.Recommend) { vm.go(Route.Recommend); onPick() }
        }
        if ("charts" !in hidden) {
            SideItem(Icons.Chart, "榜单", vm.route is Route.Charts) { vm.go(Route.Charts); onPick() }
        }
        if ("podcast" !in hidden) {
            SideItem(Icons.Podcast, "播客", vm.route is Route.Podcasts) { vm.go(Route.Podcasts); onPick() }
        }
        // 音乐云盘（用户 m05071 第④点）：排在「播客」下面，与电脑端一致。
        if ("cloud" !in hidden) {
            SideItem(Icons.Download, "音乐云盘", vm.route is Route.Cloud) { vm.go(Route.Cloud); onPick() }
        }

        SideGroup("我的音乐")
        val liked = vm.playlists.firstOrNull { it.isLiked }
        if ("liked" !in hidden) {
            SideItem(Icons.HeartFilled, "我喜欢的音乐", vm.route is Route.Liked, count = liked?.trackCount) {
                vm.go(Route.Liked); onPick()
            }
        }
        if ("recent" !in hidden) {
            SideItem(Icons.History, "最近播放", vm.route is Route.Recent, count = vm.recent.size.takeIf { it > 0 }) {
                vm.go(Route.Recent); onPick()
            }
        }
        // 「正在下载」单独一栏，挂在「已下载音乐」**上方**（用户 m03577 第①点）。
        //
        // 这一行**常驻**，不做「有任务才出现」：用户说的是「该页面在有下载任务时
        // 上方显示全部开始/暂停、清空的按钮」—— 按钮按需出现，页面本身要在。
        // 藏起来的话，队列空了之后再想进来看看「是不是都下完了」就没有入口。
        // 数目只在真有时才显示（SideItem 对 count <= 0 本来就不画）。
        SideItem(Icons.Download, "正在下载", vm.route is Route.Downloading, count = vm.downloading) {
            vm.go(Route.Downloading); onPick()
        }
        // 「已下载音乐」放在「我的音乐」下 —— 它与「我喜欢的音乐」是同一类东西：
        // 都是「我本机有的歌」。桌面版把它放在这里也是这个理由（m36690 第⑤点）。
        if ("downloads" !in hidden) {
            SideItem(Icons.Download, "已下载音乐", vm.route is Route.Downloads, count = vm.localCount.takeIf { it > 0 }) {
                vm.go(Route.Downloads); onPick()
            }
        }

        if ("own" !in hidden) {
            SideGroup(
                "创建的歌单",
                action = {
                    // 「新建歌单」入口。桌面版挂在「创建的歌单」这一组的标题行上，
                    // 侧栏要滚到很下面才看得见歌单，所以按钮跟着组标题走比放在最底部好找。
                    SideGroupAction(Icons.Add, { showCreate = true }, "新建歌单")
                },
                folded = "own" in vm.sideFolded,
                onToggleFold = { vm.toggleSideGroup("own") },
            )
            val mine = vm.playlists.filter { !it.isLiked && !it.subscribed }
            // 折叠时整组行都不渲染（不是高度为 0 的占位）——
            // LazyColumn 里留一个空 item 会在滚动时露出一条缝。
            if ("own" !in vm.sideFolded) {
                for (p in mine) {
                    SideItem(
                        Icons.Album,
                        p.name,
                        vm.route is Route.Playlist && (vm.route as Route.Playlist).id == p.id,
                        count = p.trackCount,
                        cover = if (vm.sideCover) (vm.sideCovers[p.id] ?: p.cover) else "",
                        onLongClick = { menuFor = p },
                    ) {
                        vm.go(Route.Playlist(p.id, p.name)); onPick()
                    }
                }
            }
        }
        if ("sub" !in hidden) {
            SideGroup(
                "收藏的歌单",
                folded = "sub" in vm.sideFolded,
                onToggleFold = { vm.toggleSideGroup("sub") },
            )
            val subs = vm.playlists.filter { !it.isLiked && it.subscribed }
            if ("sub" !in vm.sideFolded) {
                for (p in subs) {
                    SideItem(
                        Icons.Album,
                        p.name,
                        vm.route is Route.Playlist && (vm.route as Route.Playlist).id == p.id,
                        count = p.trackCount,
                        cover = if (vm.sideCover) (vm.sideCovers[p.id] ?: p.cover) else "",
                        onLongClick = { menuFor = p },
                    ) {
                        vm.go(Route.Playlist(p.id, p.name)); onPick()
                    }
                }
            }
        }
        // 用户 m03593 第①点：两组都是空的、而且我们确实是登录状态，那就不是「这个号
        // 真没有歌单」，而是没拉到。给一行能点的重试，别让用户只能靠退出重进。
        val ownEmpty = vm.playlists.none { !it.isLiked && !it.subscribed }
        val subEmpty = vm.playlists.none { !it.isLiked && it.subscribed }
        if ((vm.loggedIn || vm.hasTicket) && ownEmpty && subEmpty) {
            SideItem(Icons.Refresh, "歌单没加载出来 · 点这里重试", false) {
                vm.ensureLibrary(force = true)
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                showCreate = false
                vm.createPlaylist(name)
            },
        )
    }

    // 长按歌单（第⑫点）。自己创建的歌单多两颗能改内容的（改名 / 删除），
    // 收藏来的只能播和进队列 —— 别人的歌单改不了，给了按钮也是白点。
    menuFor?.let { p ->
        val s = skin
        val owned = !p.subscribed
        var renaming by remember(p.id) { mutableStateOf(false) }
        var confirmDelete by remember(p.id) { mutableStateOf(false) }

        if (renaming) {
            EditTextDialog(
                title = "编辑歌单名称",
                initial = p.name,
                onDismiss = { renaming = false },
                onConfirm = { name -> renaming = false; vm.renamePlaylist(p.id, name) },
            )
            return@let
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                containerColor = s.bg1,
                title = { Text("删除歌单", color = s.text0) },
                // 删除不可撤销，所以不直接执行，先问一句。
                text = { Text("确定删除「${p.name}」？歌单里的歌不会从账号里消失，但这个歌单本身找不回来了。", color = s.text1, fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = { confirmDelete = false; menuFor = null; vm.deletePlaylist(p.id) }) {
                        Text("删除", color = s.accent)
                    }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = s.text2) } },
            )
            return@let
        }

        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = s.bg1,
            title = {
                Text(p.name, color = s.text0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Column {
                    MenuLine("播放") { menuFor = null; vm.playPlaylist(p.id, p.name) }
                    MenuLine("添加到播放队列") { menuFor = null; vm.appendPlaylistToQueue(p.id, p.name) }
                    if (owned) {
                        MenuSep()
                        MenuLine("编辑名称") { renaming = true }
                        MenuLine("删除歌单") { confirmDelete = true }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("取消", color = s.text2) } },
        )
    }
}

/**
 * 一个「标题 + 单行输入 + 确定」的小对话框。
 *
 * 侧栏的「编辑名称」和以后别的改名场景都用它，免得每处再抄一遍
 * AlertDialog + OutlinedTextField + 非空校验。
 */
@Composable
private fun EditTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val s = skin
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = { Text(title, color = s.text0) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("保存", color = if (text.isNotBlank()) s.accent else s.text3)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = s.text2) } },
    )
}

/** 新建歌单。桌面版是一个输入框 + 确定，这里同样只问名字。 */
@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val s = skin
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = s.bg1,
        title = { Text("新建歌单", color = s.text0) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("歌单名", color = s.text3, fontSize = 14.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("创建", color = if (name.isNotBlank()) s.accent else s.text3) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = s.text2) }
        },
    )
}

@Composable
private fun SideGroup(
    text: String,
    action: (@Composable () -> Unit)? = null,
    /**
     * 折叠状态（用户 m08537 第③点）。传 `null` 表示这一组不能折叠 ——
     * 「在线音乐」「我的音乐」这两组本来就只有几行，折叠起来反而多点一次。
     */
    folded: Boolean? = null,
    onToggleFold: (() -> Unit)? = null,
) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            // 四组标题行必须一样高（用户 m09508 第⑥点）。原来「创建的歌单」那行
            // 被右侧 38dp 的加号按钮撑到 160px，而「在线音乐」只有 67px —— 加号
            // 是 Material 的 IconButton，它把 38dp 的图标盒扩成 48dp 的最小触控区，
            // 于是整行跟着长高。这里把行高钉死，动作位自己缩进这个高度里。
            //
            // 40dp 而不是 24dp（用户 m09508 第⑤点「点了没反应」）：24dp 只有文字
            // 那一小条能点，手指落在标题上方/下方几个像素就整下失效，看着就像
            // 按钮坏了。40dp 与下面 SideItem 的行高（≈39dp）也对得上。
            .height(40.dp)
            .then(
                // 整行可点，不只是那颗小箭头 —— 组标题只有 11sp 高，
                // 让用户去戳一个 12dp 的箭头是在刁难人。
                if (folded != null && onToggleFold != null) {
                    Modifier
                        .clip(RoundedCornerShape(s.radii.baseDp))
                        .clickable { onToggleFold() }
                } else {
                    Modifier
                }
            )
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folded != null) {
            // 箭头：展开时朝下、折叠时朝右。用 rotation 而不是换两个图标 ——
            // 同一颗图标转 90° 的动画比「突然换一张图」更容易看懂发生了什么。
            androidx.compose.material3.Icon(
                Icons.ChevronDown,
                contentDescription = if (folded) "展开$text" else "收起$text",
                tint = skin.text3,
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { rotationZ = if (folded) -90f else 0f },
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = skin.text3, fontSize = 11.sp, modifier = Modifier.weight(1f))
        // 组标题右侧的动作位：目前只有「创建的歌单」用，挂「新建歌单」的加号。
        // 放在组标题上而不是列表末尾 —— 歌单多了以后列表会滚到很远。
        action?.invoke()
    }
}

/**
 * 组标题右侧的小图标按钮。
 *
 * 不用 [IconButton]：那个组件外层固定 38dp 的圆形触控盒（`Components.kt`），
 * 放在组标题行里会把整行撑高，四组标题就对不齐了（用户 m09508 第⑥点）。
 * 这里自己给 24dp 的盒子，图标 15dp，热区正好等于组标题行高。
 */
@Composable
private fun SideGroupAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    Box(
        Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            icon,
            contentDescription,
            tint = skin.text3,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun SideItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    active: Boolean,
    count: Int? = null,
    // 歌单行改用「第一首歌的封面」当图标。桌面版就是这么做的：侧栏几十个歌单
    // 全是同一个唱片图标的话，只能靠读名字区分；封面有图可认，一眼就找得到。
    cover: String = "",
    /**
     * 长按（第⑫点）：歌单行弹更多信息，其余行不给。
     *
     * 必须排在 [onClick] 前面 —— 九个调用点都是尾随 lambda 形式的 `{ ... }`，
     * 那个 lambda 永远绑到最后一个参数上；把 onLongClick 放最后会让所有
     * 点击都变成「长按」。
     */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(s.radii.baseDp))
            .background(if (active && s.style.marker == SkinStyle.Marker.Fill) s.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            // 瑞士风与街机风的选中态是「左侧一道杠 + 底色去掉」，
            // 不是整块浅强调底。用 drawBehind 画，不加 border-left ——
            // 条目高度固定，加边框会把各项之间的 1dp 间距吃掉。
            .drawBehind {
                if (active && s.style.marker == SkinStyle.Marker.Bar) {
                    val w = s.style.markerWidth.dp.toPx()
                    drawRect(
                        color = if (s.id == "arcade") s.text0 else s.accent,
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                    )
                }
            }
            // combinedClickable 而不是 clickable：长按要能弹菜单。没有 onLongClick
            // 的行（每日推荐、我喜欢的音乐…）仍然只有点击 —— 长按它们会走
            // 系统默认（无动作），不会误弹一个空菜单。
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover.isNotEmpty()) {
            Cover(cover, 17.dp, s.radii.baseDp.coerceAtMost(4.dp))
        } else {
            androidx.compose.material3.Icon(icon, null, tint = if (active) s.accent else s.text2, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text,
            color = if (active) s.accent else s.text0,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null && count > 0) {
            Text(count.toString(), color = s.text3, fontSize = 11.sp)
        }
    }
}

// ------------------------------------------------------------------ 迷你播放条

@Composable
fun MiniPlayer(vm: AppViewModel) {
    val song = vm.player.currentSong ?: return
    val s = skin
    val dur = vm.player.durationMs.coerceAtLeast(1)
    val pct = (vm.player.positionMs.toFloat() / dur).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxWidth()
            .background(s.bg1)
            // 点迷你条进「正在播放」整页。原来这里跳的是设置页 —— 那只是当时
            // 没有播放页时的临时落脚点，用户点了想看的显然不是设置。
            .clickable { vm.go(Route.NowPlaying) },
    ) {
        // 顶边那道色带（瑞士风 2px 红 / 街机风 3px 墨黑）。它压在最上面、
        // 进度线跟在它下面：色带是「这一款皮肤的身份」，位置固定不动，
        // 进度线则是会跑的东西，让身份先占位。
        val stripe = s.style.stripe
        if (stripe > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stripe.dp)
                    .background(s.style.stripeColor ?: s.accent),
            )
        }
        // 顶部一条 2px 的进度线，与桌面版底栏的进度条呼应
        Box(Modifier.fillMaxWidth().height(2.dp).background(s.bg3)) {
            Box(Modifier.fillMaxWidth(pct).height(2.dp).background(s.accent))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(song.cover, 42.dp, s.radii.baseDp.coerceAtMost(8.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.name, color = s.text0, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(song.artistText, color = s.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(Icons.Previous, s.text1, { vm.previous() })
            IconButton(
                if (vm.player.isPlaying) Icons.Pause else Icons.Play,
                s.accent,
                { vm.toggle() },
                size = 28.dp,
            )
            IconButton(Icons.Next, s.text1, { vm.next() })
            // 第⑥点：这里原来画着队列图标、接的却是 vm.cycleRepeat() —— 点「队列」
            // 变成了切循环模式，难怪用户说队列打不开。现在真的打开队列页；
            // 循环模式在队列页页头和「正在播放」页底部都有入口。
            IconButton(Icons.Queue, s.text1, { vm.go(Route.Queue) })
        }
    }
}

// ------------------------------------------------------------------ 登录

/**
 * 登录页。
 *
 * 只做手机号 + 密码与手机号 + 验证码两种 —— **没做扫码登录**：
 * 那需要在客户端生成并渲染二维码，而这次构建是离线环境，加不进 zxing 之类的
 * 二维码库，自己手写一个 QR 编码器又不值得（几百行只为登录一次）。
 * 接口层 `Endpoints.qrCreate()/qrPoll()` 是齐的，等能联网拉依赖时补上界面即可。
 */
@Composable
fun LoginScreen(vm: AppViewModel) {
    var phone by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var byCaptcha by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localErr by remember { mutableStateOf<String?>(null) }
    // 第④点：验证码冷却。0 表示可以点，>0 表示还要等多少秒。
    // 放在 `remember` 里而不是 Prefs：这是「这一次打开登录页」的节奏问题，
    // 关掉应用再回来本来就该能重发（服务端还有一层 60 秒限频兜底）。
    var cooldown by remember { mutableStateOf(0) }
    // 第⑥点：-462 安全验证门。`verify` 非空就弹安全验证，`qr` 是换回来的
    // 二维码 / H5 验证链接，`qrCode` 用来轮询「验证完成没有」。
    var verify by remember { mutableStateOf<RiskVerify?>(null) }
    var qr by remember { mutableStateOf<RiskQr?>(null) }
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var qrBusy by remember { mutableStateOf(false) }
    var qrErr by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // 倒计时：只有 cooldown > 0 时才挂这个 effect，归零后自然停。
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    // 一次登录尝试。抽成函数是因为**安全验证完成后要自动重试同一次登录** —— 
    // 验证只解开了服务端那道门，登录本身还得再发一次。
    val doLogin: suspend () -> Unit = {
        busy = true
        localErr = null
        try {
            // 第⑤点：这里必须看 code 而不是布尔值 —— 密码对了却提示
            // 「请检查账号密码」，根因就是原来只有 true/false。
            val r = if (byCaptcha) {
                Endpoints.loginCaptcha(phone, captcha)
            } else {
                Endpoints.loginPhone(phone, pwd)
            }
            if (r.ok) {
                vm.onLoginSuccess(Endpoints.exportCookie())
            } else if (r.verify != null) {
                // 门开了：不再丢一句没处下手的错误，直接把验证流程摆出来。
                verify = r.verify
            } else {
                localErr = r.hint
            }
        } catch (e: Exception) {
            localErr = vm.explain(e)
        }
        busy = false
    }

    // 拿到 -462 的参数后换一张二维码（+ 一条手机上可以直接打开的 H5 验证链接）。
    LaunchedEffect(verify) {
        val v = verify ?: return@LaunchedEffect
        if (qr != null) return@LaunchedEffect
        qrBusy = true
        qrErr = null
        qr = null
        qrImage = null
        val r = runCatching { Endpoints.riskQr(v) }.getOrNull()
        if (r == null || !r.ok) {
            qrErr = r?.message?.takeIf { it.isNotBlank() } ?: "二维码获取失败，请点「刷新」重试"
            qrBusy = false
            return@LaunchedEffect
        }
        qr = r
        // 二维码图片在网易云 CDN 上、带防盗链，所以走我们自己的 client 取字节，
        // 而不是让 Coil 裸取（会 403）。
        qrImage = runCatching {
            Endpoints.imageBytes(r.imageUrl)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }
        }.getOrNull()
        qrBusy = false
    }

    // 轮询验证结果：status >= 2 表示验证完成，自动重试登录。
    LaunchedEffect(qr?.qrCode) {
        val code = qr?.qrCode ?: return@LaunchedEffect
        if (code.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(2500)
            val st = runCatching { Endpoints.riskQrStatus(code) }.getOrNull() ?: continue
            if (st.done) {
                verify = null
                qr = null
                qrImage = null
                vm.notify("安全验证已完成，正在重新登录")
                doLogin()
                return@LaunchedEffect
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(skin.bg0, skin.bg1))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("网易云音乐 Lite", color = skin.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("登录后同步你的歌单与收藏", color = skin.text3, fontSize = 12.5.sp)
            Spacer(Modifier.height(26.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
                singleLine = true,
                label = { Text("手机号") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            if (byCaptcha) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = captcha,
                        onValueChange = { captcha = it.filter { c -> c.isDigit() }.take(6) },
                        singleLine = true,
                        label = { Text("短信验证码") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 第④点：冷却。发送成功后锁 60 秒，按钮上直接显示剩余秒数 ——
                    // 服务端同一号码 60 秒才放一条，没有冷却时用户连点几次只会
                    // 拿到「发送太频繁」，还以为是应用坏了。
                    PillButton(
                        text = if (cooldown > 0) "${cooldown}s" else "获取",
                        primary = cooldown == 0,
                    ) {
                        if (cooldown > 0 || busy) return@PillButton
                        scope.launch {
                            busy = true
                            localErr = runCatching { Endpoints.sendCaptcha(phone) }
                                .fold(
                                    { err -> err },
                                    { vm.explain(it) },
                                )
                            // 只有真的发出去了才开始倒计时：失败还锁 60 秒，
                            // 用户就只能干等一分钟再试，比不限频更糟。
                            if (localErr == null) cooldown = 60
                            busy = false
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    singleLine = true,
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            localErr?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = skin.accentHi, fontSize = 12.sp)
            }

            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (busy) skin.bg3 else skin.accent)
                    .clickable(enabled = !busy) {
                        scope.launch { doLogin() }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (busy) "登录中…" else "登录", color = skin.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (byCaptcha) "改用密码登录" else "改用短信验证码登录",
                color = skin.accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { byCaptcha = !byCaptcha; localErr = null }
                    .padding(8.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "也可以先跳过，游客状态下每日推荐和搜索都能用。",
                color = skin.text3,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clickable { vm.go(Route.Daily) }
                    .padding(8.dp),
            )
        }

        // 第⑥点：安全验证弹窗。
        // 网易云对「新设备 + 短信验证码」登录会回 -462，要求先把账号级验证过掉；
        // 桌面端早就会对关注这类动作弹二维码（`electron/api/endpoints.js` 的
        // `riskQr()`），登录这条路原来只丢一句「需要先完成风控验证」，用户根本
        // 不知道该去哪儿验证 —— 这里把二维码 / H5 链接摆出来，验完自动重登。
        verify?.let { v ->
            AlertDialog(
                onDismissRequest = { verify = null; qr = null; qrImage = null },
                title = { Text("需要安全验证", fontWeight = FontWeight.SemiBold) },
                text = {
                    Column {
                        Text(
                            v.blockText.ifBlank {
                                "网易云要求先确认是你本人在登录（换手机、换设备时常见）。用当前这台手机打开验证页，或用另一台设备扫下面的二维码，完成后会自动重新登录。"
                            },
                            color = skin.text2,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        when {
                            qrBusy -> Text("正在获取二维码…", color = skin.text3, fontSize = 12.5.sp)
                            qrErr != null -> Text(qrErr!!, color = skin.accent, fontSize = 12.5.sp)
                            qrImage != null -> Image(
                                bitmap = qrImage!!,
                                contentDescription = "安全验证二维码",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .size(180.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        }
                        // H5 验证链接：同一台手机上直接点开最省事（不用找第二台设备扫码）。
                        qr?.verifyUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "用本机浏览器打开验证页",
                                color = skin.accent,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable {
                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    }
                                    .padding(vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "验证只解开门锁，登录还要再发一次；点下面的按钮可以马上重试。",
                            color = skin.text3,
                            fontSize = 11.5.sp,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { scope.launch { doLogin() } }) { Text("我已完成，重试登录") }
                },
                dismissButton = {
                    TextButton(onClick = { qr = null; qrImage = null; qrErr = null }) { Text("刷新二维码") }
                },
            )
        }
    }
}