param([switch]$Check, [string]$Main = 'org.ds.tuifileman.app.App',
      [Parameter(ValueFromRemainingArguments = $true)][string[]]$Folders)
$ErrorActionPreference = 'Stop'
$startFolder = (Get-Location).Path
Push-Location $PSScriptRoot
try {
    $deps = @(
        @{ File = '.cache\lanterna-3.2.0-alpha1.jar';
           Url  = 'https://repo.maven.apache.org/maven2/com/googlecode/lanterna/lanterna/3.2.0-alpha1/lanterna-3.2.0-alpha1.jar' },
        @{ File = '.cache\jna-5.17.0.jar';
           Url  = 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar' },
        @{ File = '.cache\jna-platform-5.17.0.jar';
           Url  = 'https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar' },
        @{ File = '.cache\org.eclipse.jgit-7.8.0.202609011348-r.jar';
           Url  = 'https://repo.maven.apache.org/maven2/org/eclipse/jgit/org.eclipse.jgit/7.8.0.202609011348-r/org.eclipse.jgit-7.8.0.202609011348-r.jar' },
        @{ File = '.cache\slf4j-api-2.0.18.jar';
           Url  = 'https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar' }
    )
    New-Item -ItemType Directory -Force '.cache', 'target\classes-lanterna' | Out-Null
    foreach ($dep in $deps) {
        if (-not (Test-Path $dep.File)) {
            $download = "$($dep.File).download"
            try {
                Invoke-WebRequest $dep.Url -OutFile $download
                Move-Item $download $dep.File
            } finally {
                if (Test-Path $download) { Remove-Item $download }
            }
        }
    }
    $classpath = ($deps | ForEach-Object { $_.File }) -join ';'
    if (Test-Path '.cache\jline-4.4.7-jdk11.jar') {
        $classpath = "$classpath;.cache\jline-4.4.7-jdk11.jar"
    }
    $sources = Get-ChildItem -Recurse 'src\main\java' -Filter '*.java' | ForEach-Object { $_.FullName }
    javac --release 17 -encoding UTF-8 -classpath $classpath -d 'target\classes-lanterna' $sources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
    if (-not $Check) {
        if ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) {
            throw 'The app needs an interactive terminal. Run .\run-lanterna.ps1 directly in PowerShell or Windows Terminal.'
        }
        $folders = if ($Folders) {
            $Folders | ForEach-Object { if ([IO.Path]::IsPathRooted($_)) { $_ } else { Join-Path $startFolder $_ } }
        } else { @($startFolder) }
        java --enable-native-access=ALL-UNNAMED -classpath "target\classes-lanterna;$classpath" $Main @folders
        if ($LASTEXITCODE -ne 0) { throw 'Java execution failed.' }
    } else {
        Write-Output 'tuifileman compiled successfully.'
    }
} finally {
    Pop-Location
}
