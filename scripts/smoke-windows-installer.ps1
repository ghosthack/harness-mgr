param(
    [Parameter(Mandatory = $true)]
    [string] $Installer,

    [Parameter(Mandatory = $true)]
    [string] $ExpectedVersion,

    [string] $UpgradeFromInstaller
)

$ErrorActionPreference = "Stop"
$AppName = "Harness Manager"
$SuccessExitCodes = @(0, 3010)

function Assert-AuthenticodeSignature([string] $Path) {
    $signature = Get-AuthenticodeSignature -FilePath $Path
    if ($signature.Status -ne "Valid") {
        throw "Invalid Authenticode signature on $(Split-Path -Leaf $Path): $($signature.Status)"
    }
    if ($null -eq $signature.TimeStamperCertificate) {
        throw "Missing Authenticode timestamp on $(Split-Path -Leaf $Path)"
    }
}

function Invoke-Installer([string] $Path, [string] $Label) {
    $log = Join-Path $env:RUNNER_TEMP "$Label-msi.log"
    $arguments = "/qn /norestart /L*V `"$log`""
    $process = Start-Process -FilePath $Path -ArgumentList $arguments -Wait -PassThru
    if ($SuccessExitCodes -notcontains $process.ExitCode) {
        if (Test-Path -LiteralPath $log) {
            Write-Host "----- $Label Windows Installer log (last 250 lines) -----"
            Get-Content -LiteralPath $log -Tail 250
        }
        throw "$Label installer exited with code $($process.ExitCode)"
    }
    Write-Host "$Label installer exit code: $($process.ExitCode)"
}

function Get-AppRegistrations {
    $roots = @(
        "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*",
        "HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*"
    )
    return @($roots |
        ForEach-Object { Get-ItemProperty -Path $_ -ErrorAction SilentlyContinue } |
        Where-Object { $_.DisplayName -eq $AppName })
}

function Assert-InstalledVersion([string] $Version) {
    $registrations = @(Get-AppRegistrations)
    if ($registrations.Count -ne 1) {
        $details = ($registrations | ForEach-Object {
            "$($_.DisplayVersion) at $($_.InstallLocation)"
        }) -join "; "
        throw "Expected one $AppName registration; found $($registrations.Count): $details"
    }

    $registration = $registrations[0]
    if ($registration.DisplayVersion -ne $Version) {
        throw "Installed version is $($registration.DisplayVersion); expected $Version"
    }
    if (-not $registration.InstallLocation) {
        throw "$AppName registration has no InstallLocation"
    }

    $launcher = Join-Path $registration.InstallLocation "$AppName.exe"
    if (-not (Test-Path -LiteralPath $launcher)) {
        throw "Installed launcher is missing: $launcher"
    }
    foreach ($worker in @("jmux.exe", "harness-stimulator.exe")) {
        Assert-AuthenticodeSignature (Join-Path $registration.InstallLocation $worker)
    }
    Assert-AuthenticodeSignature $launcher
    return $launcher
}

if (-not (Test-Path -LiteralPath $Installer)) {
    throw "Candidate installer not found: $Installer"
}
Assert-AuthenticodeSignature $Installer

if ($UpgradeFromInstaller) {
    if (-not (Test-Path -LiteralPath $UpgradeFromInstaller)) {
        throw "Previous installer not found: $UpgradeFromInstaller"
    }
    Assert-AuthenticodeSignature $UpgradeFromInstaller
    Invoke-Installer $UpgradeFromInstaller "previous-release"
    $previousRegistrations = @(Get-AppRegistrations)
    if ($previousRegistrations.Count -ne 1) {
        throw "Previous release did not create exactly one $AppName registration"
    }
    Write-Host "Previous release installed: $($previousRegistrations[0].DisplayVersion)"
}

Invoke-Installer $Installer "candidate"
$launcher = Assert-InstalledVersion $ExpectedVersion
$smoke = Start-Process -FilePath $launcher -ArgumentList "--package-smoke" -Wait -PassThru
if ($smoke.ExitCode -ne 0) {
    throw "Installed launcher smoke exited with code $($smoke.ExitCode)"
}

$uninstall = Start-Process -FilePath $Installer -ArgumentList "uninstall" -Wait -PassThru
if ($SuccessExitCodes -notcontains $uninstall.ExitCode) {
    throw "Candidate uninstaller exited with code $($uninstall.ExitCode)"
}
if (@(Get-AppRegistrations).Count -ne 0) {
    throw "$AppName remains registered after uninstall"
}
Write-Host "Windows install, upgrade, launcher, and uninstall smoke: OK"
