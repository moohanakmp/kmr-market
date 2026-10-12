param(
    [switch]$Setup,
    [switch]$InstallStartup,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this launcher in Windows PowerShell on your MT5 laptop.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$localDirectory = Join-Path $env:LOCALAPPDATA 'KMR-Market'
$configPath = Join-Path $localDirectory 'local-demo.json'
$pidPath = Join-Path $localDirectory 'workers.json'
$taskName = 'KMR Local Demo'
New-Item -ItemType Directory -Force $localDirectory | Out-Null

if ($Stop) {
    if (Test-Path $pidPath) {
        $saved = Get-Content $pidPath -Raw | ConvertFrom-Json
        foreach ($worker in $saved.workers) {
            $running = Get-CimInstance Win32_Process -Filter "ProcessId=$($worker.id)" -ErrorAction SilentlyContinue
            if ($running -and $running.CommandLine -and $running.CommandLine.Contains([string]$worker.script)) {
                Stop-Process -Id $worker.id -ErrorAction SilentlyContinue
            }
        }
    }
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($saved -and $saved.parentId -and $saved.parentId -ne $PID) {
        $parent = Get-Process -Id $saved.parentId -ErrorAction SilentlyContinue
        if ($parent -and $parent.StartTime.ToUniversalTime().Ticks -eq [long]$saved.parentStartedTicks) {
            Stop-Process -Id $parent.Id -ErrorAction SilentlyContinue
        }
    }
    Write-Host 'Scheduled launcher and its recorded Python workers stopped. Broker SL, final TP and pending expiry remain.'
    return
}

if ($Setup -or -not (Test-Path $configPath)) {
    $accountNumber = [long](Read-Host 'Enter your MT5 DEMO account number (digits only)')
    if ($accountNumber -le 0) { throw 'Account number must be positive.' }
    $server = Read-Host 'MT5 server (press Enter for OctaFX-Demo)'
    if (-not $server) { $server = 'OctaFX-Demo' }
    if ($server -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Enter the exact broker server name.' }
    $terminal = Get-Process terminal64 -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Path
    if ($terminal) { Write-Host "Detected MT5: $terminal" }
    $chosen = Read-Host 'Full terminal64.exe path (press Enter to use detected path)'
    if ($chosen) { $terminal = $chosen.Trim('"') }
    if (-not $terminal -or -not (Test-Path $terminal -PathType Leaf)) { throw 'Open MT5 first or enter its valid terminal64.exe path.' }
    $python = Join-Path $projectRoot '.venv-mt5\Scripts\python.exe'
    if (-not (Test-Path $python)) { throw 'Create .venv-mt5 and install bridge/requirements.txt first.' }
    $secret = Read-Host 'Enter Render MT5_FEED_PASSWORD (hidden, not the broker password)' -AsSecureString
    $configuration = [ordered]@{
        account=$accountNumber; server=$server; url='https://kmr-market.onrender.com'; offset=3
        terminal=$terminal; project=$projectRoot; protectedFeedPassword=($secret | ConvertFrom-SecureString)
    }
    $configuration | ConvertTo-Json | Set-Content -Encoding UTF8 $configPath
    $acl = Get-Acl $configPath
    $acl.SetAccessRuleProtection($true, $false)
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl.SetAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')))
    Set-Acl -Path $configPath -AclObject $acl
    Write-Host 'Saved the feed password encrypted for this Windows user and machine. Broker credentials stay in MT5.'
}
$config = Get-Content $configPath -Raw | ConvertFrom-Json
if ($InstallStartup) {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $script = Join-Path $PSScriptRoot 'start_local.ps1'
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy RemoteSigned -File `"$script`"" -WorkingDirectory $config.project
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $identity
    $principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName
    Write-Host 'KMR Local Demo starts now and at Windows sign-in. Keep the laptop awake and MT5 logged into the configured demo account.'
    return
}

$mutex = New-Object System.Threading.Mutex($false, "Local\KMR-Market-$($config.account)")
if (-not $mutex.WaitOne(0)) { Write-Host 'KMR launcher is already running.'; $mutex.Dispose(); return }
$children = @{}
$oldFeedPassword = $env:MT5_FEED_PASSWORD
$pointer = [IntPtr]::Zero
try {
    $secure = ConvertTo-SecureString $config.protectedFeedPassword
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $env:MT5_FEED_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $python = Join-Path $config.project '.venv-mt5\Scripts\python.exe'
    if (-not (Test-Path $python)) { throw 'Configured Python environment is missing; rerun setup from the correct project folder.' }
    Write-Host 'Starting DEMO data feed, execution and P&L sync. No broker password is sent to KMR.'
    while ($true) {
        $terminalRunning = @(Get-Process terminal64 -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $config.terminal })
        if ($terminalRunning.Count -eq 0) { Start-Process -FilePath $config.terminal | Out-Null; Start-Sleep -Seconds 10 }
        foreach ($name in @('mt5_bridge', 'mt5_executor')) {
            if (-not $children[$name] -or $children[$name].HasExited) {
                $workerScript = Join-Path $config.project "bridge\$name.py"
                $arguments = @('-u', "`"$workerScript`"", '--url', $config.url, '--broker-utc-offset-hours', [string]$config.offset, '--terminal', "`"$($config.terminal)`"")
                if ($name -eq 'mt5_executor') { $arguments += @('--execute-demo', '--account', [string]$config.account, '--server', [string]$config.server) }
                $children[$name] = Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $config.project -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $localDirectory "$name.log") -RedirectStandardError (Join-Path $localDirectory "$name-error.log")
            }
        }
        $workers = @($children.GetEnumerator() | ForEach-Object { @{ id=$_.Value.Id; script=(Join-Path $config.project "bridge\$($_.Key).py") } })
        @{ workers=$workers; parentId=$PID; parentStartedTicks=(Get-Process -Id $PID).StartTime.ToUniversalTime().Ticks } | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 $pidPath
        Start-Sleep -Seconds 30
    }
} finally {
    foreach ($process in $children.Values) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue } }
    $env:MT5_FEED_PASSWORD = $oldFeedPassword
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    $mutex.ReleaseMutex(); $mutex.Dispose()
}
