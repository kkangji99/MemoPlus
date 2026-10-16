# QuickMemo release build
#   1) bump VERSION in src\QuickMemo.java
#   2) .\release.ps1          -> dist\QuickMemo-<ver>-windows-x64.zip  (QuickMemo.exe + bundled Java runtime)
#   3) create GitHub release tag v<ver> and attach the zip  (or: .\release.ps1 -Publish  with gh CLI)
param(
    [string]$Version,      # override VERSION (testing only)
    [switch]$Publish       # upload to GitHub release with gh CLI
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$src = Get-Content src\QuickMemo.java -Raw -Encoding UTF8
$srcVersion = [regex]::Match($src, 'VERSION = "([0-9.]+)"').Groups[1].Value
if (-not $Version) { $Version = $srcVersion }
$repo = [regex]::Match($src, 'UPDATE_REPO = "([^"]+)"').Groups[1].Value
Write-Host "QuickMemo $Version  (repo $repo)"

Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory build\src, build\classes, build\jar | Out-Null
$src = $src -replace 'VERSION = "[0-9.]+"', "VERSION = `"$Version`""
[IO.File]::WriteAllText("$PWD\build\src\QuickMemo.java", $src, (New-Object Text.UTF8Encoding $false))

$flatlaf = "$PWD\lib\flatlaf-3.5.4.jar"     # modern look and feel, bundled into the jar
javac -encoding UTF-8 -cp $flatlaf -d build\classes build\src\QuickMemo.java
if ($LASTEXITCODE) { throw 'javac failed' }
Push-Location build\classes; jar xf $flatlaf; Pop-Location
if ($LASTEXITCODE) { throw 'flatlaf extract failed' }
Remove-Item build\classes\META-INF -Recurse -Force -ErrorAction SilentlyContinue
Set-Content build\manifest.txt 'Main-Class: QuickMemo' -Encoding ascii
jar cfm build\jar\QuickMemo.jar build\manifest.txt -C build\classes .
if ($LASTEXITCODE) { throw 'jar failed' }

jpackage --type app-image --name QuickMemo --app-version $Version `
    --vendor QuickMemo --description 'QuickMemo sticky notes' --icon QuickMemo.ico `
    --input build\jar --main-jar QuickMemo.jar --main-class QuickMemo --dest build\image `
    --add-modules 'java.desktop,java.logging,java.net.http,jdk.crypto.ec,jdk.localedata,jdk.charsets' `
    --jlink-options '--strip-debug --no-man-pages --no-header-files --include-locales=en,ko' `
    --java-options '-Dsun.java2d.uiScale.enabled=true'
if ($LASTEXITCODE) { throw 'jpackage failed' }

New-Item -ItemType Directory dist -Force | Out-Null
# asset name must end with -windows-x64.zip (the updater looks for it; 1.0.0 builds look for -win64.zip and are skipped on purpose)
$zip = "dist\QuickMemo-$Version-windows-x64.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory("$PWD\build\image\QuickMemo", "$PWD\$zip", [IO.Compression.CompressionLevel]::Optimal, $true)
Write-Host "Built: $zip  ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

if ($Publish) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue) -and (Test-Path 'C:\Program Files\GitHub CLI\gh.exe')) { $env:Path += ';C:\Program Files\GitHub CLI' }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'gh CLI not found. Upload the zip on the GitHub releases page instead.' }
    gh release create "v$Version" $zip --repo $repo --title "QuickMemo $Version" --notes "QuickMemo $Version"
}
