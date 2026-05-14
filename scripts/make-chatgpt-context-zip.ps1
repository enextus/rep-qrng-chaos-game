[CmdletBinding()]
param(
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"

$ScriptDir = if ($PSScriptRoot) {
    $PSScriptRoot
} else {
    Split-Path -Parent $MyInvocation.MyCommand.Path
}

$ScriptDir = (Resolve-Path -LiteralPath $ScriptDir).ProviderPath
$ProjectRoot = (Resolve-Path -LiteralPath (Join-Path $ScriptDir "..")).ProviderPath
Set-Location -LiteralPath $ProjectRoot

$Timestamp = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $ProjectRoot "rep-qrng-chaos-game_context_$Timestamp.zip"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $ProjectRoot $OutputPath
}

$OutputPath = [System.IO.Path]::GetFullPath($OutputPath)

$CandidatePaths = @(
    "pom.xml",
    "Readme.md",
    "README.md",
    "TEST_STATUS.md",
    ".env.example",
    ".gitignore",
    "src",
    "scripts"
)

$SeenPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
$ExistingPaths = New-Object 'System.Collections.Generic.List[string]'

foreach ($CandidatePath in $CandidatePaths) {
    if (-not (Test-Path -LiteralPath $CandidatePath)) {
        continue
    }

    $ResolvedPath = (Resolve-Path -LiteralPath $CandidatePath).ProviderPath

    if ([string]::Equals($ResolvedPath, $OutputPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        continue
    }

    if ($SeenPaths.Add($ResolvedPath)) {
        $ExistingPaths.Add($ResolvedPath)
    }
}

if ($ExistingPaths.Count -eq 0) {
    throw "No context files found. Run this script from the project root or from the scripts directory."
}

$OutputDirectory = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
}

if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Force
}

Compress-Archive -LiteralPath $ExistingPaths.ToArray() -DestinationPath $OutputPath -Force

$Archive = Get-Item -LiteralPath $OutputPath
Write-Host "Created ChatGPT context archive: $($Archive.FullName)"
Write-Host "Size: $([Math]::Round($Archive.Length / 1KB, 1)) KB"
Write-Host "Included:"
foreach ($Path in $ExistingPaths) {
    Write-Host " - $Path"
}
