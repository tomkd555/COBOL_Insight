<#
.SYNOPSIS
Moves the MAPA submodule to a new upstream commit, keeping the CRLF NEWLINE fix on top.
No build step: the grammars are compiled by Gradle straight from the submodule.
#>
param([string]$Upstream = "master")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$vendor = Join-Path $root "src/vendor/mapa"
git -C $vendor fetch https://github.com/cschneid-the-elder/mapa.git $Upstream
git -C $vendor checkout -B crlf-newline
git -C $vendor rebase FETCH_HEAD
if ($LASTEXITCODE -ne 0) { throw "Rebase stopped on a conflict. Resolve it in $vendor, then rerun." }
Copy-Item (Join-Path $vendor "LICENSE") (Join-Path $root "src/engine/cli/src/main/resources/licenses/mapa/LICENSE")
Write-Host "Done. Next: git -C src/vendor/mapa push -f -u origin crlf-newline; git add src/vendor/mapa"
