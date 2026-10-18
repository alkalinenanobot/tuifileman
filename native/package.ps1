param([string]$Version = '')
# Packs the release zip: the single exe, the licence files and the README, plus a SHA-256 file.
#   .\native\package.ps1            version is read from Cli.VERSION
# Run after .\native\native.ps1 -Build. Output: target\release\tuifileman-<version>-windows-x64.zip (+ .sha256)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot)
try {
    if (-not $Version) {
        $line = Select-String -Path 'src\main\java\org\ds\tuifileman\cli\Cli.java' -Pattern 'VERSION = "([^"]+)"' | Select-Object -First 1
        if (-not $line) { throw 'Cannot read Cli.VERSION; pass -Version.' }
        $Version = $line.Matches[0].Groups[1].Value
    }
    if (-not (Test-Path 'target\dist\tuifileman.exe')) { throw 'target\dist\tuifileman.exe is missing: run .\native\native.ps1 -Build first.' }
    $reported = (& 'target\dist\tuifileman.exe' --version) -join ' '
    if ($reported -notmatch [regex]::Escape($Version)) { throw "The exe reports '$reported', expected version $Version." }

    $name = "tuifileman-$Version-windows-x64"
    $stage = "target\release\$name"
    Remove-Item 'target\release' -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force "$stage\licenses" | Out-Null
    Copy-Item 'target\dist\tuifileman.exe', 'LICENSE', 'THIRD-PARTY-NOTICES.md', 'README.md' $stage
    Copy-Item 'licenses\*' "$stage\licenses"
    $zip = "target\release\$name.zip"
    Compress-Archive -Path $stage -DestinationPath $zip
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content "$zip.sha256" "$hash  $name.zip" -Encoding ascii
    Write-Output "$zip  sha256 $hash"
} finally {
    Pop-Location
}
