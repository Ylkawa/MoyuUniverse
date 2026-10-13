param(
    [string] $Maven = 'mvn.cmd',
    [string] $Java = 'java',
    [string] $ReleaseId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string] $LocalRepository,
    [switch] $Offline
)
$ErrorActionPreference = 'Stop'
if ($ReleaseId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'ReleaseId 只能包含字母、数字、点、下划线和连字符。' }
$repository = Split-Path $PSScriptRoot -Parent
$release = Join-Path $repository ('target/deployment-' + $ReleaseId)
if (Test-Path -LiteralPath $release) { throw "发行目录已存在，请使用新的 ReleaseId：$release" }
$modules = @('Universe', 'AIChat', 'OnebotAdapter', 'openaiadapter', 'DashScopeAdapter', 'AIChat-Web', 'AIChat-ImageEdit')
foreach ($module in $modules) {
    $artifact = Join-Path $repository "$module/target/$module-2.0-Phototaxis.jar"
    if (!(Test-Path -LiteralPath $artifact)) { throw "缺少 $artifact；请先按部署文档构建并 install。" }
}
$null = New-Item -ItemType Directory -Path $release, "$release/lib", "$release/laws", "$release/data/AIChat/Plugins" -Force
$mavenArguments = @('-B')
if ($Offline) { $mavenArguments += '-o' }
if ($LocalRepository) { $mavenArguments += "-Dmaven.repo.local=$LocalRepository" }
$mavenArguments += @('-pl', ($modules -join ','), '-am',
    'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies',
    '-DincludeScope=runtime', '-DexcludeArtifactIds=Universe,AIChat', "-DoutputDirectory=$release/lib")
Push-Location -LiteralPath $repository
try {
    # 只收集运行依赖，不计算向量或读取生产配置。
    & $Maven @mavenArguments *> "$release/dependencies.log"
    if ($LASTEXITCODE -ne 0) { throw "运行依赖收集失败，见 $release/dependencies.log；此目录不能发布。" }
    Copy-Item -LiteralPath 'Universe/target/Universe-2.0-Phototaxis.jar' -Destination "$release/Universe.jar"
    foreach ($module in @('AIChat', 'OnebotAdapter', 'openaiadapter', 'DashScopeAdapter')) {
        Copy-Item -LiteralPath "$module/target/$module-2.0-Phototaxis.jar" -Destination "$release/laws/$module.jar"
    }
    foreach ($module in @('AIChat-Web', 'AIChat-ImageEdit')) {
        Copy-Item -LiteralPath "$module/target/$module-2.0-Phototaxis.jar" -Destination "$release/data/AIChat/Plugins/$module.jar"
    }
    $separator = [IO.Path]::PathSeparator
    $classpath = @("$release/Universe.jar", "$release/laws/AIChat.jar", "$release/lib/*") -join $separator
    & $Java '-Djava.awt.headless=true' -cp $classpath com.nekoyu.Universe.AIChat.graph.GraphIconCli verify-classpath --required true *> "$release/icons-verification.log"
    if ($LASTEXITCODE -ne 0) { throw "最终发行包资源校验失败，见 $release/icons-verification.log；此目录不能发布。" }
    $head = & git -c "safe.directory=$repository" rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw '无法记录构建提交。' }
    $changes = @(& git -c "safe.directory=$repository" status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw '无法记录工作区状态。' }
    $files = @(Get-ChildItem -LiteralPath $release -Recurse -File -Filter '*.jar' | Sort-Object FullName | ForEach-Object {
        [PSCustomObject]@{Path=$_.FullName.Substring($release.Length + 1).Replace('\','/'); Bytes=$_.Length; SHA256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [PSCustomObject]@{ReleaseId=$ReleaseId; Commit=($head -join '').Trim(); Dirty=($changes.Count -gt 0); BuiltAt=(Get-Date).ToString('o'); Artifacts=$files} |
        ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$release/release.json" -Encoding UTF8
    Get-Content -LiteralPath "$release/icons-verification.log" -Tail 1
    Write-Output "发行目录：$release；JAR 数量：$($files.Count)；配置和向量缓存需单独迁移。"
} finally { Pop-Location }
