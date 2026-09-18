# SwiftSlate Desktop — protect API keys with Windows DPAPI
# Run in a normal PowerShell window, not as Administrator:
#   .\configure.ps1

[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:USERPROFILE ".swiftslate")
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$configPath = Join-Path $InstallDir "config.json"

if (-not (Test-Path $configPath)) {
    throw "SwiftSlate configuration not found: $configPath"
}

Add-Type -AssemblyName System.Security

function Read-PlaintextFromSecureString {
    param([Security.SecureString]$SecureString)
    if ($null -eq $SecureString -or $SecureString.Length -eq 0) { return "" }
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureString)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Protect-ApiKey {
    param([string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    try {
        $protected = [Security.Cryptography.ProtectedData]::Protect(
            $bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser
        )
        return [Convert]::ToBase64String($protected)
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($config -isnot [PSCustomObject]) {
    throw "config.json must contain a JSON object"
}

Write-Host "SwiftSlate Desktop API key setup" -ForegroundColor Cyan
Write-Host "Keys are protected with Windows DPAPI for the current Windows user." -ForegroundColor DarkGray
Write-Host "Press Enter on the first prompt to keep API access disabled (offline mode)."
Write-Host ""

$keys = @()
while ($true) {
    $secure = Read-Host "API key $($keys.Count + 1) (Enter when finished)" -AsSecureString
    $key = Read-PlaintextFromSecureString $secure
    if ([string]::IsNullOrWhiteSpace($key)) { break }
    $keys += Protect-ApiKey $key
}

# Remove legacy plaintext keys and write protected values only.
$config.PSObject.Properties.Remove("api_keys")
if ($config.PSObject.Properties.Name -contains "api_keys_protected") {
    $config.api_keys_protected = @($keys)
} else {
    $config | Add-Member -NotePropertyName api_keys_protected -NotePropertyValue @($keys)
}

$tempPath = "$configPath.tmp"
$config | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $tempPath -Encoding UTF8
Move-Item -LiteralPath $tempPath -Destination $configPath -Force

Write-Host "Saved $($keys.Count) protected API key(s)." -ForegroundColor Green
if ($keys.Count -eq 0) {
    Write-Host "Offline commands and styles remain available. AI commands are disabled until a key is added." -ForegroundColor DarkGray
}
