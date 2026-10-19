@echo off
rem QuickMemo build (JDK 21+). FlatLaf (lib\) is bundled into the single jar.
cd /d "%~dp0"
set FLATLAF=lib\flatlaf-3.5.4.jar
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -cp %FLATLAF% -d out src\QuickMemo.java || exit /b 1
rem global hotkey uses the JDK 21 foreign function API (preview), compiled on its own
javac --release 21 --enable-preview -encoding UTF-8 -d out src\QuickMemoHotkey.java 2>nul || exit /b 1
pushd out
jar xf ..\%FLATLAF% || exit /b 1
popd
if exist out\META-INF rmdir /s /q out\META-INF
echo Main-Class: QuickMemo> manifest.tmp
jar cfm QuickMemo.jar manifest.tmp -C out . || exit /b 1
del manifest.tmp
echo Build OK: QuickMemo.jar
