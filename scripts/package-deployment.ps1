param(
    [string] $Maven = 'mvn.cmd',
    [string] $Java = 'java',
    [string] $ReleaseId = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [string] $LocalRepository,
    [string] $PreviousRelease,
    [switch] $Offline,
    [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
if ($ReleaseId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') { throw 'ReleaseId 只能包含字母、数字、点、下划线和连字符。' }
$repository = Split-Path $PSScriptRoot -Parent
$release = Join-Path $repository ('target/deployment-' + $ReleaseId)
if (Test-Path -LiteralPath $release) { throw "发行目录已存在，请使用新的 ReleaseId：$release" }
$rootPom = [xml](Get-Content -LiteralPath (Join-Path $repository 'pom.xml') -Raw)
$version = $rootPom.project.version
$modules = @($rootPom.project.modules.module | Where-Object { $_ -ne 'distribution' } | ForEach-Object {
    $module = [string] $_
    $pom = [xml](Get-Content -LiteralPath (Join-Path $repository "$module/pom.xml") -Raw)
    $artifact = [string] $pom.project.artifactId
    $group = [string] $pom.project.groupId
    if (!$group) { $group = [string] $pom.project.parent.groupId }
    $destination = if ($module -eq 'Universe') { 'Universe.jar' }
        elseif (Test-Path -LiteralPath (Join-Path $repository "$module/src/main/resources/law.yml")) { "laws/$artifact.jar" }
        elseif (Test-Path -LiteralPath (Join-Path $repository "$module/src/main/resources/plug.properties")) { "data/AIChat/Plugins/$artifact.jar" }
        else { throw "模块 $module 未指定发行位置。" }
    [PSCustomObject]@{ Module=$module; Artifact=$artifact; Group=$group; Destination=$destination }
})
$distributionPom = [xml](Get-Content -LiteralPath (Join-Path $repository 'distribution/pom.xml') -Raw)
$declared = @($distributionPom.project.dependencies.dependency | ForEach-Object { "$($_.groupId):$($_.artifactId)" })
$expected = @($modules | ForEach-Object { "$($_.Group):$($_.Artifact)" })
if (Compare-Object $declared $expected) { throw 'distribution/pom.xml 的运行依赖必须覆盖所有发行模块。' }
if ($PreviousRelease) {
    $previous = Get-Content -LiteralPath (Join-Path $PreviousRelease 'release.json') -Raw | ConvertFrom-Json
}
$null = New-Item -ItemType Directory -Path $release, "$release/lib", "$release/laws", "$release/data/AIChat/Plugins" -Force
$mavenArguments = @('-B')
if ($Offline) { $mavenArguments += '-o' }
if ($LocalRepository) { $mavenArguments += "-Dmaven.repo.local=$LocalRepository" }
Push-Location -LiteralPath $repository
try {
    if (!$SkipBuild) {
        # Install lets the single dependency graph resolve the freshly built modules.
        & $Maven @mavenArguments -pl distribution -am -DskipTests install *> "$release/build.log"
        if ($LASTEXITCODE -ne 0) { throw "构建失败，见 $release/build.log；此目录不能发布。" }
    }
    foreach ($module in $modules) {
        $artifactPath = Join-Path $repository "$($module.Module)/target/$($module.Artifact)-$version.jar"
        if (!(Test-Path -LiteralPath $artifactPath)) { throw "缺少 $artifactPath。" }
        Copy-Item -LiteralPath $artifactPath -Destination (Join-Path $release $module.Destination)
    }
    # Mediate conflicting library versions once, instead of unioning per-module copies.
    $excluded = ($modules.Artifact -join ',')
    & $Maven @mavenArguments -f distribution/pom.xml `
        'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy-dependencies' `
        'org.apache.maven.plugins:maven-dependency-plugin:3.9.0:tree' `
        '-DincludeScope=runtime' '-Dscope=runtime' "-DexcludeArtifactIds=$excluded" `
        "-DoutputDirectory=$release/lib" "-DoutputFile=$release/dependency-tree.txt" *> "$release/dependencies.log"
    if ($LASTEXITCODE -ne 0) { throw "依赖收集失败，见 $release/dependencies.log；此目录不能发布。" }
    Copy-Item -LiteralPath "$PSScriptRoot/start.cmd", "$PSScriptRoot/start.sh" -Destination $release
    $classpath = @("$release/Universe.jar", "$release/lib/*") -join [IO.Path]::PathSeparator
    & $Java '-Djava.awt.headless=true' -cp $classpath "$PSScriptRoot/VerifyDeployment.java" $release *> "$release/verification.log"
    if ($LASTEXITCODE -ne 0) { throw "发行依赖/类加载校验失败，见 $release/verification.log；此目录不能发布。" }
    $iconClasspath = @("$release/Universe.jar", "$release/laws/AIChat.jar", "$release/lib/*") -join [IO.Path]::PathSeparator
    & $Java '-Djava.awt.headless=true' -cp $iconClasspath com.nekoyu.Universe.AIChat.graph.GraphIconCli verify-classpath --required true *> "$release/icons-verification.log"
    if ($LASTEXITCODE -ne 0) { throw "图标资源校验失败，见 $release/icons-verification.log；此目录不能发布。" }
    $head = & git -c "safe.directory=$repository" rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw '无法记录构建提交。' }
    $changes = @(& git -c "safe.directory=$repository" status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw '无法记录工作区状态。' }
    Copy-Item -LiteralPath "$repository/distribution/README.md" -Destination "$release/README.md"
    $files = @(Get-ChildItem -LiteralPath $release -Recurse -File | Where-Object {
        $_.Extension -eq '.jar' -or $_.Name -in @('start.cmd', 'start.sh')
    } | Sort-Object FullName | ForEach-Object {
        [PSCustomObject]@{Path=$_.FullName.Substring($release.Length + 1).Replace('\','/'); Bytes=$_.Length; SHA256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    [PSCustomObject]@{LayoutVersion=2; ReleaseId=$ReleaseId; Commit=($head -join '').Trim(); Dirty=($changes.Count -gt 0); BuiltAt=(Get-Date).ToString('o'); Modules=$modules; Artifacts=$files} |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$release/release.json" -Encoding UTF8
    if ($PreviousRelease) {
        $before = @{}
        foreach ($file in $previous.Artifacts) { $before[$file.Path] = $file.SHA256 }
        $after = @{}
        foreach ($file in $files) { $after[$file.Path] = $file.SHA256 }
        $upload = @($files | Where-Object { !$before.ContainsKey($_.Path) -or $before[$_.Path] -ne $_.SHA256 })
        $removed = @($previous.Artifacts | Where-Object { !$after.ContainsKey($_.Path) } | ForEach-Object { $_.Path })
        [PSCustomObject]@{PreviousRelease=$previous.ReleaseId; Upload=$upload; Remove=$removed} |
            ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$release/update-plan.json" -Encoding UTF8
    }
    Get-Content -LiteralPath "$release/verification.log" -Tail 1
    Write-Output "发行目录：$release；业务 JAR：$($modules.Count)；lib：$(@(Get-ChildItem "$release/lib" -Filter '*.jar').Count)。配置和持久数据沿用部署处原文件。"
} finally { Pop-Location }
