@echo on
setlocal

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

cd %SUBPROJECT%


set PROJECT=diaries-response
set GROUPID=com.rsmaxwell.diaries
set ARTIFACTID=%PROJECT%


rem TIMESTAMP="$(date '+%Y-%m-%d %H:%M:%S')"

set TIMESTAMP=%date:~6,4%-%date:~3,2%-%date:~0,2% %time:~0,2%:%time:~3,2%:%time:~6,2%
set GIT_COMMIT="%(none)%"
set GIT_BRANCH="%(none)%"
set GIT_URL="%(none)%"




set BUILD_ID=none
set VERSION=0.0.1-SNAPSHOT
set REPOSITORY=snapshots



echo on
..\gradlew build --warning-mode all 

(
    echo set BUILD_ID=%BUILD_ID%
    echo set VERSION=%VERSION%
    echo set REPOSITORY=%REPOSITORY%
    echo set TIMESTAMP=%TIMESTAMP%

) > %BUILD_DIR%\buildinfo.bat

