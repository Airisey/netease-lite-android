package com.netease.lite.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 五款皮肤。
 *
 * 桌面版的皮肤全部写在 CSS 里（`styles.css` 是经典黑，`skins.css` 覆盖其余四款），
 * Compose 没有 CSS，所以这里把它们**逐值搬**成 Kotlin 常量。
 *
 * 为什么不用 Material 的 `darkColorScheme` / `lightColorScheme` 硬套：
 * 源应用的颜色体系是 `--bg-0..4`（五级背景）+ `--text-0..3`（四级文字）+
 * `--line/--line-2`，和 Material 的 `surface` / `onSurface` / `outline` 并不是
 * 一一对应。硬套的结果是某款皮肤下某个次要文字突然变成品牌色这类怪事。
 * 所以这里自己定义一套 `SkinColors`，Material 的 colorScheme 只用来兜住
 * 我们没有显式指定的控件（对话框、菜单、涟漪等）。
 *
 * `--radius` / `--radius-lg` 也一起搬过来：瑞士风和街机风是直角，
 * 经典黑/现代白/落日余晖是 8/12px。Compose 里圆角是数值不是 CSS，
 * 所以做成 `Radii` 数据类随皮肤走。
 */

/** 一款皮肤的完整取色。字段名与 `skins.css` 的变量一一对应，方便日后比对。 */
data class SkinColors(
    val id: String,
    val name: String,
    val desc: String,
    /** 皮肤选择菜单里的左右两块色：左强调、右底色。 */
    val swatch: Pair<Color, Color>,

    val accent: Color,
    val accentSoft: Color,
    /** 强调色的「悬停/按下」加深版；街机风这里反而不是强调色，是墨黑，见下。 */
    val accentHi: Color,

    val bg0: Color,
    val bg1: Color,
    val bg2: Color,
    val bg3: Color,
    val bg4: Color,
    val bgHover: Color,
    val bgActive: Color,

    val text0: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,

    val line: Color,
    val line2: Color,

    val radius: Int,
    val radiusLg: Int,

    /** 是否为深色底 —— 决定状态栏图标亮还是暗、以及默认用什么 colorScheme 兜底。 */
    val dark: Boolean,

    /**
     * 街机风专用：2px 墨黑描边 + 硬投影（`4px 4px 0 #111`）。
     * 其余四款是 0。Compose 里描边和投影都要显式画，所以把它做成数值。
     */
    val stroke: Int = 0,
    /** 硬投影的偏移，街机风是 4dp，其余是 0。 */
    val hardShadow: Int = 0,
    /** 强调色填充上的文字颜色。街机风的黄太亮，白字压上去只有 1.3:1，改成墨黑。 */
    val onAccent: Color = Color.White,

    /**
     * 这款皮肤的「性格」。配色之外的东西 —— 边框带、选中标记、按钮形状、
     * 发光 —— 全在这里，界面各处只读这些字段，不再各自判 `id == "swiss"`。
     *
     * 为什么要有这一层：桌面版 `skins.css` 里每款皮肤都有自己的 200 行规则
     * （瑞士风的标题栏压一条 2px 红线、街机风压 3px 墨线并给侧栏描边、
     * 落日余晖的播放键带橙色光晕）。安卓版原来只有配色不同，
     * 换肤只换来换色，五款皮看起来是同一个界面调了色板。
     */
    val style: SkinStyle = SkinStyle(),
) {
    val radii: Radii get() = Radii(radius, radiusLg)
}

/**
 * 一款皮肤在「配色之外」的形态。
 *
 * 默认值 = 经典黑的那一套（无边框带、填充式选中、胶囊按钮、无光晕），
 * 其余皮肤只覆盖自己不同的那几项。
 */
