@echo off
rem Rebuilds jars\Wingman.jar against YOUR copy of the game. Needs a JDK (javac) on PATH.
rem Edit the next line if Starsector is installed somewhere else.
set CORE=C:\Program Files (x86)\Fractal Softworks\Starsector\starsector-core
if not exist "%CORE%\starfarer.api.jar" ( echo Cannot find starfarer.api.jar in "%CORE%" - edit CORE in build.bat & pause & exit /b 1 )
if exist build rmdir /s /q build
mkdir build\stub build\tools build\classes
rem Stand-ins for a few game classes whose names Java source cannot spell (see tools\PatchNew.java, tools\PatchPackage.java).
dir /s /b tools\stub\*.java > build\stubs.txt
javac -nowarn --release 8 -cp "%CORE%\*" -d build\stub @build\stubs.txt || ( pause & exit /b 1 )
javac -nowarn -d build\tools tools\PatchNew.java tools\PatchPackage.java || ( pause & exit /b 1 )
dir /s /b src\*.java > build\sources.txt
javac -nowarn --release 8 -cp "build\stub;%CORE%\*" -d build\classes @build\sources.txt || ( pause & exit /b 1 )
java -cp build\tools PatchNew build\classes\wingman\TitleLauncher.class coopNew new || ( pause & exit /b 1 )
java -cp build\tools PatchPackage build\classes\wingman\MessageSpy.class com/fs/starfarer/klass/ com/fs/starfarer/class/ || ( pause & exit /b 1 )
jar cf jars\Wingman.jar -C build\classes wingman
echo Built jars\Wingman.jar
pause
