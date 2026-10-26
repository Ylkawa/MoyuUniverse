param(
    [Parameter(Mandatory)][ValidateSet('copy', 'verify', 'delete', 'restore')][string] $Mode,
    [Parameter(Mandatory)][string] $Backup,
    [string] $LocalRepository = "$env:USERPROFILE/.m2/repository"
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $repository 'tmp/vector-migration-cli'
$gsonJar = Join-Path $LocalRepository 'com/google/code/gson/gson/2.13.1/gson-2.13.1.jar'
$pgJar = Join-Path $LocalRepository 'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'
foreach ($jar in @($gsonJar, $pgJar)) {
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing driver: $jar" }
}
$null = New-Item -ItemType Directory -Force -Path $classes
& javac --release 17 -encoding UTF-8 -cp $gsonJar -d $classes `
    (Join-Path $repository 'AIChat/src/main/java/com/nekoyu/Universe/AIChat/PgVectorTable.java') `
    (Join-Path $PSScriptRoot 'MigrateVectors.java')
if ($LASTEXITCODE -ne 0) { throw 'Migration tool compilation failed.' }
$classpath = @($classes, $gsonJar, $pgJar) -join [IO.Path]::PathSeparator
& java -cp $classpath MigrateVectors $Mode $Backup
if ($LASTEXITCODE -ne 0) { throw "Vector migration $Mode failed; do not proceed with cleanup." }
