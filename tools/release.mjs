#!/usr/bin/env node
/**
 * 打正式包并放进成品目录（用户要求：每次更新都存一份到 D:\down\Tools\FromAI）。
 *
 * 为什么要有这个脚本：`gradlew assembleRelease` 只把 APK 放在
 * `app/build/outputs/apk/release/app-release.apk`，而用户要的是
 * `D:\down\Tools\FromAI\网易云音乐 Lite Android\网易云音乐 Lite-<版本>.apk`。
 * 少了这一步，每次更新都得手动复制一次，总有一天会忘了复制、或者复制了一个旧包 ——
 * 装到手机上才发现「改的东西没生效」。
 *
 * 顺带把 README 同步过去：成品目录里那份 README 也要能解释这一版改了什么，
 * 否则隔一周再看只剩一个 APK，无从考据。
 *
 * 用法：
 *   node tools/release.mjs               # 打 release 包并发布
 *   node tools/release.mjs --skip-build  # 只发布现有产物（APK 刚打过时）
 *   node tools/release.mjs --debug       # 用 debug 包（临时验证，不覆盖正式包名）
 *
 * 输出目录可用 `NETEASE_LITE_DIST` 覆盖，默认值与桌面端 `scripts/build.js` 同一个父目录。
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(here, '..');

const DEFAULT_DIST_PARENT = 'D:\\down\\Tools\\FromAI';
const distParent = path.resolve(process.env.NETEASE_LITE_DIST || DEFAULT_DIST_PARENT);
const DIST_NAME = '网易云音乐 Lite Android';
const outDir = path.join(distParent, DIST_NAME);

const args = new Set(process.argv.slice(2));
const skipBuild = args.has('--skip-build');
const wantDebug = args.has('--debug');

const isWin = process.platform === 'win32';
const gradlew = path.join(projectRoot, isWin ? 'gradlew.bat' : 'gradlew');

/** 从 build.gradle.kts 里抠 versionName —— 包名要带版本，不能写死 1.0.0。 */
function readVersionName() {
  const text = fs.readFileSync(path.join(projectRoot, 'app', 'build.gradle.kts'), 'utf8');
  const m = /versionName\s*=\s*"([^"]+)"/.exec(text);
  return m ? m[1] : '0.0.0';
}

/** 从 build.gradle.kts 里抠 applicationId（默认 com.netease.lite）。 */
function readAppId() {
  const text = fs.readFileSync(path.join(projectRoot, 'app', 'build.gradle.kts'), 'utf8');
  const m = /applicationId\s*=\s*"([^"]+)"/.exec(text);
  return m ? m[1] : 'com.netease.lite';
}

function run(cmd, cmdArgs, opts = {}) {
  // Windows 上 .bat 不能直接 CreateProcess，Node 会抛 EINVAL；必须过一层 cmd.exe。
  // 这里传「一条已经拼好的命令字符串」给 shell，而不是 (命令, 参数数组)：
  // 后者在 shell:true 下会触发 DEP0190（参数只拼接不转义），日志里全是噪音。
  const base = { stdio: 'inherit', cwd: projectRoot, ...opts };
  const quote = (a) => (/[\s"]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a);
  const line = [cmd, ...cmdArgs].map(quote).join(' ');
  const r = isWin
    ? spawnSync(line, { ...base, shell: true })
    : spawnSync(cmd, cmdArgs, base);
  if (r.status !== 0) throw new Error(`命令失败（退出码 ${r.status}）：${line}`);
}

/**
 * 覆盖前先确认「没有别的进程正拿着这个 APK」。
 *
 * Windows 上被占用的文件会 `EBUSY`/`EPERM`，而这一步失败很容易被当成脚本 bug 排查半天。
 * 直接把原因说清楚：多半是资源管理器开着预览、或者某个手机助手正在读它。
 */
function copyFile(src, dest) {
  try {
    fs.copyFileSync(src, dest);
  } catch (e) {
    if (e && (e.code === 'EBUSY' || e.code === 'EPERM')) {
      throw new Error(
        `无法覆盖 ${dest}：文件被占用。请关掉正在读它的程序（资源管理器预览 / 手机助手）后重试。`,
      );
    }
    throw e;
  }
}

function rmrf(p) {
  if (fs.existsSync(p)) fs.rmSync(p, { recursive: true, force: true });
}

function main() {
  const version = readVersionName();
  const appId = readAppId();
  const variant = wantDebug ? 'debug' : 'release';
  const apkName = wantDebug ? 'app-debug.apk' : 'app-release.apk';
  const apkPath = path.join(projectRoot, 'app', 'build', 'outputs', 'apk', variant, apkName);

  if (!skipBuild) {
    console.log(`[release] 构建 ${variant} 包 ...`);
    run(gradlew, [`:app:assemble${wantDebug ? 'Debug' : 'Release'}`, '--offline', '-q']);
  } else {
    console.log('[release] 跳过构建，直接发布现有产物');
  }

  if (!fs.existsSync(apkPath)) {
    throw new Error(`没有找到 ${apkPath}，先跑一次 assemble${wantDebug ? 'Debug' : 'Release'}`);
  }

  fs.mkdirSync(outDir, { recursive: true });

  // 正式包固定叫「网易云音乐 Lite-<版本>.apk」；debug 包加后缀，免得覆盖正式包。
  const destName = wantDebug
    ? `网易云音乐 Lite-${version}-debug.apk`
    : `网易云音乐 Lite-${version}.apk`;
  const destApk = path.join(outDir, destName);
  copyFile(apkPath, destApk);

  // README 跟着走，成品目录里那份文档要能解释这一版改了什么。
  copyFile(path.join(projectRoot, 'README.md'), path.join(outDir, 'README.md'));
  // 仓库里已经没有 docs/ 了，成品目录残留的旧截图目录一并清掉。
  rmrf(path.join(outDir, 'docs'));

  const size = fs.statSync(destApk).size / 1024 / 1024;
  console.log('');
  console.log('[release] 完成 ✔');
  console.log('[release] 包名    :', appId, 'v' + version);
  console.log('[release] 产物    :', destApk);
  console.log('[release] 大小    :', size.toFixed(1), 'MiB');
  console.log('[release] 安装    : adb install -r "' + destApk + '"');
}

try {
  main();
} catch (e) {
  console.error('\n[release] 失败:', e && e.message ? e.message : e);
  process.exit(1);
}
