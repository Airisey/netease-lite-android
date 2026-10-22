package com.netease.lite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.netease.lite.model.ArtistProfile
import com.netease.lite.model.Playlist
import com.netease.lite.model.Song
import com.netease.lite.model.User

/**
 * 通用组件。
 *
 * 与桌面版 `views.js` 的 `TrackList` / `playlistCard` 等一一对应，
 * 但布局换成「手机竖屏一行放得下」的形式：桌面的五列网格
 * （序号 / 标题 / 歌手 / 专辑 / 时长）在 360dp 宽的手机上会挤成一团，
 * 所以这里的行是「封面 48 + 标题/歌手两行 + 时长 + 红心」。
 *
 * 街机风的 2px 描边与硬投影没法像 CSS 那样一条规则全局生效，
 * 所以抽成 [skinBorder] / [skinShadow] 两个修饰符，各处显式套上。
 */

/**
 * 皮肤描边。街机风 2dp 墨黑，其余为 0（用一条 1px 的 --line 收边）。
 *
 * 用户 m00932 第④点：描边的粗细也要按元素大小**成比例**，不能一律 2dp。
 * 基准是歌单页顶部第一首歌的封面大图（104dp）配 2dp 描边，见 [HARD_SHADOW_REF_DP]。
 * 一颗 44dp 的头像按这个比例只有 0.85dp —— 这正是用户要的效果（原来 2dp 的
 * 黑框套在小头像上像贴了张邮票）。
 *
 * 为什么不用 `Modifier.border`：它要的是「组合时就确定的宽度」，而这个宽度得看
 * 节点实测尺寸。所以改成 `drawWithContent` 里自己画，画法与 `border` 一致
 * （沿内缩半个线宽的矩形描边），链式顺序的画法先后也与 `border` 完全相同，
 * 因此各处原有的调用位置不用动。
 *
 * 例外是 [hairline]：那条 1dp 的 `--line` 是「收边」而不是装饰，它跟皮肤大小
 * 无关，保持恒定 —— 也没有任何比例可依（0 的比例还是 0）。
 */
@Composable
fun Modifier.skinBorder(
    radius: Dp = skin.radii.baseDp,
    hairline: Boolean = true,
): Modifier {
    val s = skin
    val stroke = s.stroke
    if (stroke <= 0 && !hairline) return this
    val color = if (stroke > 0) s.text0 else s.line
    val ratio = if (stroke > 0) stroke / HARD_SHADOW_REF_DP else 0f
    val density = LocalDensity.current
    val r = with(density) { radius.toPx() }
    return this.drawWithContent {
        drawContent()
        // 最细不低于 1 个物理像素：再细的线会被抗锯齿抹成一片灰，
        // 看起来像脏边而不是描边。
        val w = if (ratio > 0f) {
            (minOf(size.width, size.height) * ratio).coerceAtLeast(1f)
        } else {
            1.dp.toPx()
        }
        val inset = w / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f)),
            style = Stroke(width = w),
        )
    }
}

/**
 * 街机风的硬投影（`4px 4px 0 #111`）。
 *
 * Compose 的 `shadow()` 画的是模糊阴影，做不出这种印刷感。这里按 CSS `box-shadow`
 * 的语义来画：**投影不占布局**，元素尺寸不变，阴影从右下角探出去一圈。
 *
 * 所以不能用「外面套一个 matchParentSize 的实心块、再给内容加 padding」那种写法 ——
 * padding 会让内容的可用尺寸少 4dp，封面、卡片会整体缩小 4dp，而桌面版里
 * box-shadow 是纯绘制、不参与布局的。改成在父节点上 `drawWithContent`：
 * 先画一块偏移的圆角实心块，再画内容；内容（封面 / 带底色的按钮）不透明，
 * 正好把重叠区域盖掉，只留右下那道边。
 *
 * 偏移量按元素大小**成比例**（用户 m00932 第④点），基准是歌单页顶部第一首歌的
 * 封面大图：104dp 的封面配 4dp 阴影，所以比例是 `offset / 104dp`。
 * 这样 104dp 的封面算出来还是 4dp（与改动前一模一样），而 44dp 的小封面、
 * 60dp 的播放键只有 1.7~2.3dp —— 原来一律 4dp 时，那颗播放键的阴影比它自己的
 * 描边还粗，看起来像没对齐的重影。取 `min(宽, 高)` 而不是单独某一维：
 * 按钮的宽是内容撑出来的，用宽算会让同一个按钮因为文案长短就有不同阴影。
 */
