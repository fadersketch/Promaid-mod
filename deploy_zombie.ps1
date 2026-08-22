# deploy_zombie.ps1 - deploy promaid jar to Zombie Invade 100 Days pack
# Policy: remove ALL old promaid jars first (avoid same-modId double-jar conflict).
# ASCII-only (PowerShell 5.1 reads .ps1 as ANSI on zh-CN systems).
$ErrorActionPreference = 'Stop'
$MODS    = 'D:\.minecraft\versions\Zombie Invade 100 Days\mods'
$PATCHED = 'C:\Users\Sketch\.zcode\workspace\default\maidmods\patched'

# 1) remove ALL old promaid jars (any name pattern)
Get-ChildItem $MODS -Filter 'promaid-*.jar' -File | Remove-Item -Force

# 2) deploy newest build from patched/
$pm = Get-ChildItem $PATCHED -Filter 'promaid-*.jar' -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $pm) { throw 'patched folder missing build output - run build first' }

Copy-Item $pm.FullName (Join-Path $MODS $pm.Name) -Force

Write-Host "DONE deployed: $($pm.Name)"
Get-ChildItem $MODS -Filter 'promaid-*.jar' -File | ForEach-Object { Write-Host "  $($_.Name)" }
