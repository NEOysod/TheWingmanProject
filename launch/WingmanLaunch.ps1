# Starts Starsector with Wingman's agent, without changing any of the game's own files.
#
# Each time it runs it reads the game's current launch settings and adds the agent to a copy of them:
#   - Mikohime (Miko_Rouge.bat + Miko_Simple.txt, or another launcher .bat that runs java.exe @<file>.txt):
#     writes Miko_Wingman.txt next to it and starts the game the way that .bat does.
#   - The normal game (vmparams + jre): starts jre\bin\java.exe with vmparams' settings plus the agent.
# A setup that replaces Java's class loader (-Djava.system.class.loader=..., as Fast Rendering's own setup does)
# can't take -javaagent; for those it uses wingman.agent.Launch instead (see docs/USER_GUIDE.md, the agent).
# Re-running Mikohime's configurator needs nothing more: the copy is made fresh from its file on every launch.
#
# Written for Windows PowerShell 5.1 (comes with Windows 10 and 11).

param([switch]$DryRun)   # -DryRun: show what would be started, start nothing

$ErrorActionPreference = 'Stop'
$Utf8 = New-Object System.Text.UTF8Encoding($false)

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "Wingman launcher: $msg" -ForegroundColor Red
    Write-Host "Your game files were not changed. You can still start the game the usual way."
    exit 1
}

# ---- where things are ----
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = $here
while ($root -and -not (Test-Path (Join-Path $root 'starsector-core'))) {
    $parent = Split-Path -Parent $root
    if ($parent -eq $root) { $root = $null } else { $root = $parent }
}
if (-not $root) { Fail "can't find the Starsector folder (the one with starsector-core in it). Keep this file inside Starsector\mods\<Wingman folder>." }
$core = Join-Path $root 'starsector-core'

# The Wingman folder this launcher sits in (or any mods\* folder holding the agent).
$modDir = $null
$probe = $here
while ($probe -and $probe.Length -gt $root.Length) {
    if (Test-Path (Join-Path $probe 'jars\WingmanAgent.jar')) { $modDir = $probe; break }
    $probe = Split-Path -Parent $probe
}
if (-not $modDir) {
    $found = Get-ChildItem -Path (Join-Path $root 'mods') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'jars\WingmanAgent.jar') } | Select-Object -First 1
    if ($found) { $modDir = $found.FullName }
}
if (-not $modDir) { Fail "can't find jars\WingmanAgent.jar in any folder under Starsector\mods." }
$modName = Split-Path -Leaf $modDir
# The game runs from starsector-core, so paths in its settings start there.
$agentRel = "../mods/$modName/jars/WingmanAgent.jar"
$exportsArg = '--add-exports=java.instrument/sun.instrument=ALL-UNNAMED'
$gameMain = 'com.fs.starfarer.StarfarerLauncher'

# ---- adding the agent to a list of settings (one setting per item) ----
# Returns the new list. Two ways:
#   -javaagent:<jar> before -classpath (the usual way), or
#   with a replaced class loader: the jar on -classpath, --add-exports, and wingman.agent.Launch as main class.
function Add-Agent([string[]]$items) {
    $list = New-Object System.Collections.Generic.List[string]
    foreach ($i in $items) { $list.Add($i) }
    $live = @($list | Where-Object { -not $_.TrimStart().StartsWith('#') })
    if (@($live | Where-Object { $_ -match 'WingmanAgent\.jar' }).Count -gt 0) { return ,$list.ToArray() }   # already there

    $cpAt = -1
    for ($k = 0; $k -lt $list.Count; $k++) {
        $t = $list[$k].Trim()
        if ($t -eq '-classpath' -or $t -eq '-cp' -or $t.StartsWith('-classpath ') -or $t.StartsWith('-cp ')) { $cpAt = $k; break }
    }
    if ($cpAt -lt 0) { Fail "the launch settings have no -classpath setting, so Wingman can't tell where to add the agent." }

    $ownLoader = @($live | Where-Object { $_ -match '-Djava\.system\.class\.loader=' }).Count -gt 0
    if (-not $ownLoader) {
        $list.Insert($cpAt, "-javaagent:$agentRel")
        return ,$list.ToArray()
    }

    # Replaced class loader: use Wingman's own launcher class.
    $t = $list[$cpAt].Trim()
    if ($t -eq '-classpath' -or $t -eq '-cp') {
        $list[$cpAt + 1] = $list[$cpAt + 1].TrimEnd() + ";$agentRel"
    } else {
        $list[$cpAt] = $list[$cpAt].TrimEnd() + ";$agentRel"
    }
    $mainAt = -1
    for ($k = $list.Count - 1; $k -ge 0; $k--) { if ($list[$k].Trim() -eq $gameMain) { $mainAt = $k; break } }
    if ($mainAt -lt 0) { Fail "the launch settings don't end with $gameMain, so Wingman can't start the game after its agent." }
    $list[$mainAt] = 'wingman.agent.Launch'
    $list.Insert($cpAt, $exportsArg)
    return ,$list.ToArray()
}

