package com.netease.lite.play

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.netease.lite.R

/**
 * 固定五颗按钮的通知栏 provider。
 *
 * ## 一、为什么不能直接用默认的 [DefaultMediaNotificationProvider]
 *
 * 用户 m48583 第⑤点：「当播放列表只有一首歌时，通知栏卡片在进行循环模式切换时，
 * 按钮会出现错位」。
 *
 * 默认实现会按 `Player.Commands` **过滤**按钮：队列只剩一首歌、循环模式又切到
 * 「顺序播放」（`REPEAT_MODE_OFF`）时，`hasNextMediaItem()` 变 false，
 * `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` 从可用命令里消失，于是「下一首」那颗按钮
 * 被整个拿掉。通知栏剩下的按钮往左补位 —— 用户看到的就是「切一下循环，
 * 按钮全跳了一格」。
 *
 * 这台机器上实测的顺序变化（单曲队列切三次循环）：
 *
 * ```
 * 列表循环: 上一首 | 播放 | 下一首 | 喜欢 | 列表循环
 * 随机播放: 上一首 | 播放 | 下一首 | 喜欢 | 随机播放
 * 顺序播放: 上一首 | 播放 |        喜欢 | 顺序播放     ← 下一首消失了
 * ```
 *
 * 修法：覆写 [getMediaButtons]，**不看** `playerCommands`，永远返回同一份五颗。
 * 按钮位置从此与播放状态无关，循环档位只影响最后一颗的**图标**（不换槽位），
 * 也就不会再错位。
 *
 * ## 二、顺序与大小（用户 m00932 第⑥点）
 *
 * ① 「始终从左到右显示红心、上一首、播放/暂停、下一首、循环模式」：
 *    返回列表的**顺序**就是展开后通知里的显示顺序，所以把 heart 放到第一位、
 *    repeat 放到最后。
 *
 * ② 「把红心和循环模式两个按钮尺寸缩小一点」：图标用的是本工程
 *    `res/drawable/ic_notif_*.xml`，图形在 24×24 viewport 里按 0.72 居中缩小
 *    （`<group android:scaleX="0.72" ...>`）。槽位是系统给的 24dp，改不动，
 *    所以只能缩图形本身。
 *
 * ③ 折叠态（只显示三颗的那个小卡片）不受列表顺序影响：Media3 的
 *    [DefaultMediaNotificationProvider] 是按**槽位**挑紧凑视图的三颗
 *    （`SLOT_BACK` → 第 0 位、`SLOT_CENTRAL` → 第 1 位、`SLOT_FORWARD` → 第 2 位），
 *    红心与循环都是 `SLOT_OVERFLOW`，不会挤进去。
 *
 * 「下一首」在队列末尾点时不会切歌（Media3 自己会忽略），但按钮留在原位 ——
 * 这比「按钮时有时无」更符合直觉：用户的手不用重新找位置。
 */
@UnstableApi
class FixedNotificationProvider(context: Context) : DefaultMediaNotificationProvider(context) {

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val playing = session.player.isPlaying
        val liked = SessionUiState.liked

        // 坑（实测踩过，通知栏会整条不出现）：`CommandButton.Builder(int)` 的参数是
        // 图标**常量**（ICON_*，内部走 `getIconResIdForIconConstant` 的 lookupswitch），
        // 不是 drawable 资源 id —— 传 R.drawable.* 会落到 default 分支得到无效图标，
        // Media3 随后静默丢弃整条通知（logcat 无异常、dumpsys 无记录）。
        // 要用自定义 drawable 只有 `setCustomIconResId`（写的是另一个字段，
        // 优先级高于常量对应的内置图标）。
        val heart = CommandButton.Builder(
            if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        )
            .setCustomIconResId(
                if (liked) R.drawable.ic_notif_heart_filled else R.drawable.ic_notif_heart_outline,
            )
            .setSessionCommand(SessionCommand(PlaybackService.CMD_TOGGLE_LIKE, Bundle.EMPTY))
            .setDisplayName(if (liked) "取消喜欢" else "喜欢")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val prev = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName("上一首")
            .setSlots(CommandButton.SLOT_BACK)
            .build()

        // 图标跟着 isPlaying 走，但槽位永远是 SLOT_CENTRAL。
        val playPause = CommandButton.Builder(
            if (playing) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
        )
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(if (playing) "暂停" else "播放")
            .setSlots(CommandButton.SLOT_CENTRAL)
            .build()

        val next = CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName("下一首")
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()

        // 循环档只有三种（用户 m00001 第④点）。`normalize` 先折一次，
        // 免得老存档里的 "list" 在这里画出第四种图标。
        val mode = Playback.normalize(SessionUiState.repeatMode)
        val repeat = CommandButton.Builder(
            when (mode) {
                Playback.SHUFFLE -> CommandButton.ICON_SHUFFLE_ON
                Playback.REPEAT_ONE -> CommandButton.ICON_REPEAT_ONE
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setCustomIconResId(
                when (mode) {
                    Playback.SHUFFLE -> R.drawable.ic_notif_shuffle
                    Playback.REPEAT_ONE -> R.drawable.ic_notif_repeat_one
                    else -> R.drawable.ic_notif_repeat_off
                },
            )
            .setSessionCommand(SessionCommand(PlaybackService.CMD_CYCLE_REPEAT, Bundle.EMPTY))
            .setDisplayName(Playback.repeatLabel(mode))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        // 顺序 = 展开后从左到右的顺序（用户 m00932 第⑥点）。
        return ImmutableList.of(heart, prev, playPause, next, repeat)
    }
}
