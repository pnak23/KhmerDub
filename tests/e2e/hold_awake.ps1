# Test-only: keep an unattended PC awake while end-to-end scenarios run.
# Without it, Windows (Modern Standby) sleeps within a second of the product releasing its keep-awake request, which
# freezes the test harness itself (seen in S12/S15 on 2026-11-16). This process is powershell.exe, so the scenarios'
# check for the PRODUCT's request (a python.exe entry under SYSTEM in `powercfg /requests`) is unaffected.
# Requests ES_SYSTEM_REQUIRED only (never the display). Ends when -StopFile exists or after -MaxMinutes.
param([Parameter(Mandatory = $true)][string]$StopFile, [int]$MaxMinutes = 240)
$sig = '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
$k = Add-Type -MemberDefinition $sig -Name 'TestKeepAwake' -Namespace 'KhmerDubE2E' -PassThru
$ES_CONTINUOUS = [uint32]2147483648
$ES_SYSTEM_REQUIRED = [uint32]1
[void]$k::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED)
$end = (Get-Date).AddMinutes($MaxMinutes)
try {
    while ((Get-Date) -lt $end -and -not (Test-Path $StopFile)) {
        Start-Sleep -Seconds 2
    }
} finally {
    [void]$k::SetThreadExecutionState($ES_CONTINUOUS)
}
