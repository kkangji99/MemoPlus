# MemoPlus release build
#   1) bump VERSION in src\MemoPlus.java
#   2) .\release.ps1          -> dist\MemoPlus-<ver>-windows-x64.zip  (MemoPlus.exe + bundled Java runtime)
#   3) create GitHub release tag v<ver> and attach the zip  (or: .\release.ps1 -Publish  with gh CLI)
param(
    [string]$Version,      # override VERSION (testing only)
    [switch]$Publish       # upload to GitHub release with gh CLI
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$src = Get-Content src\MemoPlus.java -Raw -Encoding UTF8
$srcVersion = [regex]::Match($src, 'VERSION = "([0-9.]+)"').Groups[1].Value
if (-not $Version) { $Version = $srcVersion }
$repo = [regex]::Match($src, 'UPDATE_REPO = "([^"]+)"').Groups[1].Value
Write-Host "MemoPlus $Version  (repo $repo)"

Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory build\src, build\classes, build\jar | Out-Null
Copy-Item src\*.java build\src
$src = $src -replace 'VERSION = "[0-9.]+"', "VERSION = `"$Version`""
[IO.File]::WriteAllText("$PWD\build\src\MemoPlus.java", $src, (New-Object Text.UTF8Encoding $false))

$flatlaf = "$PWD\lib\flatlaf-3.5.4.jar"     # modern look and feel, bundled into the jar
javac -encoding UTF-8 -cp $flatlaf -d build\classes (Get-ChildItem build\src\*.java | ForEach-Object FullName)
if ($LASTEXITCODE) { throw 'javac failed' }
# global hotkey: JDK 21 foreign function API (preview), compiled on its own; the app runs with --enable-preview
# (through cmd: javac prints a preview-feature note on stderr, which PowerShell 5 treats as an error under 'Stop')
cmd /c "javac --release 21 --enable-preview -encoding UTF-8 -d build\classes src\hotkey\MemoPlusHotkey.java 2>nul"
if ($LASTEXITCODE) { throw 'javac hotkey failed' }
Push-Location build\classes; jar xf $flatlaf; Pop-Location
if ($LASTEXITCODE) { throw 'flatlaf extract failed' }
Remove-Item build\classes\META-INF -Recurse -Force -ErrorAction SilentlyContinue
Set-Content build\manifest.txt 'Main-Class: MemoPlus' -Encoding ascii
jar cfm build\jar\MemoPlus.jar build\manifest.txt -C build\classes .
if ($LASTEXITCODE) { throw 'jar failed' }

# QuickMemo.exe: a second launcher for the same app. Updaters of 1.0.x (QuickMemo) look for QuickMemo.exe
# in the downloaded zip and restart it, so keeping it lets old installs update to MemoPlus.
Set-Content build\legacy-launcher.properties 'description=MemoPlus' -Encoding ascii
jpackage --type app-image --name MemoPlus --app-version $Version `
    --vendor MemoPlus --description 'MemoPlus sticky notes' --icon MemoPlus.ico `
    --input build\jar --main-jar MemoPlus.jar --main-class MemoPlus --dest build\image `
    --add-launcher "QuickMemo=$PWD\build\legacy-launcher.properties" `
    --add-modules 'java.desktop,java.logging,java.net.http,jdk.crypto.ec,jdk.localedata,jdk.charsets' `
    --jlink-options '--strip-debug --no-man-pages --no-header-files --include-locales=en,ko' `
    --java-options '-Dsun.java2d.uiScale.enabled=true --enable-preview --enable-native-access=ALL-UNNAMED'
if ($LASTEXITCODE) { throw 'jpackage failed' }

New-Item -ItemType Directory dist -Force | Out-Null
# asset name must end with -windows-x64.zip (the updater looks for it; 1.0.0 builds look for -win64.zip and are skipped on purpose)
$zip = "dist\MemoPlus-$Version-windows-x64.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory("$PWD\build\image\MemoPlus", "$PWD\$zip", [IO.Compression.CompressionLevel]::Optimal, $true)
Write-Host "Built: $zip  ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

if ($Publish) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue) -and (Test-Path 'C:\Program Files\GitHub CLI\gh.exe')) { $env:Path += ';C:\Program Files\GitHub CLI' }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'gh CLI not found. Upload the zip on the GitHub releases page instead.' }
    if (-not $env:RELEASE_REPO) { $env:RELEASE_REPO = $repo }   # before the GitHub rename, publish to the old repo name
    gh release create "v$Version" $zip --repo $env:RELEASE_REPO --title "MemoPlus $Version" --notes "MemoPlus $Version"
}