# ---- Mikohime and other launchers that run java.exe @<settings>.txt ----
$launcherBat = $null; $argsFile = $null
$candidates = @()
$rouge = Join-Path $root 'Miko_Rouge.bat'
if (Test-Path $rouge) { $candidates += Get-Item $rouge }
$candidates += Get-ChildItem -Path $root -Filter '*.bat' -File | Where-Object { $_.Name -ne 'Miko_Rouge.bat' -and $_.Name -notmatch 'Wingman' }
foreach ($bat in $candidates) {
    $text = [System.IO.File]::ReadAllText($bat.FullName)
    $m = [regex]::Match($text, 'java[w]?\.exe"?\s+@"?([^"\r\n]+?\.txt)"?\s*(\r?\n|$)')
    if ($m.Success) { $launcherBat = $bat.FullName; $argsFile = $m.Groups[1].Value.Trim(); break }
}

if ($launcherBat) {
    # The settings file's path is as the .bat gives it, from the folder java.exe runs in (starsector-core).
    $argsPath = [System.IO.Path]::GetFullPath((Join-Path $core $argsFile))
    if (-not (Test-Path $argsPath)) { $argsPath = [System.IO.Path]::GetFullPath((Join-Path $root $argsFile)) }
    if (-not (Test-Path $argsPath)) { Fail "found $(Split-Path -Leaf $launcherBat), but not its settings file $argsFile." }

    $lines = [System.IO.File]::ReadAllLines($argsPath)
    $newLines = Add-Agent $lines
    $outName = 'Miko_Wingman.txt'
    $outPath = Join-Path (Split-Path -Parent $argsPath) $outName
    [System.IO.File]::WriteAllLines($outPath, $newLines, $Utf8)

    # Start it the way the launcher .bat does, with the copy in place of its settings file.
    $batText = [System.IO.File]::ReadAllText($launcherBat)
    $leaf = Split-Path -Leaf $argsFile
    $batText = $batText.Replace($leaf, $outName)
    $tmpBat = Join-Path $root 'Miko_Wingman_Launch.bat'
    [System.IO.File]::WriteAllText($tmpBat, $batText, (New-Object System.Text.ASCIIEncoding))
    if ($DryRun) { Write-Host "Would run ${tmpBat}:"; Write-Host $batText; Write-Host "with ${outPath}:"; $newLines | ForEach-Object { Write-Host "  $_" }; exit 0 }
    Write-Host "Starting Starsector through $(Split-Path -Leaf $launcherBat) with Wingman's agent ($outName)..."
    Push-Location $root
    try { & cmd.exe /c "`"$tmpBat`"" } finally { Pop-Location }
    exit $LASTEXITCODE
}

# ---- the normal game: vmparams + jre ----
$vmparams = Join-Path $root 'vmparams'
if (-not (Test-Path $vmparams)) { Fail "found neither a Mikohime launcher nor the game's vmparams file in $root." }
$java = $null
foreach ($p in @('jre\bin\java.exe', 'starsector-core\jre\bin\java.exe', 'jre\bin\javaw.exe')) {
    $c = Join-Path $root $p
    if (Test-Path $c) { $java = $c; break }
}
if (-not $java) { Fail "can't find the game's Java (jre\bin\java.exe in the Starsector folder)." }

$text = ([System.IO.File]::ReadAllText($vmparams)) -replace '[\r\n]+', ' '
$tokens = @([regex]::Matches($text, '"[^"]*"|\S+') | ForEach-Object { $_.Value })
if ($tokens.Count -gt 0 -and $tokens[0] -match 'java[w]?(\.exe)?"?$') { $tokens = @($tokens | Select-Object -Skip 1) }
$tokens = Add-Agent $tokens
$argLine = ($tokens -join ' ')
if ($DryRun) { Write-Host "Would run (in $core): $java $argLine"; exit 0 }
Write-Host "Starting Starsector with Wingman's agent..."
$proc = Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $core -NoNewWindow -PassThru -Wait
exit $proc.ExitCode