@Composable
fun HardShadowBox(
    offset: Int = skin.hardShadow,
    radius: Dp = skin.radii.baseDp,
    shadowColor: Color = skin.text0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (offset <= 0) {
        content()
        return
    }
    val ratio = offset / HARD_SHADOW_REF_DP
    val density = LocalDensity.current
    val r = with(density) { radius.toPx() }
    Box(
        modifier.drawWithContent {
            val o = minOf(size.width, size.height) * ratio
            drawRoundRect(
                color = shadowColor,
                topLeft = Offset(o, o),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(r, r),
            )
            drawContent()
        },
    ) {
        content()
    }
}

/** 封面。Coil 会自己走我们配的 Referer 头（见 NeteaseLiteApp 的 ImageLoader）。 */
@Composable
fun Cover(
    url: String?,
    size: Dp,
    radius: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(skin.bg3),
    ) {
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 头像（第⑰点）。
 *
 * 与 [Cover] 的区别只有「轮廓」：头像在桌面版是跟着皮肤走的 ——
 * 经典/现代白/落日余晖是圆的，瑞士风与街机风是方的，街机风还带 4dp 硬投影
 * （`renderer\skins.css` 里 `.user-head` 既在「直角 + 2px 描边」组，
 * 也在 `box-shadow: 4px 4px 0 var(--ink)` 名单里）。
 * 所以这里不能直接复用 `Cover(..., 999.dp)`：圆角是写死在调用点的，
 * 换肤时头像会与同屏的方形卡片打架。
 */
@Composable
fun Avatar(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val s = skin
    val square = s.style.button == SkinStyle.Button.Rect
    val r = if (square) s.radii.baseDp else 999.dp
    HardShadowBox(offset = s.hardShadow, radius = r, modifier = modifier) {
        Box(Modifier.skinBorder(r)) {
            Cover(url, size, r)
        }
    }
}

/**
 * 歌曲后缀（别名）的循环滚动条（第⑱点）。
 *
 * 与桌面 `.tl-alias` 对齐的三条规则：
 *  1. 放得下就静态显示，不滚动 —— `basicMarquee` 只在内容超出宽度时才动；
 *  2. 放不下就循环滚动，段与段之间留 [spacing]；
 *  3. 左右两端渐隐，而不是被硬边切成半截字（桌面用的是 `mask-image`，
 *     这里用 `DstIn` 混合模式把一条水平渐变当作遮罩）。
 *
 * 不用 `TextOverflow.Ellipsis`：后缀被省略号截断等于没显示，
 * 而它存在的意义就是「把这一长串别名读全」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AliasMarquee(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = color,
        fontSize = fontSize,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            // 遮罩要作用于整层像素，所以先让这一层离屏合成。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 12.dp.toPx().coerceAtMost(size.width / 3f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        fade / size.width to Color.Black,
                        1f - fade / size.width to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                repeatDelayMillis = 1200,
                initialDelayMillis = 800,
                spacing = MarqueeSpacing(28.dp),
            ),
    )
}

/**
 * 昵称这类「必须读全、又不许挤掉右边徽标」的长文本（m02003 第⑤点）。
 *
 * 和 [AliasMarquee] 的差别只有一处：**放得下时不加两端渐隐**。昵称常常是两三个字，
 * 若照抄后缀那套遮罩，字少的时候开头第一个字也会被吃掉一点，看着像脏了。
 * 所以这里先用 `onTextLayout` 问一句「真的溢出没有」，只有溢出（也就是真的在滚动）
 * 才把渐隐打开。
 *
 * 调用方**必须**给它一个 `Modifier.weight(...)`：Row 先量不带 weight 的兄弟（VIP
 * 徽标），剩下的空间才轮到它。不然长昵称会先把自己量到整行宽，徽标被压成一条缝，
 * 「VIP」三个字母就竖着排了 —— 那正是用户报的那个样子。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NameMarquee(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier,
) {
    var overflowing by remember(text) { mutableStateOf(false) }
    Text(
        text,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { overflowing = it.hasVisualOverflow },
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (!overflowing) return@drawWithContent
                val fade = 12.dp.toPx().coerceAtMost(size.width / 3f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        fade / size.width to Color.Black,
                        1f - fade / size.width to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .basicMarquee(
                iterations = Int.MAX_VALUE,
                repeatDelayMillis = 1200,
                initialDelayMillis = 800,
                spacing = MarqueeSpacing(28.dp),
            ),
    )
}

/** 区块标题，对应桌面版的 `.page-title` / `.daily-head` 的 h2。 */
@Composable
fun SectionTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, color = skin.text0, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, color = skin.text2, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun PageTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
        Text(text, color = skin.text0, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (!sub.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(sub, color = skin.text2, fontSize = 12.sp)
        }
    }
}

