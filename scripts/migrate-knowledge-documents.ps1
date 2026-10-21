param(
    [Parameter(Mandatory)][ValidateSet('copy', 'verify', 'restore', 'sync')][string] $Mode,
    [string] $Backup,
    [string] $Baseline,
    [string] $LocalRepository = "$env:USERPROFILE/.m2/repository"
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $repository 'tmp/document-migration-cli'
$mysqlJar = Join-Path $LocalRepository 'com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar'
$pgJar = Join-Path $LocalRepository 'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'
foreach ($jar in @($mysqlJar, $pgJar)) {
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing migration driver: $jar. See docs/postgresql-migration.md." }
}
if ($Mode -ne 'verify' -and !$Backup) { throw 'Backup is required for copy, restore and sync.' }
if ($Mode -eq 'sync' -and !$Baseline) { throw 'Baseline is required for sync.' }
$null = New-Item -ItemType Directory -Force -Path $classes
& javac --release 17 -encoding UTF-8 -d $classes `
    (Join-Path $repository 'AIChat/src/main/java/com/nekoyu/Universe/AIChat/knowledge/DocumentArchive.java') `
    (Join-Path $PSScriptRoot 'MigrateKnowledgeDocuments.java')
if ($LASTEXITCODE -ne 0) { throw 'Migration tool compilation failed.' }
$classpath = @($classes, $mysqlJar, $pgJar) -join [IO.Path]::PathSeparator
$arguments = @($Mode)
if ($Mode -eq 'sync') { $arguments += $Baseline }
if ($Mode -ne 'verify') { $arguments += $Backup }
& java -cp $classpath MigrateKnowledgeDocuments @arguments
if ($LASTEXITCODE -ne 0) { throw "Migration $Mode failed. See the error above; do not proceed with cutover." }
