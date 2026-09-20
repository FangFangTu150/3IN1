$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'LOGO\de4640aa-ba08-4eb0-830e-f65f8bb91954.png'
$destination = Join-Path $root 'app\src\main\res\drawable-nodpi'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$original = [System.Drawing.Bitmap]::new($source)
$color = [System.Drawing.Bitmap]::new(432, 432)
$mono = [System.Drawing.Bitmap]::new(432, 432)
$graphics = [System.Drawing.Graphics]::FromImage($color)
try {
    $graphics.Clear([System.Drawing.Color]::FromArgb(243, 246, 248))
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    # Keep the supplied artwork intact, inset slightly and center its visible mark.
    $graphics.DrawImage($original, [System.Drawing.Rectangle]::new(13, 25, 406, 406))
    for ($y = 0; $y -lt 432; $y++) {
        for ($x = 0; $x -lt 432; $x++) {
            $pixel = $color.GetPixel($x, $y)
            # The supplied mark is blue-gray against a near-white background.
            $alpha = [int][Math]::Clamp((225 - [int]$pixel.R) * 255.0 / 65, 0, 255)
            $mono.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($alpha, 0, 0, 0))
        }
    }
    $color.Save((Join-Path $destination 'app_logo.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $mono.Save((Join-Path $destination 'app_logo_monochrome.png'), [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $graphics.Dispose()
    $original.Dispose()
    $color.Dispose()
    $mono.Dispose()
}
