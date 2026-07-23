@echo off
setlocal

set "SCRIPT_DIR=%~dp0"

pushd "%SCRIPT_DIR%\..\..\.."
set PROJECT_DIR=%CD%
set "RESPONDER_DIR=%PROJECT_DIR%\diaries-responder"

set "CLASSPATH=%RESPONDER_DIR%\bin\main;%RESPONDER_DIR%\runtime\*"
set "CONFIG_FILE=%USERPROFILE%\.diaries\responder.json"

set "HIBERNATE_LOGLEVEL=OFF"
set "LOGLEVEL=INFO"

@echo on
java -classpath "%CLASSPATH%" com.rsmaxwell.diaries.responder.Responder --config "%CONFIG_FILE%"
@echo off

set "EXIT_CODE=%ERRORLEVEL%"

popd
endlocal & exit /b %EXIT_CODE%
