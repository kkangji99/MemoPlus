@echo off
cd /d "%~dp0"
if not exist QuickMemo.jar call build.bat || exit /b 1
start "" javaw -Dsun.java2d.uiScale.enabled=true -jar QuickMemo.jar
