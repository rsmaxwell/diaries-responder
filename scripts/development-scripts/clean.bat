@echo off

setlocal

set BASEDIR=%~dp0

pushd %BASEDIR%
set DEV_SCRIPT_DIR=%CD%
popd

pushd %DEV_SCRIPT_DIR%\..
set SCRIPT_DIR=%CD%
popd

pushd %SCRIPT_DIR%\..
set PROJECT_DIR=%CD%
popd




cd %PROJECT_DIR%

echo on
call %PROJECT_DIR%\gradlew clean

