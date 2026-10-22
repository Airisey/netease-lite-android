// 下载收尾算术的验证程序（跑在 JVM 上，不需要 Android 设备/网络）。
//
// 编译方式见 `_probe/run-download-math.ps1`：把本文件和真源码
// `app/src/main/java/com/netease/lite/data/DownloadMath.kt` 一起交给 Kotlin 编译器，
// 所以下面断言的就是**应用里跑的那份算术**，不是复述。
//
// 数字全部来自用户 m04384 报的三首歌（探针 `_probe/probe-cdn-45.mjs` 量到的
// CDN 真实长度）：三首歌的 `Y - X` 都恰好是 45 = 源流开头那段被丢掉的 CDN ID3。
package com.netease.lite.data

private var failed = 0

private fun check(name: String, got: Any?, want: Any?) {
    // 比字符串而不是比对象：Long 与 Int 装箱后 == 永远 false
    val ok = got?.toString() == want?.toString()
    if (!ok) failed++
    println("${if (ok) "PASS" else "FAIL"}  $name  got=$got want=$want")
}

private fun checkTrue(name: String, cond: Boolean) {
    if (!cond) failed++
    println("${if (cond) "PASS" else "FAIL"}  $name")
}

fun main() {
    // ---- 用户报的三首歌：老账本 + 416 的重试路径 ----------------------------
    // マリーゴールド：源流 12326445，ID3 45，音频 12326400
    check("マリーゴールド/掰账本", normalizeStoredTotal(45, 12326445, 12326400), 12326400)
    check("マリーゴールド/应得字节", downloadNeedBytes(12326445, 45, 12326445), 12326400)
    checkTrue("マリーゴールド/判为完成", downloadComplete(12326400, downloadNeedBytes(12326445, 45, 12326445)))
    // インスタントブルー：源流 12309987，音频 12309942
    check("インスタントブルー/掰账本", normalizeStoredTotal(45, 12309987, 12309942), 12309942)
    checkTrue("インスタントブルー/判为完成", downloadComplete(12309942, downloadNeedBytes(12309987, 45, 12309987)))
    // 誰そ彼：源流 10515885，音频 10515840
    check("誰そ彼/掰账本", normalizeStoredTotal(45, 10515885, 10515840), 10515840)
    checkTrue("誰そ彼/判为完成", downloadComplete(10515840, downloadNeedBytes(10515885, 45, 10515885)))

    // ---- 真缺口不能被放过（回归保护）------------------------------------
    checkTrue("真缺 1200 万字节 → 不完整", !downloadComplete(1000000, downloadNeedBytes(12326445, 45, 12326400)))
    checkTrue("缺口不是 skip → 不掰账本", normalizeStoredTotal(45, 12326445, 1000000) == 12326445L)
    checkTrue("空壳 .part → 不完整", !downloadComplete(0, downloadNeedBytes(12326445, 45, 12326445)))

    // ---- 源流没有 ID3（skip=0）与拿不到远端总长 --------------------------
    check("无 ID3/应得字节", downloadNeedBytes(4885986, 0, 4885986), 4885986)
    checkTrue("无 ID3/整首完成", downloadComplete(4885986, downloadNeedBytes(4885986, 0, 4885986)))
    check("远端总长未知/退回账本", downloadNeedBytes(null, 45, 12326400), 12326400)
    checkTrue("远端总长未知/整首完成", downloadComplete(12326400, downloadNeedBytes(null, 45, 12326400)))
    checkTrue("远端总长未知/半截不完整", !downloadComplete(500000, downloadNeedBytes(null, 45, 12326400)))
    check("总长完全未知/不判长度", downloadNeedBytes(null, 0, 0), 0)

    // ---- 416 分支要求「长度可知」才认完成 --------------------------------
    checkTrue("长度未知时 416 不当完成", !(downloadNeedBytes(null, 45, 0) > 0))

    println(if (failed == 0) "\nALL PASS" else "\n$failed 项失败")
    kotlin.system.exitProcess(if (failed == 0) 0 else 1)
}
