# Generate larger LVGL fonts for OmniChat-P4 with real Bold/Italic.
$ErrorActionPreference = "Stop"
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [System.Environment]::GetEnvironmentVariable("Path","User")

$src_medium   = "C:\Temp\opencode\Montserrat-Medium.ttf"
$src_bold     = "C:\Temp\opencode\Montserrat-Bold.ttf"
$src_italic   = "C:\Temp\opencode\Montserrat-Italic.ttf"
$src_mi       = "C:\Temp\opencode\Montserrat-MediumItalic.ttf"
$out = "E:\android\omni-chat-myvu-voice\esp32-p4-firmware\main\fonts"

$ranges = @(
    "0x20-0x7F",      # Basic Latin
    "0xA0-0xFF",      # Latin-1 Supplement (DE umlauts)
    "0x100-0x17F",    # Latin Extended-A
    "0x200-0x2FF",    # Latin Extended-B + Spacing Modifiers
    "0x400-0x45F",    # Cyrillic
    "0x2010-0x2027",  # General punctuation
    "0x2190-0x21FF",  # Arrows (← → ↑ ↓ etc.)
    "0x2600-0x26FF",  # Misc Symbols (☀ ♪ ♥ etc.)
    "0x2700-0x27BF",  # Dingbats (✓ ✗ ★ etc.)
    "0x20AC"           # Euro sign
)

function New-Font($name, $size, $bpp, $src) {
    Write-Host "=== $name ($size px, bpp $bpp) ==="
    $args = @("--size", "$size", "--bpp", "$bpp", "--format", "lvgl", "--font", $src)
    foreach ($r in $ranges) { $args += @("-r", $r) }
    $args += @("--no-compress", "--no-prefilter", "--lv-include", "lvgl.h", "--lv-font-name", $name, "-o", "$out\$name.c")
    & lv_font_conv @args
    Write-Host "done: $name"
}

# Body: 20px Medium (was 16)
New-Font "lv_font_md_body_20"   20 4 $src_medium

# Bold: 20px actual Bold (was 16 Medium copy)
New-Font "lv_font_md_bold_20"   20 4 $src_bold

# Italic: 20px actual Italic (was 16 Medium copy)
New-Font "lv_font_md_italic_20" 20 4 $src_italic

# Headings: bigger
New-Font "lv_font_md_h1_32"     32 4 $src_bold
New-Font "lv_font_md_h2_26"     26 4 $src_bold
New-Font "lv_font_md_h3_22"     22 4 $src_bold

# Code: 16px (was 14)
New-Font "lv_font_md_code_16"   16 4 $src_medium

Write-Host "All fonts generated."
