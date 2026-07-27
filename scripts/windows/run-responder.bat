@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

pushd "%SCRIPT_DIR%\..\..\.."
if errorlevel 1 (
    echo ERROR: Unable to change to the Diaries project directory.
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "RESPONDER_DIR=%PROJECT_DIR%\diaries-responder"
set "GENERATED_BUILD_INFO_DIR=%RESPONDER_DIR%\build\generated\resources\buildInfo"

set "CONFIG_FILE=%USERPROFILE%\.diaries\responder.json"

set "HIBERNATE_LOGLEVEL=OFF"
set "LOGLEVEL=INFO"

echo Generating responder build information...
call gradlew.bat :diaries-responder:generateBuildInfo
if errorlevel 1 (
    set "EXIT_CODE=%ERRORLEVEL%"
    echo ERROR: Unable to generate responder build information.
    popd
    endlocal & exit /b %EXIT_CODE%
)

rem Put the freshly generated resource first so an older copy under
rem bin\main cannot take precedence.
set "CLASSPATH=%GENERATED_BUILD_INFO_DIR%;%RESPONDER_DIR%\bin\main;%RESPONDER_DIR%\runtime\*"

@echo on
java -classpath "%CLASSPATH%" com.rsmaxwell.diaries.responder.Responder --config "%CONFIG_FILE%"
@echo off

set "EXIT_CODE=%ERRORLEVEL%"

popd
endlocal & exit /b %EXIT_CODE%
