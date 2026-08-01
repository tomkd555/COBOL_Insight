# Applies the Japanese identifier patches to a clean checkout of src/vendor/che4z
# (Eclipse Che4z COBOL Language Support, tag 2.5.1) and rebuilds the server.
#
# Usage:
#   pwsh -File apply-patches.ps1
#
# Prerequisites:
#   - src/vendor/che4z is a clean checkout (no local modifications).
#   - JDK 21 and Maven are available (paths below).

$ErrorActionPreference = "Stop"

$srcRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$che4zDir = Join-Path $srcRoot "vendor\che4z"
$patchDir = $PSScriptRoot

$env:JAVA_HOME = "C:\Users\karid\.cobol-insight-tools\jdk-21.0.11+10"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\karid\.cobol-insight-tools\apache-maven-3.9.16\bin;$env:PATH"

# 1. Apply patches (idempotent: skip a patch that is already applied).
Set-Location $che4zDir
foreach ($patch in (Get-ChildItem $patchDir -Filter *.patch | Sort-Object Name)) {
    git apply --check --reverse $patch.FullName 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "already applied: $($patch.Name)"
        continue
    }
    git apply --check $patch.FullName
    if ($LASTEXITCODE -ne 0) { throw "patch does not apply: $($patch.Name)" }
    git apply $patch.FullName
    Write-Host "applied: $($patch.Name)"
}

# 2. Full build (install all server modules; engine installs its fat jar).
Set-Location (Join-Path $che4zDir "server")
mvn -DskipTests install
if ($LASTEXITCODE -ne 0) { throw "mvn install failed" }

# 3. Reinstall the engine module with assembly skipped, so that the thin jar
#    (not the fat jar) is the artifact in the local Maven repository.
mvn -DskipTests "-Dassembly.skipAssembly=true" install -pl engine
if ($LASTEXITCODE -ne 0) { throw "engine thin-jar install failed" }

Write-Host "src/vendor/che4z patched and built."
