# アバター画像から各用途のアイコンを書き出す。
# 開発時に一度だけ実行し、生成物はリポジトリに入れる。
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$Root
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$src = New-Object System.Drawing.Bitmap($Source)

function Save-Resized([int]$w, [int]$h, [string]$path, [double]$scale, [bool]$transparent) {
    $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    if (-not $transparent) { $g.Clear([System.Drawing.Color]::Transparent) }
    $target = [int][Math]::Round($w * $scale)
    $offset = [int][Math]::Round(($w - $target) / 2.0)
    $g.DrawImage($src, $offset, $offset, $target, $target)
    $g.Dispose()
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("  {0}  {1}x{1}  scale={2}" -f (Split-Path $path -Leaf), $w, $scale)
}

Write-Host "アイコンを生成:"

# アダプティブアイコンの前景。ランチャーは 108dp のうち中央 72dp しか見せないため
# 画像全体を残すには 2/3 に収める必要がある。
Save-Resized 432 432 (Join-Path $Root "core\assets\icon-foreground.png") (72.0 / 108.0) $false

# 旧形式の正方アイコン。minSdk 26 では anydpi-v26 のアダプティブアイコンが常に
# 優先されるため実際には使われない。リソースの整合のために置くだけなので、
# 生成される APK を無駄に太らせないよう小さめにしておく。
Save-Resized 192 192 (Join-Path $Root "core\assets\icon-legacy.png") 1.0 $false

# GUI のヘッダー用とデモアプリ用。
Save-Resized 256 256 (Join-Path $Root "ui\SakiikaBuilder\Assets\icon.png") 1.0 $false
Save-Resized 96 96 (Join-Path $Root "demo\icon.png") 1.0 $false

# --- Windows 用 .ico ---
# Vista 以降は PNG を格納した ICO を読めるので、各サイズを PNG として詰める。
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs = @()
foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($s, $s, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.DrawImage($src, 0, 0, $s, $s)
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $pngs += , @{ size = $s; bytes = $ms.ToArray() }
    $ms.Dispose()
}

$icoPath = Join-Path $Root "ui\SakiikaBuilder\Assets\icon.ico"
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter($out)
$w.Write([UInt16]0)               # reserved
$w.Write([UInt16]1)               # type: icon
$w.Write([UInt16]$pngs.Count)
$offset = 6 + 16 * $pngs.Count
foreach ($p in $pngs) {
    # 256 は 0 として記録するのが ICO の決まり。
    $dim = if ($p.size -ge 256) { 0 } else { $p.size }
    $w.Write([byte]$dim)          # width
    $w.Write([byte]$dim)          # height
    $w.Write([byte]0)             # palette count
    $w.Write([byte]0)             # reserved
    $w.Write([UInt16]1)           # color planes
    $w.Write([UInt16]32)          # bits per pixel
    $w.Write([UInt32]$p.bytes.Length)
    $w.Write([UInt32]$offset)
    $offset += $p.bytes.Length
}
foreach ($p in $pngs) { $w.Write($p.bytes) }
$w.Flush()
[System.IO.File]::WriteAllBytes($icoPath, $out.ToArray())
$w.Dispose(); $out.Dispose()
Write-Host ("  icon.ico  {0} サイズ  {1} bytes" -f $pngs.Count, (Get-Item $icoPath).Length)

# --- 余白色（アダプティブアイコンの背景）をアバターの縁から拾う ---
# 前景を 2/3 に縮めた分だけ余白ができる。そこを画像自身の縁の色で埋めると
# 継ぎ目が見えない。ここで出た値を config.rs の icon_background の既定値にする。
$rs = 0; $gs = 0; $bs = 0; $n = 0
for ($i = 0; $i -lt $src.Width; $i += 2) {
    $c = $src.GetPixel($i, 0);               $rs += $c.R; $gs += $c.G; $bs += $c.B; $n++
    $c = $src.GetPixel($i, $src.Height - 1); $rs += $c.R; $gs += $c.G; $bs += $c.B; $n++
    $c = $src.GetPixel(0, $i);               $rs += $c.R; $gs += $c.G; $bs += $c.B; $n++
    $c = $src.GetPixel($src.Width - 1, $i);  $rs += $c.R; $gs += $c.G; $bs += $c.B; $n++
}
$edge = "#{0:X2}{1:X2}{2:X2}" -f [int]($rs / $n), [int]($gs / $n), [int]($bs / $n)
Write-Host ""
Write-Host "縁の平均色（config.rs の icon_background の既定値に使う）: $edge"
$src.Dispose()
