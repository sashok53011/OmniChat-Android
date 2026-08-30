# Generate the Unicode LVGL fonts for OmniChat-P4.
# Prerequisites: node + lv_font_conv (`npm i -g lv_font_conv`).
# Source font: Montserrat-Medium.ttf (Latin + Cyrillic + Latin-Extended).
# Output: main/fonts/font_md_*.c used by md_renderer.c.
$ErrorActionPreference = "Stop"

$src = "C:\Users\alex\Downloads\Compressed\ESP32-P4-WIFI6-Touch-LCD-4B_3\Arduino\libraries\lvgl\scripts\built_in_font\Montserrat-Medium.ttf"
$out = "E:\android\omni-chat-myvu-voice\esp32-p4-firmware\main\fonts"

# Unicode ranges: Basic Latin, Latin-1 Supplement (DE umlauts), Latin Ext-A,
# Cyrillic, and common punctuation/dashes.
$ranges = @("0x20-0x7F", "0xA0-0xFF", "0x100-0x17F", "0x400-0x45F", "0x2010-0x2027", "0x20AC")

function New-Font($name, $size, $bpp) {
    Write-Host "=== $name ($size px, bpp $bpp) ==="
    $args = @("--size", "$size", "--bpp", "$bpp", "--format", "lvgl", "--font", $src)
    foreach ($r in $ranges) { $args += @("-r", $r) }
    $args += @("--no-compress", "--no-prefilter", "--lv-include", "lvgl.h", "--lv-font-name", $name, "-o", "$out\$name.c")
    & lv_font_conv @args
    Write-Host "done: $name"
}

New-Font "lv_font_md_body_16"   16 4
New-Font "lv_font_md_bold_16"   16 4
New-Font "lv_font_md_italic_16" 16 4
New-Font "lv_font_md_h1_26"     26 4
New-Font "lv_font_md_h2_22"     22 4
New-Font "lv_font_md_h3_20"     20 4
New-Font "lv_font_md_code_14"   14 4

Write-Host "All fonts generated."
