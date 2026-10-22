package com.netease.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netease.lite.AppViewModel
import com.netease.lite.Route
import com.netease.lite.model.Comment

/**
 * 评论页。
 *
 * 桌面版把评论挂在歌单页底部与歌曲右键菜单里（`CommentList`）；Android 侧抽成
 * 一个整页 —— 手机屏幕放不下一列歌加一屏评论，分成两页各自都能读。
 *
 * 数据只有「热评 + 最新一页」，不翻页：手机上一页 30 条已经要滑三屏，
 * 翻页按钮放到底部没人会滑那么远。要看更多去桌面版。
 */
@Composable
fun CommentsPage(vm: AppViewModel, type: String, id: Long, name: String) {
    BackHandler(enabled = true) { vm.goBack() }
    val total = vm.commentsTotal
    PageShell(
        vm,
        vm.commentsHot.isEmpty() && vm.commentsNew.isEmpty(),
        "还没有评论",
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item {
                PageTitle("评论", "${name.ifEmpty { "评论" }} · 共 $total 条")
            }
            if (vm.commentsHot.isNotEmpty()) {
                item { CommentGroupTitle("精彩评论", vm.commentsHot.size) }
                items(vm.commentsHot.size) { i ->
                    val c = vm.commentsHot[i]
                    CommentRow(c, onClickUser = {
                        if (c.userId > 0) vm.go(Route.User(c.userId, c.nickname))
                    }, onLike = { want -> vm.likeComment(c, want, hot = true) })
                }
            }
            item { CommentGroupTitle("最新评论", vm.commentsNew.size) }
            items(vm.commentsNew.size) { i ->
                val c = vm.commentsNew[i]
                CommentRow(c, onClickUser = {
                    if (c.userId > 0) vm.go(Route.User(c.userId, c.nickname))
                }, onLike = { want -> vm.likeComment(c, want, hot = false) })
            }
        }
    }
}

@Composable
private fun CommentGroupTitle(text: String, n: Int) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 街机风与瑞士风的标题前面有一条短色带，其余三款没有（与桌面版同一个信号）。
        if (s.style.stripe > 0) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp)
                    .background(s.style.stripeColor ?: s.accent),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = s.text0, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("$n", color = s.text3, fontSize = 11.5.sp)
    }
}

/**
 * 一条评论：头像 + 昵称 + 时间 + 正文 + 赞。
 *
 * 正文不截断行数 —— 评论的价值就在完整那句话，截成两行再加个「展开」反而更啰嗦。
 */
@Composable
private fun CommentRow(c: Comment, onClickUser: () -> Unit, onLike: (Boolean) -> Unit) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Avatar(c.avatar, 34.dp, modifier = Modifier.clickable(onClick = onClickUser))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.nickname,
                    color = s.text2,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(onClick = onClickUser),
                )
                Spacer(Modifier.width(8.dp))
                Text(fmtCommentTime(c.time), color = s.text3, fontSize = 10.5.sp)
            }
            Spacer(Modifier.height(5.dp))
            Text(c.content, color = s.text0, fontSize = 13.5.sp, lineHeight = 19.sp)
            // 被引用的那条评论（第⑬点）。桌面版把它画成一块带底色的引用框，
            // 内容是「@昵称：原话」。用浅底 + 左侧一道细杠，不用引用符号 ——
            // 手机上「」之类在中文正文里很容易和原文混在一起。
            val q = c.quoted
            if (q != null) {
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(s.bg2)
                        .padding(vertical = 7.dp, horizontal = 9.dp),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(28.dp)
                            .background(s.line),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            if (q.nickname.isNotEmpty()) append("@${q.nickname}：")
                            append(q.content)
                        },
                        color = s.text2,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onLike(!c.liked) }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.HeartFilled,
                            contentDescription = if (c.liked) "取消点赞" else "点赞",
                            tint = if (c.liked) s.accent else s.text3,
                            modifier = Modifier.width(13.dp).height(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (c.likedCount > 0) "${c.likedCount}" else "赞",
                            color = if (c.liked) s.accent else s.text3,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/** 评论时间：今天给 `hh:mm`，昨天给「昨天」，更早给 `M月d日`。 */
private fun fmtCommentTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val diff = now - ms
    return when {
        diff < day -> {
            val d = java.util.Date(ms)
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            f.format(d)
        }
        diff < 2 * day -> "昨天"
        else -> {
            val f = java.text.SimpleDateFormat("M月d日", java.util.Locale.getDefault())
            f.format(java.util.Date(ms))
        }
    }
}
