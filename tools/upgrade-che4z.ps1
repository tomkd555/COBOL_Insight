<#
.SYNOPSIS
Rebuilds the vendored Che4z engine jar after moving the submodule to a new upstream tag.
This is the only place Maven runs. Everyday builds use the jar committed under src/engine/libs/m2.

.EXAMPLE
pwsh -File tools/upgrade-che4z.ps1 -Tag 2.5.2 -Suffix ja1 -JdkHome $env:JAVA_HOME -MavenHome C:\tools\maven
#>
param(
    [string]$Tag = "2.5.1",
    [string]$Suffix = "ja1",
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$MavenHome = $env:MAVEN_HOME
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome "bin/java.exe"))) { throw "Pass -JdkHome pointing at a JDK 21 (bin/java.exe not found)." }
if (-not $MavenHome -or -not (Test-Path (Join-Path $MavenHome "bin/mvn.cmd"))) { throw "Pass -MavenHome pointing at Maven 3.9 or later (bin/mvn.cmd not found)." }
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$MavenHome\bin;$env:PATH"

$vendor = Join-Path $root "src/vendor/che4z"
$branch = "ja-identifiers-$Tag"
Write-Host "1. Rebase the Japanese-identifier commits onto tag $Tag on branch $branch"
git -C $vendor fetch origin --tags
git -C $vendor fetch https://github.com/eclipse-che4z/che-che4z-lsp-for-cobol.git --tags
git -C $vendor checkout -B $branch
git -C $vendor rebase $Tag
if ($LASTEXITCODE -ne 0) { throw "Rebase stopped on a conflict. Resolve it in $vendor, then rerun." }

Write-Host "2. Build with Maven (tests skipped) and install the thin engine jar"
Push-Location (Join-Path $vendor "server")
try {
    mvn -q -DskipTests install
    mvn -q -DskipTests "-Dassembly.skipAssembly=true" install -pl engine
} finally { Pop-Location }

Write-Host "3. Merge engine + common + parser into one jar under src/engine/libs/m2"
$m2 = Join-Path $HOME ".m2/repository/org/eclipse/lsp/cobol"
$engine = Get-ChildItem "$m2/engine/*/engine-*.jar" | Where-Object { $_.Name -notmatch "sources|javadoc|jar-with" } | Sort-Object LastWriteTime | Select-Object -Last 1
$common = Get-ChildItem "$m2/common/*/common-*.jar" | Sort-Object LastWriteTime | Select-Object -Last 1
$parser = Get-ChildItem "$m2/parser/*/parser-*.jar" | Sort-Object LastWriteTime | Select-Object -Last 1
$version = "$Tag-$Suffix"
$outDir = Join-Path $root "src/engine/libs/m2/jp/cobolinsight/vendor/che4z-cobol-engine/$version"
New-Item -ItemType Directory -Force $outDir | Out-Null
& (Join-Path $JdkHome "bin/java.exe") (Join-Path $PSScriptRoot "MergeJars.java") (Join-Path $outDir "che4z-cobol-engine-$version.jar") $engine.FullName $common.FullName $parser.FullName
$jar = Join-Path $outDir "che4z-cobol-engine-$version.jar"
(Get-FileHash $jar -Algorithm SHA256).Hash.ToLower() + "`n" | Set-Content -NoNewline "$jar.sha256"
@"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>jp.cobolinsight.vendor</groupId>
  <artifactId>che4z-cobol-engine</artifactId>
  <version>$version</version>
  <name>Eclipse Che4z COBOL Language Support engine (patched for Japanese identifiers)</name>
  <description>engine, common and parser modules of eclipse-che4z/che-che4z-lsp-for-cobol tag $Tag
    plus branch $branch of tomkd555/che-che4z-lsp-for-cobol, merged into one jar.
    Third-party dependencies are declared by the consuming Gradle module, not here.</description>
  <licenses><license><name>Eclipse Public License 2.0</name><url>https://www.eclipse.org/legal/epl-2.0/</url></license></licenses>
</project>
"@ | Set-Content "$outDir/che4z-cobol-engine-$version.pom"
Copy-Item (Join-Path $vendor "LICENSE.md") (Join-Path $root "src/engine/cli/src/main/resources/licenses/che4z/LICENSE.md")

Write-Host "Done. Next:"
Write-Host "  git -C src/vendor/che4z push -u origin $branch"
Write-Host "  edit src/engine/cobol-frontend/build.gradle.kts: che4z-cobol-engine:$version"
Write-Host "  git add src/vendor/che4z src/engine/libs/m2 src/engine/cli/src/main/resources/licenses"
