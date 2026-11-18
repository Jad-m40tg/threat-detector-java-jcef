param(
  [string]$Name = "Threat Detector (Java)"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "dist\ThreatDetector.jar"
$icon = Join-Path $root "dist\icon.ico"

if (-not (Test-Path $jar)) {
  throw "Missing $jar. Run build-release.bat first."
}

$javaw = (Get-Command javaw.exe -ErrorAction Stop).Source
$desktop = [Environment]::GetFolderPath("Desktop")
$linkPath = Join-Path $desktop "$Name.lnk"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($linkPath)
$shortcut.TargetPath = $javaw
$shortcut.Arguments = '-jar "' + $jar + '"'
$shortcut.WorkingDirectory = $root
$shortcut.Description = "Threat Detector (JavaFX, offline)"
if (Test-Path $icon) {
  $shortcut.IconLocation = "$icon,0"
}
$shortcut.Save()

Write-Output $linkPath
