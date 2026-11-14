# Starts Starsector with Wingman's agent, without changing any of the game's own files.
#
# Each time it runs it reads the game's current launch settings and adds the agent to a copy of them:
#   - A launcher .bat that runs java.exe @<settings file>: Mikohime (Miko_Rouge.bat + Miko_Simple.txt), Fast
#     Rendering 1.0 (starsector-core\fr.bat + fr.vmparams), or another: writes a copy of the settings with the
#     agent next to them (Miko_Simple_Wingman.txt, fr_Wingman.vmparams) and starts the game the way that .bat does.
#   - The normal game (vmparams + jre): starts jre\bin\java.exe with vmparams' settings plus the agent.
# A setup that replaces Java's class loader (-Djava.system.class.loader=..., as Fast Rendering before 1.0 did)
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

# ---- launchers that run java.exe @<settings file> ----
# Mikohime (Miko_Rouge.bat in the Starsector folder, settings in a .txt), Fast Rendering 1.0 and later (fr.bat in
# starsector-core, settings in fr.vmparams), and any other .bat of that kind. The first whose settings the game
# can still start from is used.
$candidates = @()
$rouge = Join-Path $root 'Miko_Rouge.bat'
if (Test-Path $rouge) { $candidates += Get-Item $rouge }
$candidates += Get-ChildItem -Path $root -Filter '*.bat' -File | Where-Object { $_.Name -ne 'Miko_Rouge.bat' -and $_.Name -notmatch 'Wingman' }
$frBat = Join-Path $core 'fr.bat'
if (Test-Path $frBat) { $candidates += Get-Item $frBat }
$candidates += Get-ChildItem -Path $core -Filter '*.bat' -File | Where-Object { $_.Name -ne 'fr.bat' -and $_.Name -notmatch 'Wingman' }

# The jars a settings file names on its -classpath that this game doesn't have (left from before a game update).
function Missing-Jars([string[]]$lines, [string]$from) {
    $missing = @()
    for ($k = 0; $k -lt $lines.Count; $k++) {
        $t = $lines[$k].Trim()
        $cp = $null
        if ($t.StartsWith('-classpath ') -or $t.StartsWith('-cp ')) { $cp = $t -replace '^-(classpath|cp)\s+', '' }
        elseif (($t -eq '-classpath' -or $t -eq '-cp') -and $k + 1 -lt $lines.Count) { $cp = $lines[$k + 1].Trim() }
        if (-not $cp) { continue }
        foreach ($entry in $cp.Split(';')) {
            $e = $entry.Trim().Trim('"').Replace('\\', '\')
            if ($e -and -not $e.Contains('*') -and -not (Test-Path (Join-Path $from $e))) { $missing += $e }
        }
    }
    return ,$missing
}

# A class loader the settings ask for (-Djava.system.class.loader=...) that none of their jars has (Fast Rendering
# before 1.0 had one; 1.0 doesn't), or $null.
function Missing-Loader([string[]]$lines, [string]$from) {
    $want = $null; $cp = $null
    for ($k = 0; $k -lt $lines.Count; $k++) {
        $t = $lines[$k].Trim()
        if ($t.StartsWith('#')) { continue }
        if ($t -match '^-Djava\.system\.class\.loader=(\S+)') { $want = $Matches[1] }
        if ($t.StartsWith('-classpath ') -or $t.StartsWith('-cp ')) { $cp = $t -replace '^-(classpath|cp)\s+', '' }
        elseif (($t -eq '-classpath' -or $t -eq '-cp') -and $k + 1 -lt $lines.Count) { $cp = $lines[$k + 1].Trim() }
    }
    if (-not $want -or -not $cp) { return $null }
    $entryName = $want.Replace('.', '/') + '.class'
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    foreach ($entry in $cp.Split(';')) {
        $e = $entry.Trim().Trim('"').Replace('\\', '\')
        if (-not $e -or $e.Contains('*')) { continue }
        $jar = Join-Path $from $e
        if (-not (Test-Path $jar -PathType Leaf)) { continue }
        try {
            $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
            try { if ($z.GetEntry($entryName)) { return $null } } finally { $z.Dispose() }
        } catch { }
    }
    return $want
}

$launcherBat = $null; $argsFile = $null; $argsPath = $null; $lines = $null
foreach ($bat in $candidates) {
    $text = [System.IO.File]::ReadAllText($bat.FullName)
    $m = [regex]::Match($text, 'java[w]?\.exe"?\s+@"?([^"\s]+)"?')
    if (-not $m.Success) { continue }
    $file = $m.Groups[1].Value.Trim()
    $batDir = Split-Path -Parent $bat.FullName
    # The settings file's path is as the .bat gives it: from the .bat's folder, or starsector-core where the game runs.
    $path = $null
    foreach ($base in @($batDir, $core, $root)) {
        $c = [System.IO.Path]::GetFullPath((Join-Path $base $file))
        if (Test-Path $c) { $path = $c; break }
    }
    if (-not $path) { Write-Host "Wingman launcher: found $($bat.Name), but not its settings file $file; skipping it." -ForegroundColor Yellow; continue }
    $l = [System.IO.File]::ReadAllLines($path)
    $stale = Missing-Jars $l $core
    if ($stale.Count -gt 0) {
        # Settings left over from before a game update (Mikohime set up for Fast Rendering's old fr.jar, say):
        # the game can't start from them.
        Write-Host ""
        Write-Host "Wingman launcher: $($bat.Name) names files this game doesn't have ($($stale -join ', '))." -ForegroundColor Yellow
        Write-Host "They are from before a game update; set that launcher up again for this game version to use it."
        continue
    }
    $loader = Missing-Loader $l $core
    if ($loader) {
        Write-Host ""
        Write-Host "Wingman launcher: $($bat.Name) asks for a class loader no jar of it has ($loader)." -ForegroundColor Yellow
        Write-Host "It was set up for an older Fast Rendering; set that launcher up again for the one you have to use it."
        continue
    }
    $launcherBat = $bat.FullName; $argsFile = $file; $argsPath = $path; $lines = $l
    break
}

if ($launcherBat) {
    $newLines = Add-Agent $lines
    # A copy of the settings with the agent, next to them: Miko_Simple_Wingman.txt, fr_Wingman.vmparams...
    $leaf = Split-Path -Leaf $argsFile
    $ext = [System.IO.Path]::GetExtension($leaf)
    $outName = [System.IO.Path]::GetFileNameWithoutExtension($leaf) + '_Wingman' + $ext
    $outPath = Join-Path (Split-Path -Parent $argsPath) $outName
    [System.IO.File]::WriteAllLines($outPath, $newLines, $Utf8)

    # Start it the way the launcher .bat does, from its own folder, with the copy in place of its settings file.
    $batDir = Split-Path -Parent $launcherBat
    $batText = [System.IO.File]::ReadAllText($launcherBat)
    $batText = $batText.Replace($leaf, $outName)
    $tmpBat = Join-Path $batDir 'Wingman_Launch.bat'
    [System.IO.File]::WriteAllText($tmpBat, $batText, (New-Object System.Text.ASCIIEncoding))
    if ($DryRun) { Write-Host "Would run ${tmpBat} (in $batDir):"; Write-Host $batText; Write-Host "with ${outPath}:"; $newLines | ForEach-Object { Write-Host "  $_" }; exit 0 }
    Write-Host "Starting Starsector through $(Split-Path -Leaf $launcherBat) with Wingman's agent ($outName)..."
    Push-Location $batDir
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
