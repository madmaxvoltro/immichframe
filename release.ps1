<#
  Build a new version and publish it to GitHub Releases.
  Every frame checks GitHub every 6 hours (or press "Check for updates" on the remote / POST /api/update)
  and installs it by itself.

    .\release.ps1                  # bump patch: 0.2.0 -> 0.2.1
    .\release.ps1 -Version 0.3.0   # explicit version
    .\release.ps1 -Notes "Fixed portrait pairing"
    .\release.ps1 -Install         # also install straight onto a USB/Wi-Fi-connected frame via adb
#>
param(
    [string]$Version,
    [string]$Notes = "",
    [switch]$Install
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path "keystore\frame.keystore")) { throw "keystore\frame.keystore missing - restore your backup, or updates won't install over the old app." }

# --- version
$props = Get-Content gradle.properties
$current = ($props | Where-Object { $_ -match '^appVersion=' }) -replace '^appVersion=', ''
if (-not $Version) {
    $p = $current.Split('.') | ForEach-Object { [int]$_ }
    $Version = "{0}.{1}.{2}" -f $p[0], $p[1], ($p[2] + 1)
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "Version must look like 1.2.3" }
Write-Host "Releasing $current -> $Version" -ForegroundColor Cyan
($props | ForEach-Object { if ($_ -match '^appVersion=') { "appVersion=$Version" } else { $_ } }) | Set-Content gradle.properties -Encoding ascii

# --- build
.\gradlew.bat assembleRelease --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "build failed" }
New-Item -ItemType Directory -Force dist | Out-Null
$apk = "dist\immichframe-v$Version.apk"
Copy-Item app\build\outputs\apk\release\app-release.apk $apk -Force

# --- git + GitHub release
git add -A
git commit -m "Release v$Version" | Out-Null
git tag "v$Version"
git push origin HEAD --tags
if (-not $Notes) { $Notes = "Immich Frame v$Version" }
gh release create "v$Version" $apk --title "v$Version" --notes $Notes
Write-Host "Published v$Version. Frames pick it up within 6 hours, or press 'Check for updates' on the remote." -ForegroundColor Green

# --- optional direct install
if ($Install) {
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    & $adb install -r $apk
    & $adb shell am start -n dev.immichframe/.SlideshowActivity
}
