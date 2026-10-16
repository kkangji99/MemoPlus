@echo off
rem QuickMemo build (JDK 21+)
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
javac -encoding UTF-8 -d out src\QuickMemo.java || exit /b 1
echo Main-Class: QuickMemo> out\manifest.txt
jar cfm QuickMemo.jar out\manifest.txt -C out . || exit /b 1
echo Build OK: QuickMemo.jar