data class SkinStyle(
    /** 顶栏底边 / 迷你播放条顶边的那道色带。0 表示不画。 */
    val stripe: Int = 0,
    /** 色带颜色；null 表示用强调色。街机风用它画墨黑。 */
    val stripeColor: Color? = null,

    /** 选中项（侧栏条目、正在播放的歌曲行）的标记方式。 */
    val marker: Marker = Marker.Fill,
    /** [Marker.Bar] 时左侧那道杠的宽度。 */
    val markerWidth: Int = 3,

    /** 侧栏 / 队列面板要不要一圈描边（街机风把整个抽屉当一张硬卡片）。 */
    val panelBorder: Boolean = false,

    /** 按钮形状。胶囊是默认，瑞士风与街机风是矩形。 */
    val button: Button = Button.Pill,

    /** 强调色块周围的光晕半径（dp）。落日余晖用它做「太阳边缘」。 */
    val glow: Int = 0,

    /**
     * VIP 标签的观感。
     *
     * 这里原来只有一个 `flatVip: Boolean` —— 只够区分「瑞士风平涂」和「其余四款
     * 一律金→橙渐变」，等于五款皮肤里四款的会员标识长得一模一样，换肤换了个寂寞。
     * 现在每款一套（用户 m00932 第⑪点）。瑞士风那一套是定稿，原样搬过来。
     */
    val vip: VipStyle = VipStyle(),

    /**
     * 当前句歌词的发光色（自带透明度）。null = 不发光。
     *
     * 桌面端只有深色底那几款给 `.lyric-line.active` 加 text-shadow：经典黑是
     * `0 0 18px rgba(255,255,255,.35)`，落日余晖换成强调色的 55%；三款浅色底
     * 都显式写了 `text-shadow: none`（浅底上白光只会把黑字糊成一团）。
     */
    val lyricGlow: Color? = null,

    /** 正在播放的歌曲行里那个「♪」要不要换成实心小方块（街机风）。 */
    val squarePlayingDot: Boolean = false,

    /**
     * 每日推荐 / 最近播放上方那条「大框框」的样子（m00001 第⑤点）。
     *
     * 桌面版这块是 `renderer/styles.css` 的 `.daily-head` 加 `skins.css` 里
     * 每款皮肤的一条覆盖规则。以前安卓端只顾自己「用皮肤强调色做个渐变」，
     * 于是同一款皮肤在两端长得不一样：现代白/瑞士风在桌面上是极淡的一层，
     * 手机上却是一块明显发红的色块；街机风在桌面上是薄荷色块 + 墨黑粗边，
     * 手机上还是黄渐变。现在把桌面那四组值逐款搬过来。
     */
    val hero: HeroStyle = HeroStyle(),
) {
    enum class Marker {
        /** 整块浅强调底 + 文字提亮（经典黑 / 现代白 / 落日余晖）。 */
        Fill,

        /** 只留左侧一道杠，底色去掉（瑞士风的红线 / 街机风的墨黑杠）。 */
        Bar,
    }

    enum class Button { Pill, Rect }
}

/**
 * 每日推荐 / 最近播放上方那条「大框框」的样子（m00001 第⑤点）。
 *
 * 逐项对应桌面版 `.daily-head` 的 CSS：
 *
 * | 皮肤 | 桌面写法 |
 * | --- | --- |
 * | 经典黑 | `linear-gradient(120deg, rgba(236,65,65,.16), rgba(236,65,65,.03) 62%)` + 1px `rgba(236,65,65,.18)` 边 |
 * | 现代白 | `linear-gradient(120deg, rgba(accent,.07), rgba(accent,.015) 62%)` + `--line` 边 |
 * | 瑞士风 | `--bg-3` 纸面 + `--line` 边 + `inset 4px 0 0 accent` 左侧红线 |
 * | 落日余晖 | 横向橙渐变（.22 → .04 → 透明）叠在 `--bg-3` 上 + `rgba(accent,.28)` 边 + 左侧 4px 橙杠 |
 * | 街机风 | 薄荷 `#7ee2b7` 平涂 + 2px 墨黑边 + 4px 硬投影，标题与说明都用墨黑，说明 72% 不透明 |
 *
 * @param colors 底色。**两个以上**时按 [angleIsDiagonal] 指定方向铺渐变
 *   （对应 CSS 的 `linear-gradient(120deg, …)`）；只给一个就是平涂。
 * @param base 渐变**下面**那层实色（CSS 里 `linear-gradient(…), var(--bg-3)` 的
 *   后半段）。null = 不垫底；给了就让渐变的透明端透出这个颜色。
 * @param stops 与 [colors] 一一对应的停止位置（0~1）；null 表示均匀分布。
 * @param direction 渐变方向：true = 120° 斜向（左上→右下），false = 90° 横向。
 * @param border 描边色；null = 不描边。
 * @param borderWidth 描边宽度（dp）。桌面是 1px，街机风 2px。
 * @param leftBar 左侧那道内嵌杠的颜色（瑞士风红线 / 落日橙杠）；null = 没有。
 * @param leftBarWidth 杠宽（dp），桌面是 4px。
 * @param hardShadow 硬投影偏移（dp），街机风 4dp。
 * @param titleColor / bodyColor / bodyAlpha 文字色；null 表示沿用皮肤的
 *   `text0` / `text2`。
 */
