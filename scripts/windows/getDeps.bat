@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

pushd "%SCRIPT_DIR%\..\..\.."
set PROJECT_DIR=%CD%


echo on
call gradlew.bat :diaries-responder:getDeps
@echo off

set "EXIT_CODE=%ERRORLEVEL%"

popd
exit /b %EXIT_CODE%
