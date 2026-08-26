param(
  [ValidateSet('app-image','exe')]
  [string]$Type = 'app-image',
  [string]$Version = '0.1.0'
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$inputDir = Join-Path $project 'release-input'
$outputDir = Join-Path $project 'release'
$jar = Join-Path $project 'target\codeauto-0.1.0-SNAPSHOT.jar'

Push-Location $project
try {
  mvn -q clean package -DskipTests
  New-Item -ItemType Directory -Force $inputDir | Out-Null
  Copy-Item $jar (Join-Path $inputDir 'CodeAuto.jar') -Force
  New-Item -ItemType Directory -Force $outputDir | Out-Null
  jpackage `
    --type $Type `
    --name CodeAuto `
    --input $inputDir `
    --main-jar CodeAuto.jar `
    --main-class com.codeauto.cli.CodeAutoCli `
    --dest $outputDir `
    --app-version $Version `
    --vendor CodeAuto `
    --arguments '--web' `
    --arguments '--choose-folder' `
    --arguments '--web-port' `
    --arguments '0' `
    $(if ($Type -eq 'exe') { '--win-shortcut'; '--win-menu'; '--win-menu-group'; 'CodeAuto' })
} finally {
  Pop-Location
}

Write-Host "Release created under $outputDir"
