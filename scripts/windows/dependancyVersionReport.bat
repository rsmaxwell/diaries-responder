@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

pushd "%SCRIPT_DIR%\..\..\.." || exit /b 1
set PROJECT_DIR=%CD%
set "EXIT_CODE=0"






echo on
gradlew.bat :diaries-responder:dependencyUpdates --no-parallel
echo off

set "EXIT_CODE=%ERRORLEVEL%"

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
