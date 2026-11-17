<#
.SYNOPSIS
    Stops and removes the attendance sync agent service. Run from an Administrator PowerShell.
.PARAMETER KeepFiles
    Leave the install folder (jar, configuration, logs) in place.
#>
[CmdletBinding()]
param(
    [string] $InstallDir = "C:\AttendanceAgent",
    [switch] $KeepFiles
)

$ErrorActionPreference = "Stop"
$ServiceId = "AttendanceAgent"
$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: run this from an Administrator PowerShell." -ForegroundColor Red; exit 1
}

$exe = Join-Path $InstallDir "$ServiceId.exe"
if (Get-Service $ServiceId -ErrorAction SilentlyContinue) {
    if (Test-Path $exe) {
        & $exe stop 2>$null | Out-Null
        Start-Sleep -Seconds 3
        & $exe uninstall
    } else {
        Stop-Service $ServiceId -Force -ErrorAction SilentlyContinue
        & sc.exe delete $ServiceId | Out-Null
    }
    Write-Host "Service removed."
} else {
    Write-Host "Service $ServiceId is not installed."
}

if (-not $KeepFiles -and (Test-Path $InstallDir)) {
    Remove-Item $InstallDir -Recurse -Force
    Write-Host "Deleted $InstallDir (it contained the database password)."
}
