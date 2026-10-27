param([switch]$Agent, [switch]$Build, [string]$GraalHome = $(if ($env:GRAALVM_HOME) { $env:GRAALVM_HOME } else { $env:JAVA_HOME }), [string]$Vcvars = '')
# GraalVM native-image build. The class path is the fat jar from `mvn package` (the same jar the Linux build uses),
# so the native image is built from exactly what the tests ran against.
#   .\native\native.ps1 -Agent   run the jar on GraalVM with the tracing agent (use every feature, quit with F10)
#   .\native\native.ps1 -Build   build target\native\tuifileman.exe and copy it alone to target\dist (needs MSVC; vcvars64 is loaded here)
# Run `mvn package` first. The jar is target\tuifileman-<Cli.VERSION>.jar; the script refuses a jar that is older than
# any file under src\main, so it can never be built from stale classes.
# GraalVM comes from -GraalHome, else $env:GRAALVM_HOME, else $env:JAVA_HOME. The Visual Studio C++ tools are found with
# vswhere, or pass the path of vcvars64.bat with -Vcvars.
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot)
try {
    if (-not $GraalHome -or -not (Test-Path "$GraalHome\bin\native-image.cmd")) {
        throw 'GraalVM with native-image not found: set GRAALVM_HOME or pass -GraalHome.'
    }
    $cp = ''
    if ($Agent -or $Build) {
        $line = Select-String -Path 'src\main\java\org\ds\tuifileman\cli\Cli.java' -Pattern 'VERSION = "([^"]+)"' | Select-Object -First 1
        if (-not $line) { throw 'Cannot read Cli.VERSION.' }
        $cp = "target\tuifileman-$($line.Matches[0].Groups[1].Value).jar"
        if (-not (Test-Path $cp)) { throw "$cp is missing: run mvn package first." }
        $newest = Get-ChildItem -Recurse 'src\main' -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        if ($newest.LastWriteTimeUtc -gt (Get-Item $cp).LastWriteTimeUtc) {
            throw "$cp is older than $($newest.FullName): run mvn package again."
        }
    }
    $config = 'native\config'
    if ($Agent) {
        & "$GraalHome\bin\java.exe" "-agentlib:native-image-agent=config-merge-dir=$config" `
            --enable-native-access=ALL-UNNAMED -cp $cp org.ds.tuifileman.app.App
        # 'Stop' does not cover native programs: without this a crashed run would look like a good trace.
        if ($LASTEXITCODE -ne 0) { throw "the traced run failed (exit $LASTEXITCODE): check $config before building" }
    } elseif ($Build) {
        New-Item -ItemType Directory -Force 'target\native' | Out-Null
        if (-not $Vcvars) {
            $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
            if (Test-Path $vswhere) {
                $install = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
                if ($install) { $Vcvars = Join-Path $install 'VC\Auxiliary\Build\vcvars64.bat' }
            }
        }
        if (-not $Vcvars -or -not (Test-Path $Vcvars)) {
            throw 'Visual Studio C++ tools not found: install them or pass -Vcvars <path to vcvars64.bat>.'
        }
        $cmd = "call `"$Vcvars`" >nul && `"$GraalHome\bin\native-image.cmd`" " +
            "-H:ConfigurationFileDirectories=$config -H:+ReportExceptionStackTraces -H:+AddAllCharsets " +
            "--enable-native-access=ALL-UNNAMED -cp `"$cp`" org.ds.tuifileman.app.App target\native\tuifileman"
        & $env:ComSpec /c $cmd
        if ($LASTEXITCODE -ne 0) { throw 'native-image failed' }
        # native-image copies awt.dll (JNA references java.awt), java.dll and jvm.dll next to the exe, but the
        # exe runs fine without them, so the distributable is the single exe.
        New-Item -ItemType Directory -Force 'target\dist' | Out-Null
        Copy-Item 'target\native\tuifileman.exe' 'target\dist\tuifileman.exe' -Force
        Write-Output ('Single-file build: ' + (Resolve-Path 'target\dist\tuifileman.exe'))
    } else {
        Write-Output 'Use -Agent or -Build.'
    }
} finally {
    Pop-Location
}
