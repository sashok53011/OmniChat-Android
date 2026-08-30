# Helper: set ESP-IDF (6.0.2) environment and run a command.
# Clears MSYS/MSYSTEM to avoid detection issues.
$py = "C:\Users\alex\.espressif\python_env\idf6.0_py3.11_env\Scripts\python.exe"
$idf = "C:\esp\v6.0.2\esp-idf"

# Remove MSYS/MSYSTEM to prevent IDF from refusing to run
Remove-Item Env:MSYSTEM -ErrorAction SilentlyContinue
Remove-Item Env:MSYS -ErrorAction SilentlyContinue
Remove-Item Env:MSYSTEM_CARCH -ErrorAction SilentlyContinue
Remove-Item Env:MSYSTEM_PREFIX -ErrorAction SilentlyContinue
Remove-Item Env:MINGW_PREFIX -ErrorAction SilentlyContinue
Remove-Item Env:MINGW_CHOST -ErrorAction SilentlyContinue

$env:IDF_PATH = $idf
$env:IDF_TOOLS_PATH = "C:\Users\alex\.espressif"
$env:IDF_PYTHON_ENV_PATH = "C:\Users\alex\.espressif\python_env\idf6.0_py3.11_env"
$env:ESP_IDF_VERSION = "6.0.2"

$exportOut = & $py "$idf\tools\idf_tools.py" export --format key-value 2>$null
$toolPath = ""
foreach ($line in $exportOut) {
    if ($line -like "PATH=*") {
        $toolPath = $line.Substring(5).Replace(";%PATH%", "")
        break
    }
}

$env:PATH = "C:\Users\alex\.espressif\tools\riscv32-esp-elf\esp-15.2.0_20251204\riscv32-esp-elf\bin;C:\Users\alex\.espressif\tools\xtensa-esp-elf\esp-15.2.0_20251204\xtensa-esp-elf\bin;C:\Users\alex\.espressif\tools\ninja\1.12.1;C:\Users\alex\.espressif\tools\cmake\3.30.2\bin;C:\Users\alex\.espressif\tools\ccache\4.12.1\ccache-4.12.1-windows-x86_64;$toolPath;C:\Users\alex\.espressif\python_env\idf6.0_py3.11_env\Scripts;$idf\tools;" + $env:PATH

& $py "$idf\tools\idf.py" -C "E:\android\omni-chat-myvu-voice\esp32-p4-firmware" @args
exit $LASTEXITCODE
