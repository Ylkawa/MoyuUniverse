$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path $PSScriptRoot -Parent
$testReport = Join-Path $moduleDirectory 'target/surefire-reports/TEST-com.nekoyu.Universe.AIChat.graph.GraphRenderingTest.xml'
if (!(Test-Path -LiteralPath $testReport)) { throw 'Run mvn -pl AIChat -am test first.' }
[xml]$testResult = Get-Content -Encoding UTF8 -LiteralPath $testReport -Raw
$exampleClasspath = ($testResult.testsuite.properties.property | Where-Object { $_.name -eq 'java.class.path' }).value
if (!$exampleClasspath) { throw 'Test report does not contain the JVM classpath.' }
Push-Location -LiteralPath $moduleDirectory
try {
    & java '-Djava.awt.headless=true' -cp $exampleClasspath com.nekoyu.Universe.AIChat.graph.GraphExampleGenerator
    if ($LASTEXITCODE -ne 0) { throw 'Graph example generation failed.' }
} finally { Pop-Location }
