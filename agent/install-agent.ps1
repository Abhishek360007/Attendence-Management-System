<#
.SYNOPSIS
    Installs the attendance sync agent as a Windows service on the PC that runs ZKTeco software.

.DESCRIPTION
    The agent is the same jar as the cloud API, run with SYNC_ENABLED=true. It reads new punches from the
    ZKTeco Access database (ATT2000.MDB) and writes them to PostgreSQL (Neon). The service starts with
    Windows and restarts itself if it crashes.

    Run from an elevated (Administrator) PowerShell. Use -TestOnly first to check your settings: it runs the
    agent for one sync and reports the result without installing anything (no Administrator needed).

.EXAMPLE
    .\install-agent.ps1 -MdbPath "C:\Program Files (x86)\ZKTeco\att2000.mdb" `
        -DbUrl "jdbc:postgresql://ep-xxx-pooler.ap-southeast-1.aws.neon.tech/neondb?sslmode=require" `
        -DbUsername neondb_owner -TestOnly

.EXAMPLE
    # Database file on another PC: use a UNC path and a Windows account that can read the share.
    .\install-agent.ps1 -MdbPath "\\OFFICE-PC\ZKTeco\att2000.mdb" -DbUrl "..." -DbUsername neondb_owner `
        -ServiceAccount (Get-Credential OFFICE\attendance-svc)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $MdbPath,
    [Parameter(Mandatory)] [string] $DbUrl,
    [Parameter(Mandatory)] [string] $DbUsername,
    [SecureString] $DbPassword,
    [string] $InstallDir = "C:\AttendanceAgent",
    [string] $JarPath,
    [string] $SyncCron = "0 */1 * * * *",
    [int] $Port = 9010,
    [PSCredential] $ServiceAccount,
    [switch] $TestOnly
)

$ErrorActionPreference = "Stop"
$ServiceId = "AttendanceAgent"
$WinSwUrl = "https://github.com/winsw/winsw/releases/download/v2.13.0/WinSW-x64.exe_Release"
$WinSwSha256 = "286C5E550C606593D249BB70599BE5C9013573ACB290693C3717DA690BAB8574"
$MinJava = 25
$RepoRoot = Split-Path $PSScriptRoot -Parent

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

function Get-JavaMajor([string] $javaExe) {
    $out = & cmd /c "`"$javaExe`" -version 2>&1"
    if ("$out" -match 'version "(\d+)') { return [int] $Matches[1] }
    return 0
}

function Find-Java {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME "bin\java.exe" }
    $candidates += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($c in $candidates) {
        if ((Test-Path $c) -and (Get-JavaMajor $c) -ge $MinJava) { return (Resolve-Path $c).Path }
    }
    return $null
}

function Assert-Admin {
    $principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Fail "Run this from an Administrator PowerShell (right-click PowerShell > Run as administrator), or add -TestOnly to just check the settings."
    }
}

function ConvertTo-PlainText([SecureString] $secure) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

