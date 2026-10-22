package com.netease.lite.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 图标表。
 *
 * 桌面版用内联 SVG（`renderer/js/views.js:7-41` 的 `ICON`）；Compose 用
 * [ImageVector]。这里不是「照着重画一遍」而是**逐个把原 path 抄过来**：
 * 两端的图标形状必须一模一样，否则同一个「红心」在桌面和手机上长得不同，
 * 用户会以为点错了地方。
 *
 * 颜色一律留空（`SolidColor(Color.Black)` 填充 + 由 `Icon(tint=)` 上色），
 * 这样五款皮肤只需要在调用点给 tint，不必为每款皮肤再建一套图标。
 *
 * 描边类图标（[HeartOutline] / [Eye]）在 SVG 里写的是 `fill="none"
 * stroke="currentColor"`，Compsoe 里对应 `stroke = SolidColor(...)`。
 */
object Icons {

    private fun icon(
        name: String,
        pathData: String,
        stroke: Boolean = false,
        strokeWidth: Float = 1.7f,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        val nodes = PathParser().parsePathString(pathData).toNodes()
        builder.addPath(
            pathData = nodes,
            fill = if (stroke) null else SolidColor(Color.Black),
            stroke = if (stroke) SolidColor(Color.Black) else null,
            strokeLineWidth = strokeWidth,
        )
        return builder.build()
    }