data class HeroStyle(
    val colors: List<Color> = emptyList(),
    val base: Color? = null,
    val stops: List<Float>? = null,
    val angleIsDiagonal: Boolean = true,
    val border: Color? = null,
    val borderWidth: Int = 1,
    val leftBar: Color? = null,
    val leftBarWidth: Int = 4,
    val hardShadow: Int = 0,
    val titleColor: Color? = null,
    val bodyColor: Color? = null,
    val bodyAlpha: Float = 1f,
)

/**
 * 一款皮肤的 VIP 标识长什么样（用户 m00932 第⑪点）。
 *
 * 底色三种给法：
 *   * [fill] 与 [fillTo] 都给 → 渐变；
 *   * 只给 [fill] → 平涂；
 *   * 都不给 → 不填充，只剩描边（经典黑的「描金」就是这一支，
 *     也是桌面 `styles.css` 的 `.ucard-vip` 的做法：透明底 + 描边 + 亮字）。
 *
 * 默认值 = 经典黑那一套。其余皮肤只覆盖自己不同的几项。
 */
data class VipStyle(
    /** 底色；null = 不填（描边式）。 */
    val fill: Color? = null,
    /** 渐变的终止色；null = 平涂。 */
    val fillTo: Color? = null,
    /** 字色。 */
    val text: Color = Color(0xFFF0C86A),
    /** 描边色；null = 不描边。 */
    val border: Color? = Color(0xFFE8C46A),
    /** 描边宽度（dp）。这个标签只有 9sp 高，2dp 已经是它的极限。 */
    val borderWidth: Int = 1,
    /** 硬投影偏移（dp）。街机风用 2，跟它的 `4px 4px 0` 是同一套语法、小一号。 */
    val hardShadow: Int = 0,
    /** 外发光半径（dp）。落日余晖用 6 —— 这款皮唯一的形态语言就是发光。 */
    val glow: Int = 0,
    /** 圆角；null = 跟皮肤的 [SkinColors.radius] 走（直角皮肤自然是 0）。 */
    val radius: Int? = null,
)

/**
 * 街机风阴影的基准尺寸（dp）：歌单页顶部第一首歌的封面大图是 104dp，
 * 它的硬投影是 [SkinStyle.hardShadow] = 4dp，比例 4/104。
 *
 * 用户 m00932 第④点要求所有硬投影按**这个比例**随元素大小缩放，而不是一律 4dp。
 * 桌面端是反过来的（`skins.css` 里明写「阴影偏移固定 4px，不随元素大小变」），
 * 所以这个常量只服务安卓端，别拿去改桌面。
 */
const val HARD_SHADOW_REF_DP = 104f