function Wait-Health([int] $seconds, $process) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ($process -and $process.HasExited) { return $false }
        try {
            $h = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3
            if ($h.status -eq "UP") { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Show-SyncResult {
    try {
        $s = Invoke-RestMethod "http://127.0.0.1:$Port/api/sync/status" -TimeoutSec 10
        $latest = if ($s.latestPunchAt) { ($s.latestPunchAt -replace 'T', ' ') } else { "none" }
        Write-Host ("    Punches in database: {0}   latest punch: {1}" -f $s.totalRecords, $latest)
        if ($s.lastRun) {
            Write-Host ("    Last sync: {0}, {1} new punch(es) {2}" -f $s.lastRun.status, $s.lastRun.recordsSynced, $s.lastRun.message)
        }
        return $s
    } catch {
        Write-Host "    Could not read sync status: $($_.Exception.Message)" -ForegroundColor Yellow
        return $null
    }
}

# ---------------------------------------------------------------------------------------------------------
if (-not $TestOnly) { Assert-Admin }

Step "Checking the ZKTeco database"
if (-not (Test-Path -LiteralPath $MdbPath -PathType Leaf)) {
    if ($ServiceAccount) {
        Write-Host "    $MdbPath is not visible to you; continuing because the service runs as $($ServiceAccount.UserName)." -ForegroundColor Yellow
    } else {
        Fail "File not found: $MdbPath. ZKTime / ZKAccess usually keeps it in its install folder as att2000.mdb."
    }
}
if ($MdbPath -match '^[A-Za-z]:' -and -not $TestOnly) {
    $drive = Get-PSDrive -Name $MdbPath.Substring(0, 1) -ErrorAction SilentlyContinue
    if ($drive -and $drive.DisplayRoot -like '\\*') {
        Fail "$($MdbPath.Substring(0,2)) is a mapped network drive, which services cannot see. Use the UNC path ($($drive.DisplayRoot)\...) and -ServiceAccount."
    }
}
$MdbForJdbc = $MdbPath -replace '\\', '/'

if ($DbUrl -notmatch '^jdbc:postgresql://') {
    Fail "DbUrl must look like jdbc:postgresql://<host>/<database>?sslmode=require (not postgresql://user:pass@...)."
}
if (-not $DbPassword) { $DbPassword = Read-Host "Database password for $DbUsername" -AsSecureString }
$plainPassword = ConvertTo-PlainText $DbPassword
if (-not $plainPassword) { Fail "A database password is required." }

Step "Checking Java $MinJava+"
$java = Find-Java
if (-not $java) {
    Write-Host "    Not found. Installing Eclipse Temurin JDK $MinJava with winget..."
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Fail "winget is not available. Install Java $MinJava from https://adoptium.net and run this script again."
    }
    winget install --id "EclipseAdoptium.Temurin.$MinJava.JDK" --exact --silent --accept-package-agreements --accept-source-agreements
    $java = Find-Java
    if (-not $java) { Fail "Java $MinJava was not found after installing. Install it from https://adoptium.net and retry." }
}
Write-Host "    $java"

Step "Locating the application jar"
if (-not $JarPath) { $JarPath = Join-Path $RepoRoot "target\attendance-management-system.jar" }
if (-not (Test-Path $JarPath)) {
    $mvnw = Join-Path $RepoRoot "mvnw.cmd"
    if (-not (Test-Path $mvnw)) { Fail "Jar not found at $JarPath. Pass -JarPath, or run this script from inside the project folder." }
    Write-Host "    Not built yet. Building with the Maven wrapper (first build downloads dependencies)..."
    $env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent
    Push-Location $RepoRoot
    try { & $mvnw -B -q -DskipTests package; if ($LASTEXITCODE -ne 0) { Fail "Build failed." } }
    finally { Pop-Location }
}
$JarPath = (Resolve-Path $JarPath).Path
Write-Host "    $JarPath"

$settings = [ordered]@{
    DB_URL          = $DbUrl
    DB_USERNAME     = $DbUsername
    DB_PASSWORD     = $plainPassword
    DB_POOL_SIZE    = "2"
    ZKTECO_MDB_PATH = $MdbForJdbc
    SYNC_ENABLED    = "true"
    SYNC_CRON       = $SyncCron
    SERVER_PORT     = "$Port"
    # The agent's own dashboard/API is reachable from this PC only.
    SERVER_ADDRESS  = "127.0.0.1"
    SCHEMA_INIT     = "always"
}

# ---------------------------------------------------------------------------------------------------------
if ($TestOnly) {
    Step "Test run: starting the agent for one sync (nothing is installed)"
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) { Fail "Port $Port is in use (is the agent service already running?). Pass -Port with a free port." }
    $log = Join-Path ([IO.Path]::GetTempPath()) "attendance-agent-test.log"
    foreach ($k in $settings.Keys) { Set-Item "env:$k" $settings[$k] }
    $env:SYNC_CRON = "*/5 * * * * *"
    $proc = Start-Process -FilePath $java -ArgumentList @("-jar", "`"$JarPath`"") -RedirectStandardOutput $log `
        -RedirectStandardError "$log.err" -WindowStyle Hidden -PassThru
    try {
        if (-not (Wait-Health 90 $proc)) {
            Get-Content $log -Tail 25 -ErrorAction SilentlyContinue
            Fail "The agent did not start. See the log above (full log: $log)."
        }
        Write-Host "    Connected to the database."
        Start-Sleep -Seconds 12
        $status = Show-SyncResult
        if ($status -and $status.lastRun -and $status.lastRun.status -eq "FAILED") {
            Fail "Sync failed. Check -MdbPath and that this account can read the file."
        }
        Write-Host ""
        Write-Host "Settings work. Run the same command without -TestOnly, in an Administrator PowerShell, to install the service." -ForegroundColor Green
    } finally {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        foreach ($k in $settings.Keys) { Remove-Item "env:$k" -ErrorAction SilentlyContinue }
    }
    exit 0
}

# ---------------------------------------------------------------------------------------------------------
Step "Preparing $InstallDir"
$exe = Join-Path $InstallDir "$ServiceId.exe"
if (Get-Service $ServiceId -ErrorAction SilentlyContinue) {
    Write-Host "    Existing service found; replacing it."
    & $exe stop 2>$null | Out-Null
    Start-Sleep -Seconds 3
    & $exe uninstall | Out-Null
    Start-Sleep -Seconds 2
}
New-Item -ItemType Directory -Force -Path $InstallDir, (Join-Path $InstallDir "logs") | Out-Null
Copy-Item $JarPath (Join-Path $InstallDir "app.jar") -Force

if (-not (Test-Path $exe) -or (Get-FileHash $exe -Algorithm SHA256).Hash -ne $WinSwSha256) {
    Write-Host "    Downloading WinSW service wrapper..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $tmp = "$exe.download"
    Invoke-WebRequest $WinSwUrl -OutFile $tmp -UseBasicParsing
    $hash = (Get-FileHash $tmp -Algorithm SHA256).Hash
    if ($hash -ne $WinSwSha256) { Remove-Item $tmp -Force; Fail "WinSW download checksum mismatch ($hash). Not installing." }
    Move-Item $tmp $exe -Force
}

Step "Writing service configuration"
$esc = { param($s) [Security.SecurityElement]::Escape($s) }
$envXml = ($settings.Keys | ForEach-Object { "  <env name=`"$_`" value=`"$(& $esc $settings[$_])`" />" }) -join "`r`n"
$accountXml = ""
if ($ServiceAccount) {
    $accountXml = @"
  <serviceaccount>
    <username>$(& $esc $ServiceAccount.UserName)</username>
    <password>$(& $esc (ConvertTo-PlainText $ServiceAccount.Password))</password>
    <allowservicelogon>true</allowservicelogon>
  </serviceaccount>
"@
}
$xml = @"
<service>
  <id>$ServiceId</id>
  <name>Attendance Sync Agent</name>
  <description>Copies ZKTeco punches from $(& $esc $MdbPath) to PostgreSQL.</description>
  <executable>$(& $esc $java)</executable>
  <arguments>-XX:MaxRAMPercentage=25 -jar "%BASE%\app.jar"</arguments>
  <workingdirectory>%BASE%</workingdirectory>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <stoptimeout>30 sec</stoptimeout>
  <onfailure action="restart" delay="10 sec" />
  <onfailure action="restart" delay="30 sec" />
  <onfailure action="restart" delay="60 sec" />
  <resetfailure>1 hour</resetfailure>
  <logpath>%BASE%\logs</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>5</keepFiles>
  </log>
$envXml
$accountXml
</service>
"@
$xmlPath = Join-Path $InstallDir "$ServiceId.xml"
[IO.File]::WriteAllText($xmlPath, $xml, (New-Object Text.UTF8Encoding($false)))

# The XML holds the database password: only Administrators, SYSTEM and the service account may read the folder.
$grants = @("*S-1-5-32-544:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F")
if ($ServiceAccount) { $grants += "$($ServiceAccount.UserName):(OI)(CI)M" }
& icacls $InstallDir /inheritance:r /grant:r $grants | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Could not restrict permissions on $InstallDir." }

Step "Installing and starting the service"
& $exe install
if ($LASTEXITCODE -ne 0) { Fail "Service install failed." }
& $exe start
if ($LASTEXITCODE -ne 0) { Fail "Service start failed. See $InstallDir\logs." }

if (Wait-Health 120) {
    Start-Sleep -Seconds 5
    Show-SyncResult | Out-Null
    Write-Host ""
    Write-Host "Installed. The agent starts with Windows and syncs on schedule '$SyncCron'." -ForegroundColor Green
    Write-Host "  Local dashboard: http://127.0.0.1:$Port/"
    Write-Host "  Logs:            $InstallDir\logs"
    Write-Host "  Remove with:     .\uninstall-agent.ps1"
} else {
    Write-Host "The service is installed but did not report healthy within 2 minutes. Check $InstallDir\logs." -ForegroundColor Yellow
    exit 1
}