/**
 * 歌曲行。
 *
 * `trailing` 用来放各页不同的尾巴（播放页没有、歌单页是「⋯」菜单）；
 * `leading` 放序号或勾选框。两者都用 slot 而不是枚举，
 * 免得为了一个新页面就往这个组件里塞一个 when 分支。
 */
@Composable
fun SongRow(
    song: Song,
    index: Int,
    isPlaying: Boolean,
    liked: Boolean,
    local: Boolean,
    onClick: () -> Unit,
    onLike: () -> Unit,
    onMore: (() -> Unit)? = null,
    showIndex: Boolean = true,
    /** 多选模式：左侧序号槽换成勾选框，点行本身改成勾选而不是播放。 */
    selected: Boolean? = null,
    /** 是否显示封面。设置里「歌曲列表显示封面」关掉时传 false，行高也跟着收窄。 */
    showCover: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val s = skin
    val picking = selected != null
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(s.radii.baseDp))
            .background(
                when {
                    picking && selected == true -> s.accentSoft
                    isPlaying && s.style.marker == SkinStyle.Marker.Fill -> s.accentSoft
                    else -> Color.Transparent
                },
            )
            // 瑞士风 / 街机风的选中态不是整块提亮，而是左侧一道杠。
            // 用 drawBehind 而不是加 border-left：行高是固定的 58dp，
            // 加边框会参与盒模型，把每行挤矮 3dp。
            .drawBehind {
                if ((picking && selected == true) || (isPlaying && s.style.marker == SkinStyle.Marker.Bar)) {
                    val w = s.style.markerWidth.dp.toPx()
                    drawRect(
                        color = if (s.id == "arcade") s.text0 else s.accent,
                        size = Size(w, size.height),
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (picking) {
            // 勾选框：街机风画方框 + 实心块，其余三款画圆环 + 实心圆。
            // 与桌面的 .tl-check 一致 —— 那一款连「选中」这件事都是印刷体。
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                val on = selected == true
                val round = !s.style.squarePlayingDot
                val shape = if (round) RoundedCornerShape(999.dp) else RoundedCornerShape(2.dp)
                Box(
                    Modifier
                        .size(17.dp)
                        .clip(shape)
                        .background(if (on) s.accent else Color.Transparent)
                        .skinBorder(if (round) 999.dp else 2.dp, hairline = true),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) {
                        androidx.compose.material3.Icon(
                            Icons.Check,
                            contentDescription = null,
                            tint = s.onAccent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        } else if (showIndex) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (isPlaying) {
                    // 正在播的那首不显示序号，改成一个强调色的音符。
                    // 街机风把它换成实心小方块 —— 那一款的「现在」是一个色块，
                    // 不是符号（桌面版 .tl-eq i 就是这么画的）。
                    if (s.style.squarePlayingDot) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(s.accent)
                                .skinBorder(0.dp),
                        )
                    } else {
                        Text("♪", color = s.accent, fontSize = 14.sp)
                    }
                } else {
                    Text(
                        (index + 1).toString(),
                        color = s.text3,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        // 封面圆角比卡片小一号：街机风卡片圆角是 0，封面也不该突然变圆
        if (showCover) {
            Cover(song.cover, 44.dp, s.radii.baseDp.coerceAtMost(8.dp))
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.name,
                    color = if (isPlaying) s.accent else s.text0,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 第⑱点：歌名后面接歌曲后缀（别名），放不下时循环滚动、两端渐隐。
                // 与桌面 `.tl-alias` 同一套规则：歌名优先，后缀只吃剩下的空间；
                // 歌名自己都撑满一行时后缀整块让位（这里 weight 分到 0 宽度，
                // 自然就看不见了，等价于桌面的 .tl-alias.yielded）。
                val alias = song.alias.joinToString(" / ")
                if (alias.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    AliasMarquee(
                        text = alias,
                        color = s.text3,
                        fontSize = 11.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (local) {
                    Spacer(Modifier.width(4.dp))
                    Text("√", color = s.accent, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(3.dp))
            // 第⑱点：VIP 标识从歌名后面挪到歌手名前面。
            // 歌名那一行已经很挤（歌名 + 后缀 + √），VIP 挤在里面会被当成
            // 歌名的一部分；挪到第二行开头，和「谁唱的」贴在一起读更顺。
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isPaid) VipTag(leadingSpace = false)
                Text(
                    song.artistText.ifEmpty { "未知歌手" },
                    color = s.text2,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            fmtDuration(song.duration),
            color = s.text3,
            fontSize = 11.5.sp,
        )
        Spacer(Modifier.width(6.dp))
        IconButton(
            icon = if (liked) Icons.HeartFilled else Icons.HeartOutline,
            tint = if (liked) s.accent else s.text3,
            onClick = onLike,
            size = 20.dp,
        )
        if (onMore != null) {
            IconButton(Icons.More, s.text3, onMore, 20.dp)
        }
    }
}

/**
 * VIP 标识。
 *
 * 用户 m00932 第⑪点：以前只有两支（瑞士风平涂红、其余四款一律金→橙渐变），
 * 五款皮肤里四款的会员标识长得一模一样。现在观感全部由 [SkinStyle.vip] 描述，
 * 这个函数只负责把它画出来：
 *
 *   * **经典黑** —— 不填色，只描一圈金 + 亮金字（描金）。黑底上金色最贵气，
 *     但填成实心块会在歌曲行里抢过歌名，所以只留一道边；
 *   * **现代白** —— 强调蓝实底 + 白字。白底上金色会发脏；
 *   * **瑞士风** —— 强调红平涂 + 纸白字（定稿，原样保留）；
 *   * **落日余晖** —— 夕照橙→金渐变 + 一点外发光，发光是这款皮唯一的形态语言；
 *   * **街机风** —— 糖果黄底 + 墨字 + 2dp 墨黑描边 + 2dp 硬投影，
 *     跟这款皮的卡片/按钮/头像同一套「贴纸」画法。
 */
@Composable
fun VipTag(leadingSpace: Boolean = true) {
    val s = skin
    val v = s.style.vip
    if (leadingSpace) Spacer(Modifier.width(5.dp))
    val corner = (v.radius ?: s.radius).dp
    val shape = RoundedCornerShape(corner)
    HardShadowBox(offset = v.hardShadow, radius = corner, shadowColor = s.text0) {
        Box(
            Modifier
                // 外发光画成阴影：Compose 只有模糊阴影，用 `shadow(0dp)` 之外没有
                // 直接画「四周一圈光」的 API，而模糊的、无偏移、同色的阴影正是它。
                .then(
                    if (v.glow > 0) {
                        Modifier.shadow(v.glow.dp, shape, ambientColor = s.accentHi, spotColor = s.accentHi)
                    } else {
                        Modifier
                    },
                )
                .clip(shape)
                .then(
                    when {
                        v.fill == null -> Modifier
                        v.fillTo != null -> Modifier.background(Brush.linearGradient(listOf(v.fill, v.fillTo)))
                        else -> Modifier.background(SolidColor(v.fill))
                    },
                )
                // 描边写在底色之后：`border` 画在内容之上，顺序反了会被底色盖掉。
                .then(
                    if (v.border != null) Modifier.border(v.borderWidth.dp, v.border, shape) else Modifier,
                )
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                "VIP",
                color = v.text,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                // 宁可让外面滚动，也绝不让「VIP」自己折行：这也是 m02003 第⑤点
                // 里「徽标被挤成竖排」的另一半保险（父级量错宽度时兜底）。
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
fun IconButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size))
    }
}

/**
 * 空态。对应桌面版的 `emptyState()`。
 *
 * 左右各留 28dp 并把提示居中：折叠屏外屏只有 344dp 宽，不留边距时那条
 * 两行的说明文字会顶到屏幕边缘（实测外屏上正好贴着左右两边），
 * 居中之后窄屏看起来是「一段说明」，宽屏也不会显得空。
 */
@Composable
fun EmptyState(text: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = skin.text2, fontSize = 14.sp)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                color = skin.text3,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
fun LoadingBlock(hint: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = skin.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(hint, color = skin.text3, fontSize = 12.sp)
        }
    }
}

/** 歌单卡片，横向列表用。 */
@Composable
fun PlaylistCard(p: Playlist, onClick: () -> Unit) {
    val s = skin
    Column(
        Modifier
            .width(132.dp)
            .clickable(onClick = onClick),
    ) {
        // 街机风的卡片是「贴纸」：2dp 墨黑描边 + 4dp 硬投影（桌面版
        // `.card-cover { border: 2px solid var(--ink); box-shadow: 4px 4px 0 var(--ink) }`）。
        // HardShadowBox 只在这一款里真的画出东西，其余四款 offset=0 直接透传。
        HardShadowBox(radius = s.radii.baseDp) {
            Box(
                Modifier
                    .skinBorder(s.radii.baseDp)
                    .clip(RoundedCornerShape(s.radii.baseDp)),
            ) {
                Cover(p.cover, 132.dp, s.radii.baseDp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            p.name,
            color = skin.text0,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
        )
        if (p.playCount > 0) {
            Spacer(Modifier.height(3.dp))
            Text(fmtCount(p.playCount), color = skin.text3, fontSize = 11.sp)
        }
    }
}

/** 横向滚动的一排歌单。 */
@Composable
fun HorizontalPlaylists(items: List<Playlist>, onPick: (Playlist) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items.size) { i ->
            PlaylistCard(items[i]) { onPick(items[i]) }
        }
    }
}

/**
 * 歌手卡片，与 [PlaylistCard] 同宽同节奏 —— 搜索结果和歌手页的「相似歌手」
 * 并排放的时候才不会一高一低。
 *
 * 头像是圆的：歌手不是商品，方封面让人下意识去找「播放」按钮。
 */
@Composable
fun ArtistCard(a: ArtistProfile, onClick: () -> Unit) {
    val s = skin
    Column(
        Modifier
            .width(132.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HardShadowBox(radius = s.radii.baseDp) {
            Box(Modifier.skinBorder(s.radii.baseDp)) {
                Avatar(a.avatar, 92.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            a.name,
            color = s.text0,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
        )
        val sub = when {
            a.musicSize > 0 -> "${a.musicSize} 首"
            a.albumSize > 0 -> "${a.albumSize} 张专辑"
            else -> ""
        }
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(sub, color = s.text3, fontSize = 11.sp)
        }
    }
}

/**
 * 用户行（关注 / 粉丝列表用）。
 *
 * 做成行而不是卡片：这两个列表动辄上百人，卡片扫起来太慢。
 * 右侧把「关注 / 粉丝」两个数并排给出 —— 判断要不要点进去，这两个数最有用。
 */
@Composable
fun UserRow(u: User, onClick: () -> Unit) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(u.avatar, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 昵称：`weight(1f)` 让 VIP 徽标先量出自己的固定宽度，昵称只吃剩下的；
                // 昵称过长时原地循环滚动而不是省略号（m02003 第⑤点）。以前这里是
                // 不带宽度的 Text + Ellipsis，长昵称把整行占满，VipTag 只剩几个像素，
                // 「VIP」被逐字折行，看起来就是竖着的三个字母。
                NameMarquee(
                    text = u.nickname,
                    color = s.text0,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                if (u.vipType > 0) {
                    Spacer(Modifier.width(5.dp))
                    VipTag()
                }
            }
            if (u.signature.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    u.signature,
                    color = s.text2,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${u.follows} 关注 · ${u.followeds} 粉丝",
            color = s.text3,
            fontSize = 11.sp,
        )
    }
}

// --------------------------------------------------------------- 格式化

/**
 * 毫秒 → `mm:ss`（超过一小时给 `h:mm:ss`）。
 * 与桌面版 `U.fmtTime` 同一口径。
 */
fun fmtDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 播放次数：一万以上显示「12万」，一亿以上显示「1.2亿」。 */
fun fmtCount(n: Long): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000.0)
    n >= 10_000 -> "${n / 10_000}万"
    else -> n.toString()
}

/**
 * 毫秒时间戳 → `2024-03-07`。
 *
 * 与桌面版 `U.fmtDate` 同一口径（只给到日）。歌单页的「创建时间」只需要知道
 * 是哪一年建的，精确到分钟既没用又占地方。0 或负数返空串，调用方据此整行不画。
 */
fun fmtDate(ms: Long): String {
    if (ms <= 0) return ""
    val d = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%04d-%02d-%02d".format(
        d.get(java.util.Calendar.YEAR),
        d.get(java.util.Calendar.MONTH) + 1,
        d.get(java.util.Calendar.DAY_OF_MONTH),
    )
}
