package com.netease.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netease.lite.AppViewModel
import com.netease.lite.Route

/**
 * 听歌等级详情页，对应桌面版 `views.userlevel`。
 *
 * 桌面版把「Lv.N」做成用户页里一个可点的数字，点进来是这块：大等级数 + 进度条
 * + 三行读数（本周已听 / 本周已登录 / 累计听歌）。Android 侧沿同一条路径，
 * 数据来自 [com.netease.lite.api.Endpoints.userLevel]（`fetchUser` 已顺带取回）。
 */
@Composable
fun UserLevelPage(vm: AppViewModel, uid: Long, name: String) {
    BackHandler(enabled = true) { vm.goBack() }
    val s = skin
    val lv = vm.userLevel
    val u = vm.userDetail?.takeIf { it.id == uid }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("${name.ifEmpty { u?.nickname ?: "我" }} 的等级")

        val level = (lv?.get("level") as? Int ?: 0).takeIf { it > 0 } ?: (u?.level ?: 0)
        val info = lv?.get("info") as? String ?: ""
        val progress = (lv?.get("progress") as? Double ?: 0.0).coerceIn(0.0, 1.0)

        // 大数字 + 说明，与桌面版 `.daily-head` 的「日期块 + 文案」同构：
        // 左边一块色底大字给等级，右边两行解释。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(s.radii.lgDp))
                .background(s.accentSoft)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(64.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(s.radii.baseDp))
                    .background(s.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (level > 0) "$level" else "-",
                    color = s.onAccent,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (level > 0) "听歌等级 Lv.$level" else "还没有等级数据",
                    color = s.text0,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    info.ifEmpty { "听歌越多，等级越高" },
                    color = s.text2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        // 进度条。满级时 next 全是 0，此时不给进度（画一条满格像是在说「还差一点」）。
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(s.bg3),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.toFloat())
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(s.accent),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "距下一级 ${"%.0f".format(progress * 100)}%",
            color = s.text3,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(16.dp))
        val nowPlay = lv?.get("nowPlayCount") as? Int ?: 0
        val nextPlay = lv?.get("nextPlayCount") as? Int ?: 0
        val nowLogin = lv?.get("nowLoginCount") as? Int ?: 0
        val nextLogin = lv?.get("nextLoginCount") as? Int ?: 0
        // 满级时 next 是 0，写「0 / 0 首」读起来像 bug，只给一个数。
        fun pair(now: Int, next: Int, unit: String) =
            if (next > 0) "$now / $next $unit" else "$now $unit"

        LevelRow("本周已听", pair(nowPlay, nextPlay, "首"))
        LevelRow("本周已登录", pair(nowLogin, nextLogin, "天"))
        LevelRow("累计听歌", if ((u?.listenSongs ?: 0) > 0) fmtCount(u!!.listenSongs.toLong()) + " 首" else "-")

        Spacer(Modifier.height(18.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            PillButton("TA 的歌单") { vm.go(Route.User(uid, name)) }
            Spacer(Modifier.width(10.dp))
            PillButton("回到顶部") { vm.goBack() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LevelRow(label: String, value: String) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = s.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = s.text0, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
