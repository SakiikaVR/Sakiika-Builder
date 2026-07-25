<#
.SYNOPSIS
  アンパッケージ WinUI 3 アプリ用の resources.pri を生成します。

.DESCRIPTION
  Windows App SDK の標準ビルドは Visual Studio 付属の MSBuild タスク
  (Microsoft.Build.Packaging.Pri.Tasks.dll) で PRI を作りますが、これは
  .NET SDK には含まれません。VS を入れずにビルドできるようにするため、
  Windows SDK の makepri.exe を直接呼んで同等のものを作ります。

  PRI が無いと WinUI は ms-appx:///Microsoft.UI.Xaml/Themes/themeresources.xaml
  を解決できず、起動時に XamlParseException で落ちます。

  出力フォルダーをそのまま索引付けすると、Microsoft.UI.Xaml.Controls.pri が
  宣言しているリソースと、同じ名前で実体のある PNG が衝突します。そのため
  ステージング用フォルダーに「アプリ自身の .xbf」と「参照している .pri」だけを
  集めてから索引付けします。
#>
[CmdletBinding()]
param(
    # 完成した実行ファイルがあるフォルダー（MSBuild の $(TargetDir)）
    [Parameter(Mandatory = $true)][string]$OutDir,

    # PRI の索引名。既定のビルドと同じく $(TargetName) を渡します
    [Parameter(Mandatory = $true)][string]$IndexName,

    # 中間ファイル置き場（MSBuild の $(IntermediateOutputPath)）
    [Parameter(Mandatory = $true)][string]$IntermediateDir,

    # 既定の言語。リソースに言語修飾子は無いので表示上の意味しかありません
    [string]$DefaultLanguage = 'ja-JP'
)

$ErrorActionPreference = 'Stop'

function Find-MakePri {
    $candidates = @()

    # NuGet の Microsoft.Windows.SDK.BuildTools（Windows App SDK が連れてくる）
    $nugetRoot = if ($env:NUGET_PACKAGES) { $env:NUGET_PACKAGES }
                 else { Join-Path $env:USERPROFILE '.nuget\packages' }
    $buildTools = Join-Path $nugetRoot 'microsoft.windows.sdk.buildtools'
    if (Test-Path $buildTools) {
        $candidates += Get-ChildItem $buildTools -Recurse -Filter 'makepri.exe' -ErrorAction SilentlyContinue |
            Where-Object { $_.DirectoryName -like '*\x64' }
    }

    # インストール済みの Windows SDK
    foreach ($kit in @("${env:ProgramFiles(x86)}\Windows Kits\10\bin", "$env:ProgramFiles\Windows Kits\10\bin")) {
        if (Test-Path $kit) {
            $candidates += Get-ChildItem $kit -Recurse -Filter 'makepri.exe' -ErrorAction SilentlyContinue |
                Where-Object { $_.DirectoryName -like '*\x64' }
        }
    }

    if (-not $candidates) {
        throw @'
makepri.exe が見つかりません。
Windows SDK か Microsoft.Windows.SDK.BuildTools パッケージが必要です。
通常は `dotnet restore` で Windows App SDK と一緒に入ります。
'@
    }
    # 新しいものを優先（パスにバージョンが入るので並べ替えで足ります）
    ($candidates | Sort-Object -Property FullName -Descending | Select-Object -First 1).FullName
}

$makepri = Find-MakePri
Write-Host "makepri: $makepri"

if (-not (Test-Path $OutDir)) {
    throw "出力フォルダーがありません: $OutDir"
}

$stage = Join-Path $IntermediateDir 'sakiika-pri'
if (Test-Path $stage) {
    Get-ChildItem $stage -Recurse -File | ForEach-Object { $_.Delete() }
} else {
    New-Item -ItemType Directory -Force $stage | Out-Null
}

# アプリ自身の XAML（コンパイル済み XBF）
$xbf = @(Get-ChildItem $OutDir -Filter '*.xbf' -File -ErrorAction SilentlyContinue)
foreach ($file in $xbf) { Copy-Item $file.FullName -Destination $stage -Force }

# 参照している PRI（WinUI のテーマリソースがこの中に入っています）
$pri = @(Get-ChildItem $OutDir -Filter '*.pri' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne 'resources.pri' })
foreach ($file in $pri) { Copy-Item $file.FullName -Destination $stage -Force }

if ($xbf.Count -eq 0 -and $pri.Count -eq 0) {
    throw "索引付けする対象が $OutDir にありません（ビルドが完了していない可能性があります）"
}
Write-Host "索引付け対象: XBF $($xbf.Count) 件 / PRI $($pri.Count) 件"

$config = Join-Path $IntermediateDir 'sakiika-priconfig.xml'
& $makepri createconfig /cf $config /dq $DefaultLanguage /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makepri createconfig が失敗しました (終了コード $LASTEXITCODE)" }

$output = Join-Path $OutDir 'resources.pri'
& $makepri new /pr $stage /cf $config /of $output /IndexName $IndexName /o | Out-Null
if ($LASTEXITCODE -ne 0) { throw "makepri new が失敗しました (終了コード $LASTEXITCODE)" }

$size = (Get-Item $output).Length
Write-Host "resources.pri を作成しました ($([math]::Round($size / 1024)) KB): $output"
