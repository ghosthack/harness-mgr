param(
    [Parameter(Mandatory = $true)]
    [string[]] $Path
)

$ErrorActionPreference = "Stop"

if (-not $env:WINDOWS_PFX_FILE) {
    throw "WINDOWS_PFX_FILE is required"
}
if (-not (Test-Path -LiteralPath $env:WINDOWS_PFX_FILE)) {
    throw "Signing certificate not found: $env:WINDOWS_PFX_FILE"
}

$signTool = Get-Command signtool.exe -ErrorAction SilentlyContinue
if (-not $signTool) {
    $kits = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
    $signTool = Get-ChildItem -Path $kits -Filter signtool.exe -Recurse |
        Where-Object { $_.FullName -match '\\x64\\signtool\.exe$' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
}
if (-not $signTool) {
    throw "signtool.exe was not found"
}

$timestampUrl = if ($env:WINDOWS_TIMESTAMP_URL) {
    $env:WINDOWS_TIMESTAMP_URL
} else {
    "http://timestamp.digicert.com"
}

foreach ($item in $Path) {
    if (-not (Test-Path -LiteralPath $item)) {
        throw "File to sign not found: $item"
    }
    $arguments = @(
        "sign",
        "/fd", "SHA256",
        "/td", "SHA256",
        "/tr", $timestampUrl,
        "/f", $env:WINDOWS_PFX_FILE
    )
    if ($env:WINDOWS_PFX_PASSWORD) {
        $arguments += @("/p", $env:WINDOWS_PFX_PASSWORD)
    }
    $arguments += $item

    & $signTool.Source @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed for $item"
    }
}
