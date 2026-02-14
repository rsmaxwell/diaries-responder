@echo off
setLocal EnableDelayedExpansion

set BASEDIR=%~dp0

pushd %BASEDIR%
set DEV_SCRIPT_DIR=%CD%
popd

pushd %DEV_SCRIPT_DIR%\..
set SCRIPT_DIR=%CD%
popd

pushd %SCRIPT_DIR%\..
set SUBPROJECT_DIR=%CD%
popd

pushd %SUBPROJECT_DIR%\..
set PROJECT_DIR=%CD%
popd

pushd %SUBPROJECT_DIR%\build
set BUILD_DIR=%CD%
popd



pushd %PROJECT_DIR%\diaries-common
set COMMON_SUBPROJECT_DIR=%CD%
popd

cd %PROJECT_DIR%


rem Build a short classpath using runtime\* wildcard
set "CLASSPATH=%SUBPROJECT_DIR%\bin\main;%SUBPROJECT_DIR%\src\main\resources\META-INF;%COMMON_SUBPROJECT_DIR%\bin\main;%SUBPROJECT_DIR%\runtime\*"

rem echo(
rem echo ==== CLASSPATH entries ====
rem echo %SUBPROJECT_DIR%\bin\main
rem echo %SUBPROJECT_DIR%\src\main\resources\META-INF
rem echo %COMMON_SUBPROJECT_DIR%\bin\main
rem for %%a in ("%SUBPROJECT_DIR%\runtime\*.jar") do echo %%~fa
rem echo ==========================
rem echo(

java -classpath "%CLASSPATH%" com.rsmaxwell.diaries.responder.Responder ^
 --config %USERPROFILE%\.diaries\responder.json

