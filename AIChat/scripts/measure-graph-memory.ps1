param([int]$HeapMiB = 192)
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path $PSScriptRoot -Parent
$repositoryDirectory = Split-Path $moduleDirectory -Parent
$testReport = Join-Path $moduleDirectory 'target/surefire-reports/TEST-com.nekoyu.Universe.AIChat.graph.GraphRenderingTest.xml'
if (!(Test-Path -LiteralPath $testReport)) { throw 'Run mvn -pl AIChat -am test first.' }
[xml]$testResult = Get-Content -LiteralPath $testReport -Raw
$dependencyClasspath = ($testResult.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
if (!$dependencyClasspath) { throw 'Test report does not contain the JVM classpath.' }
$classpath = (Join-Path $moduleDirectory 'target/test-classes') + ';' + (Join-Path $moduleDirectory 'target/classes') + ';' + (Join-Path $repositoryDirectory 'Universe/target/classes') + ';' + $dependencyClasspath
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = 'java'
$start.Arguments = '-Xms32m -Xmx' + $HeapMiB + 'm -Djava.awt.headless=true -cp "' + $classpath + '" com.nekoyu.Universe.AIChat.graph.GraphRenderMemoryProbe'
$start.WorkingDirectory = $moduleDirectory
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $start
try {
    [void]$process.Start()
    $errorOutput = $process.StandardError.ReadToEndAsync()
    $ready = $process.StandardOutput.ReadLine()
    if ($ready -notmatch '^READY ([0-9]+)$') { throw "Memory probe did not reach READY: $ready" }
    # Windows java.exe may be a launcher shim. Measure the PID reported by the JVM itself.
    $jvmProcess = [System.Diagnostics.Process]::GetProcessById([int]$Matches[1])
    $jvmProcess.Refresh()
    $baseline = $jvmProcess.WorkingSet64
    $peak = $baseline
    $remainingOutput = $process.StandardOutput.ReadToEndAsync()
    $process.StandardInput.WriteLine('render')
    $process.StandardInput.Flush()
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    while (!$process.HasExited) {
        if (!$jvmProcess.HasExited) {
            $jvmProcess.Refresh()
            $peak = [Math]::Max($peak, $jvmProcess.PeakWorkingSet64)
        }
        if ([DateTime]::UtcNow -gt $deadline) { $process.Kill(); throw 'Memory probe timed out.' }
        Start-Sleep -Milliseconds 100
    }
    $process.WaitForExit()
    $output = $remainingOutput.GetAwaiter().GetResult()
    $errors = $errorOutput.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "Memory probe failed: $output $errors" }
    $report = [ordered]@{
        timestamp = [DateTime]::UtcNow.ToString('o')
        heapLimitMiB = $HeapMiB
        baselineWorkingSetMiB = [Math]::Round($baseline / 1MB, 2)
        peakWorkingSetMiB = [Math]::Round($peak / 1MB, 2)
        addedWorkingSetMiB = [Math]::Round(($peak - $baseline) / 1MB, 2)
        probeOutput = $output.Trim()
    }
    $json = $report | ConvertTo-Json
    $json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $moduleDirectory 'target/graph-memory-probe/report.json')
    $json
} finally {
    if ($process.Id -and !$process.HasExited) { $process.Kill() }
    if ($jvmProcess) { $jvmProcess.Dispose() }
    $process.Dispose()
}
