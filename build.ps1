<#
.SYNOPSIS
  さきいかビルダー本体（Rust エンジン + WinUI 3 GUI）をビルドします。

.DESCRIPTION
  完成した sakiika.exe には、コンパイル済みの Android ランタイム
  （template.apk）が埋め込まれます。そのため利用者の PC には Java も
  Android SDK も要りません。

  テンプレートを作り直すときだけ Android SDK と JDK が必要です
  （-Template を指定、または core/prebuilt/template.apk が空のとき）。

.EXAMPLE
  .\build.ps1                 エンジン + GUI
  .\build.ps1 -Template       テンプレートも作り直す（SDK と JDK が必要）
  .\build.ps1 -EngineOnly     エンジンだけ（.NET SDK 不要）
  .\build.ps1 -Demo           デモアプリの APK も作る
#>
[CmdletBinding()]
param(
    # ランタイムテンプレートを作り直します（Android SDK と JDK が必要）
    [switch]$Template,

    # Rust エンジンだけをビルドします（.NET SDK が無い場合）
    [switch]$EngineOnly,

    # ビルド後にデモアプリの APK も作ります
    [switch]$Demo,

    # GUI のターゲット CPU
    [ValidateSet('x64', 'x86', 'ARM64')]
    [string]$Platform = 'x64'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Step($text) {
    Write-Host ""
    Write-Host "=== $text ===" -ForegroundColor Cyan
}

$templateApk = Join-Path $root 'core\prebuilt\template.apk'
$templateMissing = -not (Test-Path $templateApk) -or (Get-Item $templateApk).Length -eq 0

if ($templateMissing -and -not $Template) {
    Write-Host "ランタイムテンプレートが空です。-Template を付けて実行します。" -ForegroundColor Yellow
    $Template = $true
}

Step "Rust エンジンをビルド"
Push-Location $root
try {
    cargo build --release
    if ($LASTEXITCODE -ne 0) { throw "cargo build が失敗しました" }
} finally {
    Pop-Location
}
$engine = Join-Path $root 'target\release\sakiika.exe'
if (-not (Test-Path $engine)) { throw "エンジンが出力されていません: $engine" }

if ($Template) {
    Step "ランタイムテンプレートを生成（Android SDK と JDK が必要）"
    & $engine devtemplate
    if ($LASTEXITCODE -ne 0) {
        throw @'
テンプレートの生成に失敗しました。
Android SDK (build-tools + platforms) と JDK 17 以上が必要です。
場所を自動検出できない場合は環境変数で指定してください:
  $env:SAKIIKA_SDK = "C:\AndroidSDK"
  $env:SAKIIKA_JDK = "C:\Program Files\Eclipse Adoptium\jdk-17..."
'@
    }

    Step "テンプレートを実行ファイルへ埋め込み（再ビルド）"
    Push-Location $root
    try {
        cargo build --release
        if ($LASTEXITCODE -ne 0) { throw "埋め込み後の cargo build が失敗しました" }
    } finally {
        Pop-Location
    }
}

Step "動作状態を確認"
& $engine doctor
if ($LASTEXITCODE -ne 0) { throw "doctor が失敗しました" }

if (-not $EngineOnly) {
    Step "WinUI 3 GUI をビルド"
    $project = Join-Path $root 'ui\SakiikaBuilder\SakiikaBuilder.csproj'
    # 実行中の GUI がいると出力先の DLL がロックされてコピーに失敗します。
    Get-Process -Name 'SakiikaBuilder' -ErrorAction SilentlyContinue | ForEach-Object {
        try { $_.Kill(); Write-Host "起動中の GUI を終了しました (pid $($_.Id))" } catch {
            Write-Warning "起動中の GUI (pid $($_.Id)) を終了できませんでした。手動で閉じてから再実行してください。"
        }
    }
    dotnet build $project -c Release -p:Platform=$Platform
    if ($LASTEXITCODE -ne 0) { throw "GUI のビルドが失敗しました" }

    $guiDir = Join-Path $root "ui\SakiikaBuilder\bin\$Platform\Release\net9.0-windows10.0.19041.0"
    $exe = Get-ChildItem $guiDir -Recurse -Filter 'SakiikaBuilder.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($exe) { Write-Host "GUI: $($exe.FullName)" }
}

if ($Demo) {
    Step "デモアプリの APK をビルド"
    & $engine quick `
        --web (Join-Path $root 'demo') `
        --name 'さきいかデモ' `
        --package 'com.sakiika.demo' `
        --file-access folder_pick `
        --permissions 'INTERNET,ACCESS_NETWORK_STATE,ACCESS_WIFI_STATE,VIBRATE,WAKE_LOCK,CAMERA,RECORD_AUDIO,ACCESS_FINE_LOCATION,ACCESS_COARSE_LOCATION,POST_NOTIFICATIONS,READ_CONTACTS,READ_MEDIA_IMAGES,USE_BIOMETRIC,QUERY_ALL_PACKAGES' `
        --no-splash `
        --out-dir (Join-Path $root 'out')
    if ($LASTEXITCODE -ne 0) { throw "デモ APK のビルドが失敗しました" }
}

Step "完了"
