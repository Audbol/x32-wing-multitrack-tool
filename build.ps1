# Builds the X32 / M32 / WING Multitrack Tool. Needs a JDK 17+ (jpackage for -Exe).
#   .\build.ps1             dist\X32WingTool-<ver>.jar
#   .\build.ps1 -Exe        + dist\X32WingTool\ (self-contained app with its own Java runtime)
#   .\build.ps1 -Installer  + dist\X32WingTool-<ver>-Setup.exe (needs Inno Setup 6)
#   .\build.ps1 -Release    everything, plus a portable zip and SHA256SUMS.txt
param([switch]$Exe, [switch]$Installer, [switch]$Release)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($Release) { $Installer = $true }
if ($Installer) { $Exe = $true }

$version = [regex]::Match((Get-Content src\x32wing\Main.java -Raw), 'VERSION = "([^"]+)"').Groups[1].Value
if (-not $version) { throw 'Could not read VERSION from Main.java' }

$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { (Get-ChildItem 'C:\Program Files\Java' -Directory | Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } | Sort-Object Name -Descending | Select-Object -First 1).FullName }
if (-not $jdk) { throw 'No JDK found. Install a JDK 17+ or set JAVA_HOME.' }
$bin = Join-Path $jdk 'bin'

function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed" } }

Remove-Item -Recurse -Force build, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build\classes\x32wing, build\jpkg, dist | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& "$bin\javac.exe" --release 17 -encoding UTF-8 -Xlint:all,-serial -d build\classes $sources; Check 'javac'
Copy-Item packaging\icon-512.png build\classes\x32wing\icon.png
$jar = "X32WingTool-$version.jar"
& "$bin\jar.exe" --create --file "build\jpkg\X32WingTool.jar" --main-class x32wing.Main -C build\classes .; Check 'jar'
Copy-Item build\jpkg\X32WingTool.jar "dist\$jar"
Write-Host "Built dist\$jar"

if ($Exe) {
    & "$bin\jpackage.exe" --type app-image --name X32WingTool --input build\jpkg --main-jar X32WingTool.jar `
        --main-class x32wing.Main --dest dist --app-version $version --vendor Audbol `
        --description "X32 / M32 / WING multitrack tool" --icon packaging\app.ico `
        --add-launcher x32wing=packaging\cli-launcher.properties `
        --java-options "-Xmx2g" --add-modules java.base,java.desktop,java.prefs
    Check 'jpackage'
    Write-Host "Built dist\X32WingTool\ (X32WingTool.exe = app, x32wing.exe = command line)"
}

if ($Installer) {
    $iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe") |
        Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $iscc) { throw 'Inno Setup 6 (ISCC.exe) not found.' }
    & $iscc /Q "/DAppVersion=$version" packaging\installer.iss; Check 'Inno Setup'
    Write-Host "Built dist\X32WingTool-$version-Setup.exe"
}

if ($Release) {
    $zip = "dist\X32WingTool-$version-portable-win64.zip"
    Compress-Archive -Path dist\X32WingTool -DestinationPath $zip
    $assets = Get-ChildItem dist -File
    $assets | ForEach-Object { "{0}  {1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(), $_.Name } |
        Set-Content dist\SHA256SUMS.txt -Encoding ascii
    Write-Host "Release assets:"
    Get-ChildItem dist -File | ForEach-Object { "  {0,-45} {1,8:N1} MB" -f $_.Name, ($_.Length / 1MB) }
}
