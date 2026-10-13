param(
    [Parameter(Mandatory = $true, Position = 0)] [ValidateSet('prepare', 'build', 'verify', 'verify-classpath')] [string] $Command,
    [Parameter(ValueFromRemainingArguments = $true)] [string[]] $CliArguments
)
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path $PSScriptRoot -Parent
$repositoryDirectory = Split-Path $moduleDirectory -Parent
$report = Join-Path $moduleDirectory 'target/surefire-reports/TEST-com.nekoyu.Universe.AIChat.graph.LocalIconIndexTest.xml'
if (!(Test-Path -LiteralPath $report)) { throw '先运行 mvn -pl AIChat -am test，生成测试 classpath。' }
[xml]$testResult = Get-Content -LiteralPath $report -Raw -Encoding UTF8
$runtimeClasspath = ($testResult.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
if (!$runtimeClasspath) { throw '测试报告缺少 java.class.path。' }
if ($Command -eq 'build' -and $CliArguments -notcontains '--provider-plugin') {
    $providerJar = Join-Path $repositoryDirectory 'DashScopeAdapter/target/DashScopeAdapter-2.0-Phototaxis.jar'
    if (!(Test-Path -LiteralPath $providerJar)) { throw '先构建 DashScopeAdapter JAR，或明确指定 --provider-plugin。' }
    $CliArguments = @('--provider-plugin', $providerJar) + $CliArguments
}
Push-Location -LiteralPath $repositoryDirectory
try {
    & java '-Djava.awt.headless=true' -cp $runtimeClasspath com.nekoyu.Universe.AIChat.graph.GraphIconCli $Command @CliArguments
    if ($LASTEXITCODE -ne 0) { throw "图标 CLI 执行失败：$Command" }
} finally { Pop-Location }
