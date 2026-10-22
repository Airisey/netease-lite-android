package com.netease.lite

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.netease.lite.ui.AppShell
import com.netease.lite.ui.NeteaseLiteTheme
import com.netease.lite.ui.skin
import kotlinx.coroutines.delay

/**
 * 唯一的 Activity。
 *
 * 界面靠 Compose 搭，播放靠 [com.netease.lite.play.PlaybackService] 的前台服务 ——
 * Activity 负责的只有三件事：把主题套上、把 MediaController 连上服务、给播放器
 * 一个心跳去刷新进度。列表/路由/请求都在 [AppViewModel] 里，Activity 退出重建不会
 * 丢状态。
 */
class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    // Android 13 起通知要用户点头，没这一步前台服务的媒体通知是空白的。
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全面屏（第①点）：内容要铺到状态栏/导航栏底下，靠 Compose 侧的 inset
        // padding 把可点区域让开。不调这个的话，Android 15 起系统默认强制
        // edge-to-edge，主题里的 transparent 状态栏会让顶栏被状态栏压住一半。
        enableEdgeToEdge()
        maybeAskNotification()

        setContent {
            NeteaseLiteTheme(vm.skin) {
                // 状态栏/导航栏的图标颜色跟着皮肤走：深色皮肤要浅色图标，
                // 浅色皮肤（现代白、街机风）要深色图标。enableEdgeToEdge() 的
                // auto 只按系统深色模式判断，不看应用自己选了什么皮肤 ——
                // 在浅色皮肤 + 系统深色模式下，状态栏图标会是白的、压在米白底上，
                // 一个字都看不见。
                SystemBarIcons(dark = skin.dark)
                LaunchedEffect(Unit) {
                    // 连服务放在这里而不是 Application：MediaController 要用 Activity 的
                    // Context 拿 SessionToken，而且连上之后界面才谈得上操作。
                    vm.player.connect(applicationContext)
                    // 恢复上次播放：只摆好队列停在那个位置，不自动出声。
                    vm.restoreLastPlay()
                }
                // 心跳：进度条与迷你播放条的时间靠它走。500ms 一次，
                // 比 ExoPlayer 自己的 progress 回调省电，肉眼看也是连续的。
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(500)
                        vm.player.tick()
                    }
                }
                AppShell(vm)
            }
        }
    }

    private fun maybeAskNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * 按皮肤深浅决定状态栏/导航栏图标是深色还是浅色。
 *
 * 只在主题切换时跑一次就够 —— [dark] 变了才会重新进入这个 Composable。
 * API 30 之前没有官方 API，退化成「不处理」（那时状态栏图标颜色由主题的
 * `windowLightStatusBar` 决定，老系统上本来也没有全面屏的强约束）。
 */
@Composable
private fun SystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as Activity).window
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
