package com.netease.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netease.lite.AppViewModel
import com.netease.lite.model.Song
import com.netease.lite.play.Playback

/**
 * 播放队列页（桌面版是底栏右侧的队列面板）。
 *
 * 桌面版那个面板可以在「播放队列 / 下载」之间切页；Android 没有下载队列，
 * 所以这里只有队列本身，另外把循环模式的切换也放在页头 —— 桌面版的循环按钮
 * 在底栏，手机底栏只有三颗键，循环在「正在播放」页已经是文字按钮，这里再给一个
 * 是因为「要清空队列」和「要切随机」常常是同一个念头（重新开始听一批歌）。
 *
 * 列表直接读 [com.netease.lite.play.PlayerController.queue] 这个 State：
 * 加歌、删歌、切随机都会让这一页自己重画，不需要额外的事件订阅。
 */
@Composable
fun QueuePage(vm: AppViewModel) {
    val s = skin
    val songs = vm.player.queue
    val playing = vm.player.queueIndex

    BackHandler(enabled = true) { vm.goBack() }

    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PageTitle("播放队列", "共 ${songs.size} 首 · ${Playback.repeatLabel(vm.player.repeatMode)}")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(Playback.repeatLabel(vm.player.repeatMode), primary = true) { vm.cycleRepeat() }
            Spacer(Modifier.width(10.dp))
            if (songs.isNotEmpty()) {
                PillButton("清空") { confirmClear = true }
            }
        }
        if (songs.isEmpty()) {
            EmptyState("播放队列是空的", "到任意一个列表里点一首歌，队列就会出现在这里。")
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(songs.size) { i ->
                    QueueRow(
                        song = songs[i],
                        index = i,
                        isCurrent = i == playing,
                        onPick = { vm.player.playAt(i) },
                        onRemove = { vm.player.removeQueueItem(i) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空播放队列？") },
            text = { Text("队列清空后当前这首歌也会停下，之后可以重新从任意列表开始听。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.player.clearQueue()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = skin
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(s.radii.baseDp))
            .background(if (isCurrent && s.style.marker == SkinStyle.Marker.Fill) s.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            // 队列行在街机风里是一张张小卡片：桌面版 skins.css 的「描边 A 组」
            // 明确包含 .qp-item（2px 墨黑）。hairline=false 让其余四款完全
            // 不加边框 —— 桌面上那四款的 .qp-item 本来就没有描边。
            .skinBorder(s.radii.baseDp, hairline = false)
            // 与 SongRow / SideItem 同一套选中语义：瑞士风与街机风只在左侧画一道杠。
            .drawBehind {
                if (isCurrent && s.style.marker == SkinStyle.Marker.Bar) {
                    val w = s.style.markerWidth.dp.toPx()
                    drawRect(
                        color = if (s.id == "arcade") s.text0 else s.accent,
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                    )
                }
            }
            .clickable(onClick = onPick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
            if (isCurrent) {
                // 与 SongRow 的「现在」标记同一套语义：街机风画实心小方块，
                // 其余四款画音符。桌面上 .tl-eq i 与 .qp-item.on 就是这么分的。
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
        Cover(song.cover, 38.dp, s.radii.baseDp.coerceAtMost(8.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                color = if (isCurrent) s.accent else s.text0,
                fontSize = 13.5.sp,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                song.artistText,
                color = s.text3,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(Icons.Close, s.text3, onRemove, size = 18.dp)
    }
}
