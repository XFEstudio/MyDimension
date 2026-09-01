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
            for ($arm = 0; $arm -lt 2; $arm++) {
                for ($step = 0; $step -lt 28; $step++) {
                    $progress = $step / 27.0
                    $radius = 6.25 * (1.0 - $progress) + 0.7
                    $angle = $phase + $arm * [Math]::PI +
                        $Direction * ($progress * 1.35 * [Math]::PI)
                    $x = [int][Math]::Round(7.5 + [Math]::Cos($angle) * $radius)
                    $y = [int][Math]::Round(7.5 + [Math]::Sin($angle) * $radius) + $frame * $frameSize
                    $index = [Math]::Min($Palette.Length - 1, [int]($progress * $Palette.Length))
                    $color = $Palette[$index]
                    Add-Pixel $bitmap $x $y $color
                }
            }
            $centerY = 8 + $frame * $frameSize
            Add-Pixel $bitmap 7 $centerY ([System.Drawing.Color]::FromArgb(255, 224, 248, 255))
            Add-Pixel $bitmap 8 $centerY ([System.Drawing.Color]::FromArgb(255, 117, 194, 255))
        }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $bitmap.Dispose()
    }
}

function New-RealmwrightBodyTexture {
    param([string]$Path)

    $palette = @(
        [System.Drawing.Color]::FromArgb(255, 5, 6, 14),
        [System.Drawing.Color]::FromArgb(255, 10, 12, 27),
        [System.Drawing.Color]::FromArgb(255, 17, 20, 42),
        [System.Drawing.Color]::FromArgb(255, 24, 25, 55),
        [System.Drawing.Color]::FromArgb(255, 30, 27, 68)
    )
    $bitmap = New-Object System.Drawing.Bitmap(16, 16,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        for ($y = 0; $y -lt 16; $y++) {
            for ($x = 0; $x -lt 16; $x++) {
                $noise = [Math]::Abs(($x * 19 + $y * 31 + $x * $y * 7 + ($x - $y) * 11) % 29)
                $index = if ($noise -lt 4) { 0 } elseif ($noise -lt 12) { 1 } elseif ($noise -lt 21) { 2 } elseif ($noise -lt 27) { 3 } else { 4 }
                $bitmap.SetPixel($x, $y, $palette[$index])
            }
        }

        # Sparse violet-blue fissures keep the shaft readable without turning it
        # into a metallic or industrial material.
        $vein = [System.Drawing.Color]::FromArgb(255, 54, 31, 116)
        $veinHighlight = [System.Drawing.Color]::FromArgb(255, 75, 55, 158)
        foreach ($point in @(@(1, 3), @(2, 4), @(3, 5), @(3, 6), @(4, 7), @(5, 7),
                @(9, 0), @(9, 1), @(10, 2), @(11, 3), @(11, 4), @(12, 5),
                @(14, 10), @(13, 11), @(12, 12), @(12, 13), @(11, 14), @(10, 15))) {
            $bitmap.SetPixel($point[0], $point[1], $vein)
        }
        foreach ($point in @(@(3, 5), @(5, 7), @(10, 2), @(12, 5), @(12, 12), @(10, 15))) {
            $bitmap.SetPixel($point[0], $point[1], $veinHighlight)
        }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $bitmap.Dispose()
    }
}

function New-RealmwrightCrystalTexture {
    param([string]$Path)

    $bitmap = New-Object System.Drawing.Bitmap(16, 16,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $dark = [System.Drawing.Color]::FromArgb(255, 9, 5, 28)
        $purple = [System.Drawing.Color]::FromArgb(255, 40, 12, 91)
        $violet = [System.Drawing.Color]::FromArgb(255, 83, 30, 174)
        $blue = [System.Drawing.Color]::FromArgb(255, 28, 83, 190)
        $cyan = [System.Drawing.Color]::FromArgb(255, 45, 185, 235)
        $glint = [System.Drawing.Color]::FromArgb(255, 151, 115, 255)

        for ($y = 0; $y -lt 16; $y++) {
            for ($x = 0; $x -lt 16; $x++) {
                $noise = [Math]::Abs(($x * 23 + $y * 17 + $x * $y * 5) % 19)
                $color = if ($noise -lt 7) { $dark } elseif ($noise -lt 13) { $purple } elseif ($noise -lt 17) { $violet } else { $blue }
                $bitmap.SetPixel($x, $y, $color)
            }
        }

        foreach ($point in @(@(0, 12), @(1, 11), @(2, 10), @(3, 9), @(4, 8), @(5, 8),
                @(6, 7), @(7, 6), @(8, 6), @(9, 5), @(10, 4), @(11, 4), @(12, 3),
                @(13, 2), @(14, 2), @(15, 1))) {
            $bitmap.SetPixel($point[0], $point[1], $cyan)
        }
        foreach ($point in @(@(2, 10), @(5, 8), @(8, 6), @(11, 4), @(14, 2),
                @(5, 9), @(6, 10), @(7, 11), @(8, 12), @(9, 13))) {
            $bitmap.SetPixel($point[0], $point[1], $glint)
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
    [System.Drawing.Color]::FromArgb(255, 21, 12, 72),
    [System.Drawing.Color]::FromArgb(255, 34, 70, 184),
    [System.Drawing.Color]::FromArgb(255, 24, 188, 245),
    [System.Drawing.Color]::FromArgb(255, 184, 240, 255)
)
$demolishPalette = @(
    [System.Drawing.Color]::FromArgb(255, 18, 8, 61),
    [System.Drawing.Color]::FromArgb(255, 73, 20, 155),
    [System.Drawing.Color]::FromArgb(255, 168, 48, 245),
    [System.Drawing.Color]::FromArgb(255, 111, 176, 255)
)

New-RealmwrightBodyTexture (Join-Path $resolvedOutput 'realmwright_voidstone.png')
New-RealmwrightCrystalTexture (Join-Path $resolvedOutput 'realmwright_rift_crystal.png')
New-VortexTexture (Join-Path $resolvedOutput 'realmwright_effect_build.png') 1 $buildPalette
New-VortexTexture (Join-Path $resolvedOutput 'realmwright_effect_demolish.png') -1 $demolishPalette
