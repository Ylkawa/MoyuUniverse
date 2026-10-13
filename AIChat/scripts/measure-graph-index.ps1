param([string]$ProviderId='DashScope', [string]$ProviderPlugin, [ValidateRange(128,16384)][int]$HeapMiB=1024)
$ErrorActionPreference = 'Stop'
$graphRepo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if(!$ProviderPlugin){ $ProviderPlugin = Join-Path $graphRepo 'DashScopeAdapter/target/DashScopeAdapter-2.0-Phototaxis.jar' }
$graphLog = Join-Path $graphRepo 'AIChat/target/graph-jar-acceptance.log'
[xml]$graphTestReport = Get-Content (Join-Path $graphRepo 'AIChat/target/surefire-reports/TEST-com.nekoyu.Universe.AIChat.graph.LocalIconIndexTest.xml') -Raw -Encoding UTF8
$graphDeps = (($graphTestReport.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value.Split(';') | Where-Object { $_ -notmatch '[\\/]AIChat[\\/]target[\\/]classes$' }) -join ';'
$graphCp = (Join-Path $graphRepo 'AIChat/target/AIChat-2.0-Phototaxis.jar') + ';' + (Join-Path $graphRepo 'AIChat/target/test-classes') + ';' + (Join-Path $graphRepo 'Universe/target/Universe-2.0-Phototaxis.jar') + ';' + $graphDeps
$graphStart = [Diagnostics.ProcessStartInfo]::new()
$graphStart.FileName = (Get-Command java).Source
$graphStart.WorkingDirectory = $graphRepo
$graphStart.UseShellExecute = $false
$graphStart.CreateNoWindow = $true
$graphStart.RedirectStandardInput = $true
$graphStart.RedirectStandardOutput = $true
$graphStart.RedirectStandardError = $true
$graphStart.StandardOutputEncoding = [Text.Encoding]::UTF8
$graphStart.StandardErrorEncoding = [Text.Encoding]::UTF8
foreach($arg in @('-Xms32m',('-Xmx'+$HeapMiB+'m'),'-Djava.awt.headless=true','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8','-cp',$graphCp,'com.nekoyu.Universe.AIChat.graph.GraphJarAcceptance',$ProviderId,'--provider-plugin',$ProviderPlugin,'--pause')){ $graphStart.ArgumentList.Add($arg) }
# Provider 从工作目录的 JSON 配置读取密钥；已有缓存可以在 APIKey 为空时验收。
$graphProcess = [Diagnostics.Process]::new()
$graphProcess.StartInfo = $graphStart
$null = $graphProcess.Start()
$graphLines = [Collections.Generic.List[string]]::new()
$graphFirst = $graphProcess.StandardOutput.ReadLine()
if($graphFirst -ne 'READY'){ throw 'Acceptance did not reach READY' }
$graphLines.Add($graphFirst)
$graphProcess.Refresh()
$graphBaseline = $graphProcess.WorkingSet64
$graphProcess.StandardInput.WriteLine('run')
while(($graphLine = $graphProcess.StandardOutput.ReadLine()) -ne $null){
    $graphLines.Add($graphLine)
    if($graphLine -eq 'DONE'){ break }
}
if($graphLines.Contains('DONE')){
    $graphProcess.Refresh()
    $graphEnd = $graphProcess.WorkingSet64
    $graphPeak = $graphProcess.PeakWorkingSet64
    $graphProcess.StandardInput.WriteLine('exit')
}
if(!$graphProcess.WaitForExit(10000)){ $graphProcess.Kill(); throw 'Acceptance did not exit' }
$graphErrors = $graphProcess.StandardError.ReadToEnd()
[IO.File]::WriteAllLines($graphLog,$graphLines,[Text.Encoding]::UTF8)
if($graphErrors){ [IO.File]::AppendAllText($graphLog,$graphErrors,[Text.Encoding]::UTF8) }
if($graphProcess.ExitCode -ne 0 -or !$graphLines.Contains('DONE')){ Get-Content -LiteralPath $graphLog -Tail 25; throw 'Acceptance failed' }
$graphReport = [PSCustomObject]@{BaselineMiB=$graphBaseline/1MB;EndMiB=$graphEnd/1MB;PeakMiB=$graphPeak/1MB;IncrementMiB=($graphPeak-$graphBaseline)/1MB;HeapLimitMiB=$HeapMiB;JarBytes=(Get-Item (Join-Path $graphRepo 'AIChat/target/AIChat-2.0-Phototaxis.jar')).Length}
$graphReport | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $graphRepo 'AIChat/target/graph-jar-memory.json') -Encoding UTF8
Get-Content -LiteralPath $graphLog -Tail 17
$graphReport
