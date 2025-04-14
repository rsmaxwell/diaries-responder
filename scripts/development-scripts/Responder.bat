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


set CLASSPATH="%SUBPROJECT_DIR%\bin\main
set CLASSPATH=%CLASSPATH%;%SUBPROJECT_DIR%\src\main\resources\META-INF
set CLASSPATH=%CLASSPATH%;%COMMON_SUBPROJECT_DIR%\bin\main
for /R %SUBPROJECT_DIR%\runtime %%a in (*.jar) do (
  set CLASSPATH=!CLASSPATH!;%%a
)
set CLASSPATH=%CLASSPATH%"

set LOGGER_LEVEL=DEBUG

java -classpath %CLASSPATH% com.rsmaxwell.diaries.response.Responder ^
 --config %USERPROFILE%\.diaries\responder.json

