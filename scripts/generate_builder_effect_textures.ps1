param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\src\main\resources\assets\mydimension\textures\item')
)

Add-Type -AssemblyName System.Drawing

function Add-Pixel {
    param(
        [System.Drawing.Bitmap]$Bitmap,
        [int]$X,
        [int]$Y,
        [System.Drawing.Color]$Color
    )
    if ($X -lt 0 -or $X -ge $Bitmap.Width -or $Y -lt 0 -or $Y -ge $Bitmap.Height) {
        return
    }
    $previous = $Bitmap.GetPixel($X, $Y)
    $alpha = [Math]::Min(255, $previous.A + $Color.A)
    if ($alpha -eq 0) { return }
    $red = [int](($previous.R * $previous.A + $Color.R * $Color.A) / [Math]::Max(1, $previous.A + $Color.A))
    $green = [int](($previous.G * $previous.A + $Color.G * $Color.A) / [Math]::Max(1, $previous.A + $Color.A))
    $blue = [int](($previous.B * $previous.A + $Color.B * $Color.A) / [Math]::Max(1, $previous.A + $Color.A))
    $Bitmap.SetPixel($X, $Y, [System.Drawing.Color]::FromArgb($alpha, $red, $green, $blue))
}

function New-VortexTexture {
    param(
        [string]$Path,
        [int]$Direction,
        [System.Drawing.Color[]]$Palette
    )

    $frameSize = 16
    $frameCount = 8
    $bitmap = New-Object System.Drawing.Bitmap($frameSize, ($frameSize * $frameCount),
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        for ($frame = 0; $frame -lt $frameCount; $frame++) {
            $phase = 2.0 * [Math]::PI * $frame / $frameCount
            for ($arm = 0; $arm -lt 3; $arm++) {
                for ($step = 0; $step -lt 52; $step++) {
                    $progress = $step / 51.0
                    $radius = 6.1 * (1.0 - $progress) + 0.35
                    $angle = $phase + $arm * (2.0 * [Math]::PI / 3.0) +
                        $Direction * ($progress * 2.45 * [Math]::PI)
                    $x = [int][Math]::Round(7.5 + [Math]::Cos($angle) * $radius)
                    $y = [int][Math]::Round(7.5 + [Math]::Sin($angle) * $radius) + $frame * $frameSize
                    $index = [Math]::Min($Palette.Length - 1, [int]($progress * $Palette.Length))
                    $color = $Palette[$index]
                    Add-Pixel $bitmap $x $y $color
                    if (($step + $arm) % 3 -eq 0) {
                        $glow = [System.Drawing.Color]::FromArgb(62, $color.R, $color.G, $color.B)
                        Add-Pixel $bitmap ($x + 1) $y $glow
                        Add-Pixel $bitmap ($x - 1) $y $glow
                        Add-Pixel $bitmap $x ($y + 1) $glow
                        Add-Pixel $bitmap $x ($y - 1) $glow
                    }
                }
            }
            $centerY = 8 + $frame * $frameSize
            Add-Pixel $bitmap 7 $centerY ([System.Drawing.Color]::FromArgb(245, 245, 255, 255))
            Add-Pixel $bitmap 8 $centerY ([System.Drawing.Color]::FromArgb(220, 210, 255, 255))
            Add-Pixel $bitmap 7 ($centerY - 1) ([System.Drawing.Color]::FromArgb(180, 190, 255, 255))
        }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $bitmap.Dispose()
    }
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

$buildPalette = @(
    [System.Drawing.Color]::FromArgb(150, 0, 112, 126),
    [System.Drawing.Color]::FromArgb(205, 0, 210, 174),
    [System.Drawing.Color]::FromArgb(235, 27, 242, 221),
    [System.Drawing.Color]::FromArgb(255, 174, 255, 248)
)
$demolishPalette = @(
    [System.Drawing.Color]::FromArgb(150, 92, 0, 22),
    [System.Drawing.Color]::FromArgb(205, 211, 18, 39),
    [System.Drawing.Color]::FromArgb(235, 255, 91, 15),
    [System.Drawing.Color]::FromArgb(255, 255, 224, 158)
)

New-VortexTexture (Join-Path $resolvedOutput 'realmwright_effect_build.png') 1 $buildPalette
New-VortexTexture (Join-Path $resolvedOutput 'realmwright_effect_demolish.png') -1 $demolishPalette
