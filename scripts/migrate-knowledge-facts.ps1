param(
    [Parameter(Mandatory)][ValidateSet('migrate', 'verify', 'restore')][string] $Mode,
    [Parameter(Mandatory)][string] $Backup,
    [string] $FactsTable = 'aichat.knowledge_fact',
    [string] $DocumentsTable = 'aichat.knowledge_document',
    [string] $LocalRepository = "$env:USERPROFILE/.m2/repository"
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $repository 'tmp/fact-migration-cli'
$pgJar = Join-Path $LocalRepository 'org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar'
$gsonJar = Join-Path $LocalRepository 'com/google/code/gson/gson/2.13.2-rc1/gson-2.13.2-rc1.jar'
foreach ($jar in @($pgJar, $gsonJar)) {
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing migration dependency: $jar" }
}
$null = New-Item -ItemType Directory -Force -Path $classes
& javac --release 17 -encoding UTF-8 -cp $gsonJar -d $classes `
    (Join-Path $repository 'AIChat/src/main/java/com/nekoyu/Universe/AIChat/PgVectorTable.java') `
    (Join-Path $repository 'AIChat/src/main/java/com/nekoyu/Universe/AIChat/knowledge/KnowledgeFactTable.java') `
    (Join-Path $PSScriptRoot 'MigrateKnowledgeFacts.java')
if ($LASTEXITCODE -ne 0) { throw 'Fact migration compilation failed.' }
$classpath = @($classes, $pgJar, $gsonJar) -join [IO.Path]::PathSeparator
& java '-Duser.language=en' -cp $classpath MigrateKnowledgeFacts $Mode $Backup $FactsTable $DocumentsTable
if ($LASTEXITCODE -ne 0) { throw 'Fact migration failed; transaction rolled back.' }
