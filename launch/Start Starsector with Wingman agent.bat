@echo off
rem Starts Starsector with Wingman's agent (optional, see Wingman's README). Put this file in your Starsector
rem folder, the one with starsector.exe in it, and start the game with it (or with a desktop shortcut to it).
rem It changes none of the game's files: each time, it reads your launch settings (Mikohime's or the normal
rem vmparams), adds the agent to a copy of them and starts the game. Works with Fast Rendering.
setlocal
set "PS1="
rem next to this file (the copy in the mod's launch folder), else in any mod under Starsector\mods
if exist "%~dp0WingmanLaunch.ps1" set "PS1=%~dp0WingmanLaunch.ps1"
if not defined PS1 for /d %%D in ("%~dp0mods\*") do if not defined PS1 if exist "%%~D\launch\WingmanLaunch.ps1" set "PS1=%%~D\launch\WingmanLaunch.ps1"
if not defined PS1 goto notfound
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
if errorlevel 1 pause
exit /b

:notfound
echo.
echo Wingman launcher: can't find Wingman's launch\WingmanLaunch.ps1.
echo Put this file in your Starsector folder, the one with starsector.exe and the mods folder in it,
echo with Wingman installed in mods. Your game files were not changed.
echo.
pause
exit /b 1
