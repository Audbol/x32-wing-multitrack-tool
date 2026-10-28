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

# FlatLaf (Apache-2.0) look and feel, pinned by version and SHA-256, fetched from Maven Central once.
$flatlafVersion = '3.7.2'
$flatlafSha256 = '917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4'
$flatlaf = "lib\flatlaf-$flatlafVersion.jar"
if (-not (Test-Path $flatlaf)) {
    New-Item -ItemType Directory -Force lib | Out-Null
    Write-Host "Downloading FlatLaf $flatlafVersion from Maven Central"
    Invoke-WebRequest "https://repo1.maven.org/maven2/com/formdev/flatlaf/$flatlafVersion/flatlaf-$flatlafVersion.jar" -OutFile $flatlaf -UseBasicParsing
}
if ((Get-FileHash $flatlaf -Algorithm SHA256).Hash.ToLower() -ne $flatlafSha256) {
    Remove-Item $flatlaf
    throw "FlatLaf checksum mismatch - download deleted"
}

Remove-Item -Recurse -Force build, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force build\classes\x32wing, build\jpkg, build\deps, dist | Out-Null
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
& "$bin\javac.exe" --release 17 -encoding UTF-8 -Xlint:all,-serial -cp $flatlaf -d build\classes $sources; Check 'javac'
Copy-Item packaging\icon-512.png build\classes\x32wing\icon.png
# Merge FlatLaf into one runnable jar (its module-info and manifest are dropped; licence kept).
Push-Location build\deps
& "$bin\jar.exe" --extract --file "..\..\$flatlaf"; Check 'unpack FlatLaf'
Pop-Location
Remove-Item build\deps\module-info.class, build\deps\META-INF\MANIFEST.MF -ErrorAction SilentlyContinue
Get-ChildItem build\deps\META-INF\versions -Recurse -Filter module-info.class -ErrorAction SilentlyContinue | Remove-Item
Move-Item build\deps\META-INF\LICENSE build\deps\META-INF\LICENSE-FlatLaf.txt
Copy-Item -Recurse -Force build\deps\* build\classes\
Set-Content build\manifest.txt "Multi-Release: true`r`nEnable-Native-Access: ALL-UNNAMED`r`nImplementation-Version: $version`r`n" -Encoding ascii
$jar = "X32WingTool-$version.jar"
& "$bin\jar.exe" --create --file "build\jpkg\X32WingTool.jar" --manifest build\manifest.txt --main-class x32wing.Main -C build\classes .; Check 'jar'
Copy-Item build\jpkg\X32WingTool.jar "dist\$jar"
Write-Host "Built dist\$jar"

if ($Exe) {
    & "$bin\jpackage.exe" --type app-image --name X32WingTool --input build\jpkg --main-jar X32WingTool.jar `
        --main-class x32wing.Main --dest dist --app-version $version --vendor Audbol `
        --description "X32 / M32 / WING multitrack tool" --icon packaging\app.ico `
        --add-launcher x32wing=packaging\cli-launcher.properties `
        --java-options "-Xmx2g" --java-options "--enable-native-access=ALL-UNNAMED" --add-modules java.base,java.desktop,java.prefs
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
