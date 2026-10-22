# 编译并运行 _probe/DownloadMathTest.kt + 真源码 data/DownloadMath.kt（纯 JVM，无需设备/网络）
# 用 Gradle 缓存里的 Kotlin 编译器；classpath 直接在缓存里按文件名找。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'

function Find-Jar([string]$pattern) {
  $hit = Get-ChildItem -Path $cache -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
  if (-not $hit) { throw "Gradle 缓存里找不到 $pattern" }
  return $hit.FullName
}

$compiler = Find-Jar 'kotlin-compiler-embeddable-*.jar'
$stdlib = Find-Jar 'kotlin-stdlib-2.2.10.jar'
$deps = @(
  $stdlib,
  (Find-Jar 'kotlinx-coroutines-core-jvm-*.jar'),
  (Find-Jar 'kotlin-daemon-embeddable-*.jar'),
  (Find-Jar 'kotlin-script-runtime-*.jar'),
  (Find-Jar 'kotlin-reflect-2.2.10.jar'),
  (Find-Jar 'annotations-13.0.jar')
) -join ';'
Write-Host "compiler = $compiler"
Write-Host "stdlib   = $stdlib"

$out = Join-Path $PSScriptRoot 'out-math'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

$src = @(
  (Join-Path $PSScriptRoot 'DownloadMathTest.kt'),
  (Join-Path $root 'app\src\main\java\com\netease\lite\data\DownloadMath.kt')
)

& java -cp "$compiler;$deps" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @src `
  -classpath $stdlib -d $out -no-stdlib -no-reflect -nowarn
if ($LASTEXITCODE -ne 0) { throw "Kotlin 编译失败 EXIT=$LASTEXITCODE" }

& java -cp "$out;$($stdlib)" com.netease.lite.data.DownloadMathTestKt
exit $LASTEXITCODE