data class Radii(val base: Int, val lg: Int) {
    val baseDp get() = base.dp
    val lgDp get() = lg.dp
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

/** 经典黑 —— 对应 `styles.css` 的 `:root`。 */
val SkinClassic = SkinColors(
    id = "classic",
    name = "经典黑",
    desc = "深色、紧凑、原汁原味的客户端观感",
    swatch = c(0xEC4141) to c(0x16161A),
    accent = c(0xEC4141),
    accentSoft = Color(0x24EC4141),
    accentHi = c(0xFF5C5C),
    bg0 = c(0x16161A),
    bg1 = c(0x1C1C21),
    bg2 = c(0x232329),
    bg3 = c(0x2B2B32),
    bg4 = c(0x35353D),
    bgHover = Color(0x0EFFFFFF),
    bgActive = Color(0x17FFFFFF),
    text0 = c(0xF2F2F4),
    text1 = c(0xB9B9C2),
    text2 = c(0x85858F),
    text3 = c(0x5F5F69),
    line = Color(0x12FFFFFF),
    line2 = Color(0x1FFFFFFF),
    radius = 8,
    radiusLg = 12,
    dark = true,
    // 经典黑：形态上什么都不加（无边框带、无描边、胶囊按钮），只给当前句歌词
    // 一点白光 —— 深底上白光=「这句在发光」，桌面端也是这三款深色皮才有。
    //
    // 大框框照桌面 `styles.css:700`：红色 16% → 3% 的 120° 渐变 + 18% 的红边。
    // 这是唯一一款写死 `#ec4141` 而不是跟 `--accent` 走的皮肤 —— 桌面那行就是
    // 写死的红色，换颜色反而会和它不一样。
    style = SkinStyle(
        lyricGlow = Color(0x59F2F2F4),
        hero = HeroStyle(
            colors = listOf(Color(0x29EC4141), Color(0x08EC4141)),
            stops = listOf(0f, 0.62f),
            border = Color(0x2EEC4141),
        ),
    ),
)

/**
 * 现代白。注意这里刻意**不**跟随系统深色模式 —— 皮肤是用户显式选的，
 * 系统切了深色不该把一个叫「现代白」的皮肤变黑。
 */
val SkinLight = SkinColors(
    id = "light",
    name = "现代白",
    desc = "白底、细线、平面化的极简界面",
    swatch = c(0x2B63F0) to c(0xFFFFFF),
    accent = c(0x2B63F0),
    accentSoft = Color(0x1A2B63F0),
    accentHi = c(0x1D5BD6),
    bg0 = c(0xFFFFFF),
    bg1 = c(0xF7F8FA),
    bg2 = c(0xFFFFFF),
    bg3 = c(0xF1F2F4),
    bg4 = c(0xEEF0F3),
    bgHover = Color(0x0B111111),
    bgActive = Color(0x13111111),
    text0 = c(0x111114),
    text1 = c(0x4A4D55),
    text2 = c(0x5C5F67),
    text3 = c(0x696D76),
    line = Color(0x14111111),
    line2 = Color(0x26111111),
    radius = 8,
    radiusLg = 12,
    dark = false,
    // 现代白：VIP 标识用这款皮自己的强调蓝实底 + 白字。金色在白底上会发脏
    // （白底上一条金黄色块像陈年贴纸），而蓝色是这一款唯一的「重点」语言。
    //
    // 大框框照桌面：强调蓝 **7% → 1.5%** 的极淡渐变 + 一根 `--line` 发丝边。
    // 关键是「淡」—— 桌面白皮这块几乎是白纸上一层薄雾，重一点整页就脏了。
    style = SkinStyle(
        vip = VipStyle(
            fill = Color(0xFF2B63F0),
            text = Color.White,
            border = null,
            radius = 4,
        ),
        hero = HeroStyle(
            colors = listOf(Color(0x122B63F0), Color(0x042B63F0)),
            stops = listOf(0f, 0.62f),
            border = Color(0x14111111),
        ),
    ),
)

/** 瑞士风：纸白底、黑字、红色细线，全部直角。 */
val SkinSwiss = SkinColors(
    id = "swiss",
    name = "瑞士风",
    desc = "纸白底、黑字、红色细线，国际主义排版",
    swatch = c(0xE93B1A) to c(0xF5F2EB),
    accent = c(0xD63412),
    accentSoft = Color(0x1AD63412),
    accentHi = c(0xB02809),
    bg0 = c(0xF5F2EB),
    bg1 = c(0xEFECE4),
    bg2 = c(0xFBF9F4),
    bg3 = c(0xE9E4D8),
    bg4 = c(0xDED8C9),
    bgHover = Color(0x0D111111),
    bgActive = Color(0x17111111),
    text0 = c(0x111111),
    text1 = c(0x3C3C3C),
    text2 = c(0x555555),
    text3 = c(0x5F5F5F),
    line = Color(0x1A111111),
    line2 = Color(0x38111111),
    radius = 2,
    radiusLg = 2,
    dark = false,
    // 瑞士风：两根红线（顶栏底边、迷你条顶边）+ 红线选中标记 + 矩形按钮 +
    // 平涂 VIP 标签。桌面版这四项写在 skins.css 的 900-1300 行。
    // VIP 那一项是**已经定稿**的一支（用户 m00932 第⑪点点名不动它）：
    // 平涂强调红 + 纸白字 + 直角，不带任何金色装饰。
    style = SkinStyle(
        stripe = 2,
        marker = SkinStyle.Marker.Bar,
        markerWidth = 3,
        button = SkinStyle.Button.Rect,
        vip = VipStyle(
            fill = Color(0xFFD63412),
            text = Color(0xFFF5F2EB),
            border = null,
            radius = 0,
        ),
        // 大框框照桌面 `skins.css:1215`：**去掉色块**，只留纸面 `--bg-3` +
        // 一根发丝线 + 左边 4px 红杠（`box-shadow: inset 4px 0 0 accent`）。
        // 这一款的语言是「线条与留白」，一块带色的底会立刻显得不像瑞士风。
        hero = HeroStyle(
            colors = listOf(Color(0xFFE9E4D8)),
            border = Color(0x1A111111),
            leftBar = Color(0xFFD63412),
        ),
    ),
)

/** 落日余晖：暖褐黑底、夕照橙金。 */
val SkinSunset = SkinColors(
    id = "sunset",
    name = "落日余晖",
    desc = "暖褐黑底、夕照橙金，地平线上一轮落日",
    swatch = c(0xEF8A5C) to c(0x121010),
    accent = c(0xBD5730),
    accentSoft = Color(0x33BD5730),
    accentHi = c(0xEF8A5C),
    bg0 = c(0x121010),
    bg1 = c(0x1B1715),
    bg2 = c(0x241E1A),
    bg3 = c(0x2E2620),
    bg4 = c(0x3A2F26),
    bgHover = Color(0x0EFFFFFF),
    bgActive = Color(0x18FFFFFF),
    text0 = c(0xF2E9DF),
    text1 = c(0xC9B9A9),
    text2 = c(0xAB9B83),
    text3 = c(0x968A7B),
    line = Color(0x14F2E9DF),
    line2 = Color(0x26F2E9DF),
    radius = 8,
    radiusLg = 12,
    dark = true,
    // 落日余晖：没有边框带、没有矩形，只有「发光」——
    // 播放键与卡片播放键周围一圈橙色光晕（桌面版用 box-shadow 做的），
    // 当前句歌词也是（桌面的 `0 0 22px rgba(accent, .55)`），VIP 标识同样带一点，
    // 因为「发光」是这一款唯一的形态语言。
    style = SkinStyle(
        glow = 12,
        lyricGlow = Color(0x8CEF8A5C),
        vip = VipStyle(
            fill = Color(0xFFEF8A5C),
            fillTo = Color(0xFFD9952E),
            text = Color(0xFF2A1608),
            border = null,
            glow = 6,
        ),
        // 大框框照桌面 `skins.css:1416`：「夕照斜打进画面」—— 横向渐变
        // （橙 22% → 4% → 透明）**叠在** `--bg-3` 暖褐底上，加一道 28% 的橙边
        // 和左边 4px 橙杠。桌面是 90°，不是另外两款的 120°。
        hero = HeroStyle(
            colors = listOf(Color(0x38BD5730), Color(0x0ABD5730), Color(0x00000000)),
            base = Color(0xFF2E2620),
            stops = listOf(0f, 0.62f, 1f),
            angleIsDiagonal = false,
            border = Color(0x47BD5730),
            leftBar = Color(0xFFEF8A5C),
        ),
    ),
)

/**
 * 街机风：奶油底、糖果色块、2px 黑描边与硬投影。
 *
 * `accentHi` 在 skins.css 里是 `#111111` 而不是更亮的黄 —— 因为这一款几乎所有
 * 强调色元素都带墨黑描边，「加深」在这里的语义是「更黑」。
 */
val SkinArcade = SkinColors(
    id = "arcade",
    name = "街机风",
    desc = "奶油底、糖果色块、2px 黑描边与硬投影",
    swatch = c(0xF6D467) to c(0xF7F8F3),
    accent = c(0xF6D467),
    accentSoft = Color(0x6BF6D467),
    accentHi = c(0x111111),
    bg0 = c(0xF7F8F3),
    bg1 = c(0xFFFFFF),
    bg2 = c(0xFFFFFF),
    bg3 = c(0xE6E7DE),
    bg4 = c(0xDADCD0),
    bgHover = Color(0x0F111111),
    bgActive = Color(0x1A111111),
    text0 = c(0x111111),
    text1 = c(0x2B2B2B),
    text2 = c(0x444444),
    text3 = c(0x565656),
    line = Color(0x24111111),
    line2 = Color(0x47111111),
    radius = 0,
    radiusLg = 0,
    dark = false,
    stroke = 2,
    hardShadow = 4,
    onAccent = c(0x111111),
    // 街机风：3px 墨黑边框带 + 墨黑左侧杠的选中标记 + 矩形按钮 +
    // 侧栏整体描边 + 正在播放的行换成实心小方块。桌面版在 skins.css 的 1656-2400 行。
    // VIP 标识走这一款自己的语法：糖果黄底 + 墨字 + 墨黑描边 + 2dp 硬投影，
    // 跟卡片、按钮、头像用的是同一套「贴纸」画法（直角、粗描边、硬投影）。
    // 它没有 lyricGlow —— 浅色底上白光只会把墨字糊掉，桌面端这里写的也是 none。
    style = SkinStyle(
        stripe = 3,
        stripeColor = c(0x111111),
        marker = SkinStyle.Marker.Bar,
        markerWidth = 4,
        panelBorder = true,
        button = SkinStyle.Button.Rect,
        squarePlayingDot = true,
        vip = VipStyle(
            fill = c(0xF6D467),
            text = c(0x111111),
            border = c(0x111111),
            borderWidth = 2,
            hardShadow = 2,
            radius = 0,
        ),
        // 大框框照桌面 `skins.css:2086`：**薄荷绿 `#7ee2b7` 平涂**（不是黄色）+
        // 2px 墨黑边 + 4px 硬投影，标题和说明一律墨黑，说明行 72% 不透明。
        // 桌面这里刻意用装饰色而不是强调黄 —— 黄底上压黑字会显得整块在发光，
        // 薄荷色块才是参考图里「一片安静的面」。
        hero = HeroStyle(
            colors = listOf(c(0x7EE2B7)),
            border = c(0x111111),
            borderWidth = 2,
            hardShadow = 4,
            titleColor = c(0x111111),
            bodyColor = c(0x111111),
            bodyAlpha = 0.72f,
        ),
    ),
)

val SKINS = listOf(SkinClassic, SkinLight, SkinSwiss, SkinSunset, SkinArcade)

fun skinById(id: String?): SkinColors = SKINS.firstOrNull { it.id == id } ?: SkinClassic

/**
 * 当前皮肤。用 `staticCompositionLocalOf` 而不是普通的 `compositionLocalOf`：
 * 换肤是罕见事件，按皮肤读取的每处 lambda 都参与重组是纯浪费 ——
 * static 版本会整棵子树重组，对一次换肤来说正是我们要的。
 */
val LocalSkin = staticCompositionLocalOf { SkinClassic }

/** 当前皮肤，给 `SkinColors.text0` 这种取值用。 */
val skin: SkinColors
    @Composable @ReadOnlyComposable get() = LocalSkin.current

@Composable
fun NeteaseLiteTheme(
    skinId: String,
    content: @Composable () -> Unit,
) {
    val s = skinById(skinId)
    // Material 的 colorScheme 只兜底我们自己没画的地方（对话框、文本选择柄、
    // 涟漪）。真正的界面颜色一律从 LocalSkin 取，所以这里的映射粗糙一点没关系。
    val scheme = if (s.dark) {
        darkColorScheme(
            primary = s.accent,
            onPrimary = s.onAccent,
            background = s.bg0,
            onBackground = s.text0,
            surface = s.bg1,
            onSurface = s.text0,
            surfaceVariant = s.bg2,
            onSurfaceVariant = s.text1,
            outline = s.line2,
            error = Color(0xFFF28B84),
        )
    } else {
        lightColorScheme(
            primary = s.accent,
            onPrimary = s.onAccent,
            background = s.bg0,
            onBackground = s.text0,
            surface = s.bg1,
            onSurface = s.text0,
            surfaceVariant = s.bg2,
            onSurfaceVariant = s.text1,
            outline = s.line2,
            error = Color(0xFFB3261E),
        )
    }
    CompositionLocalProvider(LocalSkin provides s) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * 字号沿用桌面版的层级（标题 22、区块标题 19、正文 13、次要 12.5、说明 11）。
 * 不直接用 Material 默认排版：它的 `titleLarge` 是 22sp 但行高与字重都不同，
 * 逐处覆盖比重定义更啰嗦。
 */
private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp),
    labelSmall = TextStyle(fontSize = 10.sp),
)