    val Play = icon("play", "M7 4l13 8-13 8z")
    val Add = icon("add", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")

    /** 取消收藏用减号，跟收藏的加号成对。 */
    val Minus = icon("minus", "M5 11h14v2H5z")

    val HeartFilled = icon("heart", "M12 21s-8-5.1-8-11a4.6 4.6 0 018-3.1A4.6 4.6 0 0120 10c0 5.9-8 11-8 11z")
    val HeartOutline = icon(
        "heartOutline",
        "M12 19.5C9.7 18 5.5 14.6 5.5 10.4A3.9 3.9 0 0112 7.7a3.9 3.9 0 016.5 2.7c0 4.2-4.2 7.6-6.5 9.1z",
        stroke = true,
    )

    val Download = icon("download", "M11 3h2v9h3l-4 4-4-4h3zM5 18h14v2H5z")
    val Next = icon("next", "M15 6h2v12h-2zM5 18V6l9 6z")
    val Comment = icon("comment", "M4 4h16v12H8l-4 4z")
    val Album = icon("album", "M12 2a10 10 0 100 20 10 10 0 000-20zm0 13a3 3 0 110-6 3 3 0 010 6z")
    val Artist = icon("artist", "M12 12a5 5 0 100-10 5 5 0 000 10zm0 2c-5 0-9 2.7-9 6v2h18v-2c0-3.3-4-6-9-6z")
    val Copy = icon("copy", "M8 3h9v2H8zm-3 3h12v15H5zm2 2v11h8V8z")
    val Trash = icon("trash", "M9 3h6v2H9zM4 6h16v2H4zm2 3h12v12H6zm3 2v8h2V11zm4 0v8h2V11z")
    val Radio = icon("radio", "M3 6h18v3H3zm0 5h18v9H3zm3 3v3h5v-3z")

    /** 与底栏随机播放按钮同一套双箭头交叉图形，保证两处图标一致。 */
    val Shuffle = icon(
        "shuffle",
        "M17 3l4 4-4 4V8h-2.2l-2.3 3-1.3-1.7L13.4 6H17V3zM3 6h4.3l2.5 3.3-1.3 1.7L6.6 8H3V6zm14 8v-3l4 4-4 4v-3h-3.6l-2.2-3 1.3-1.7L14.8 14H17zM3 16h3.6l2.2-3 1.3 1.7-2.5 3.3H3v-2z",
    )

    val Chart = icon("chart", "M4 20h3V10H4zm6.5 0h3V4h-3zM17 20h3v-6h-3z")
    val Podcast = icon(
        "podcast",
        "M12 2a10 10 0 00-4 19.2V19a8 8 0 1110 0v2.2A10 10 0 0012 2zm0 5a5 5 0 00-2 9.6V21a1 1 0 002 0v-4.4A5 5 0 0012 7zm0 3a2 2 0 100 4 2 2 0 000-4z",
    )
    val Palette = icon(
        "palette",
        "M12 3a9 9 0 000 18c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.2 0-1.1.9-2 2-2h2.3A4.7 4.7 0 0022 9.8C22 6 17.5 3 12 3zM7.5 12a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm3-4a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm5 0a1.5 1.5 0 110-3 1.5 1.5 0 010 3zm3 4a1.5 1.5 0 110-3 1.5 1.5 0 010 3z",
    )
    val Gear = icon(
        "gear",
        "M12 8a4 4 0 100 8 4 4 0 000-8zm0 6a2 2 0 110-4 2 2 0 010 4zm8.9-2c0-.5-.05-1-.14-1.47l2.02-1.5-2-3.46-2.35.94a7.9 7.9 0 00-2.55-1.48L15.5 2h-4l-.38 2.53a7.9 7.9 0 00-2.55 1.48l-2.35-.94-2 3.46 2.02 1.5a7.9 7.9 0 000 2.94l-2.02 1.5 2 3.46 2.35-.94a7.9 7.9 0 002.55 1.48L11.5 22h4l.38-2.53a7.9 7.9 0 002.55-1.48l2.35.94 2-3.46-2.02-1.5c.09-.47.14-.97.14-1.47z",
    )
    val MiniV = icon(
        "miniV",
        "M7 3h10a4 4 0 014 4v10a4 4 0 01-4 4H7a4 4 0 01-4-4V7a4 4 0 014-4zm0 2a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2H7zm3 3h4a2 2 0 012 2v3a2 2 0 01-2 2h-4a2 2 0 01-2-2V10a2 2 0 012-2z",
    )
    val MiniH = icon(
        "miniH",
        "M3 7a4 4 0 014-4h10a4 4 0 014 4v10a4 4 0 01-4 4H7a4 4 0 01-4-4V7zm2 0v10a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2zm3 3h4a2 2 0 012 2v1a2 2 0 01-2 2H8a2 2 0 01-2-2v-1a2 2 0 012-2z",
    )
    val Check = icon("check", "M9.6 16.2L5.4 12l-1.4 1.4 5.6 5.6L20.4 8.2 19 6.8z")
    val Slider = icon(
        "slider",
        "M4 11h9v2H4zm13-6h3v2h-3zM4 5h9v2H4zm13 12h3v2h-3zM4 17h9v2H4zm11-8a2 2 0 11-4 0 2 2 0 014 0zm0 12a2 2 0 11-4 0 2 2 0 014 0z",
    )
    val Layers = icon("layers", "M12 3l9 5-9 5-9-5zm0 2.3L6.4 8 12 10.7 17.6 8zM3 13l9 5 9-5-1.8-1-7.2 4-7.2-4z")
    val Info = icon("info", "M12 2a10 10 0 100 20 10 10 0 000-20zm1 15h-2v-6h2zm0-8h-2V7h2z")
    val Eye = icon(
        "eye",
        "M2.5 12S6 6.5 12 6.5 21.5 12 21.5 12 18 17.5 12 17.5 2.5 12 2.5 12zm9.5 3a3 3 0 110-6 3 3 0 010 6z",
        stroke = true,
        strokeWidth = 1.8f,
    )
    val Grip = icon("grip", "M9 6h2v2H9zm4 0h2v2h-2zM9 11h2v2H9zm4 0h2v2h-2zM9 16h2v2H9zm4 0h2v2h-2z")
    val Folder = icon("folder", "M3 5h6l2 2h10v12H3zm2 4v8h14V9z")
    val CheckAll = icon("checkAll", "M3 5h6l2 2h10v12H3zm2 4v8h14V9zM5.5 12l1.4-1.4 2.1 2.1 4.6-4.6L15 9.5l-6 6z")

    // ---------------------------------------------------- 系统常见的那些
    // 这几个在 Material 官方图标集里都有，但引 material-icons-core 要多一个依赖，
    // 而我们本来就有一张 SV G 表要维护 —— 顺手抄进来，省得为了五个图标多一个包。
    // （路径取自 Google Material Icons 的标准 24dp 网格，Apache-2.0。）
    val More = icon("more", "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z")
    val Search = icon("search", "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z")
    val Close = icon("close", "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    val ArrowBack = icon("arrowBack", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    // 「正在播放」页用它收起：那是从下往上升起来的一页，向下的箭头比向左的返回箭头
    // 更贴切（路径是 Material 的 expand_more）。
    val ChevronDown = icon("chevronDown", "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6z")

    // 汉堡：桌面版没有这个图标（它的侧栏永远摊在左边），手机端要靠它把抽屉叫出来。
    val Menu = icon("menu", "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z")
    val Pause = icon("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Previous = icon("previous", "M6 6h2v12H6zm3.5 6l8.5 6V6z")
    val Queue = icon("queue", "M3 10h11v2H3zm0-4h11v2H3zm0 8h7v2H3zm13-1v8l6-4z")
    // 「最近播放」原来错用了 Comment（对话气泡）—— 侧栏里那个气泡图标怎么看都是
    // 「评论」，与桌面版的时钟箭头不是一回事。路径是 Material 的 history。
    val History = icon("history", "M13 3a9 9 0 00-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 117 7 6.9 6.9 0 01-4.9-2.05l-1.42 1.42A9 9 0 1013 3zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8z")
    val Refresh = icon("refresh", "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z")
    val Delete = icon("delete", "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z")

    // ---------------------------------------------------- 播放模式三态（第⑧点）
    // 用户要求「播放模式由顺序、随机、循环三个模式的图标组合而成」，即这一颗按钮
    // 要按当前模式换图标，而不是一直显示「列表循环」四个字。
    // 三个图形各取 Material 的标准 24dp 路径，与桌面版 `ICON.repeat*` 同源。

    /**
     * 顺序播放：一遍放完就停（`Player.REPEAT_MODE_OFF`）—— 循环箭头被划掉。
     *
     * **必须与 [RepeatList] 长得不一样**。这两个常量原来是同一份路径（复制粘贴
     * 时只改了常量名），于是「顺序播放」和「列表循环」在界面上是同一个图形，
     * 用户点着这颗按钮看不出自己切到了哪一档 —— 而 m45788 第⑧点要的正是
     * 「顺序、随机、循环三个模式的图标」。划线之后三态才真的三态。
     *
     * 「带斜杠的循环箭头」用的也是桌面版当初那个 `#iconRepeatOff` 的形态
     * （`app.js` 的 `syncRepeatUI` 注释里提到过它，后来因为随机模式误点亮被删掉；
     * 移动端三档同钮，它才是顺序播放该有的样子）。
     */
    val RepeatOff = icon(
        "repeatOff",
        "M2 5.27 3.28 4 20 20.72 18.73 22l-3-3H7v3l-4-4 4-4v3h6.73L7 10.27V11H5V8.27zM17 13h2v4.18l-2-2zm0-8V2l4 4-4 4V7H8.82l-2-2z",
    )

    /** 列表循环：放完从头再来（`Player.REPEAT_MODE_ALL`）。 */
    val RepeatList = icon("repeatList", "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z")

    /**
     * 单曲循环：同一首反复播（`Player.REPEAT_MODE_ONE`）—— 循环箭头里一个「1」。
     *
     * 用户 m03577 第⑥点：「单曲循环模式图标错误，且单曲循环模式不起作用」。
     * 图标错是因为上一轮砍档时把这一档整个删了，只剩顺序 / 列表循环 / 随机，
     * 用户看到的「第四档」其实是列表循环的样子。路径是 Material 的 repeat_one，
     * 与桌面版 `#iconRepeatOne` 同源。
     */
    val RepeatOne = icon(
        "repeatOne",
        "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z",
    )

    /** 改名（第⑫点「编辑名称」）。 */
    val Edit = icon("edit", "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 000-1.41l-2.34-2.34a1 1 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z")

    /** 移动到别的歌单（第⑪点）。 */
    val Move = icon("move", "M20 6h-8l-2-2H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2zm-3 9h-3v3h-2v-3H9v-2h3V9h2v3h3v2z")

    /** 歌手简介（第⑭点）。 */
    val Bio = icon("bio", "M4 4h16v2H4zm0 4h16v2H4zm0 4h10v2H4zm0 4h10v2H4zm14.5-2.5a2.5 2.5 0 100-5 2.5 2.5 0 000 5zM20 20h-2v-1.2c0-1-.8-1.8-1.8-1.8h-1.4c-1 0-1.8.8-1.8 1.8V20H11v-1.2c0-2.1 1.7-3.8 3.8-3.8h1.4c2.1 0 3.8 1.7 3.8 3.8V20z")

    /** 关注歌手 / 关注用户（第⑭点）。 */
    val PersonAdd = icon("personAdd", "M15 12a4 4 0 100-8 4 4 0 000 8zm0 2c-3.3 0-6 1.8-6 4v2h12v-2c0-2.2-2.7-4-6-4zM6 10V7H4v3H1v2h3v3h2v-3h3v-2H6z")
}
