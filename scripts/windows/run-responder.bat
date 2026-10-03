@echo off
setlocal

rem ============================================================================
rem run-responder.bat
rem
rem Build and run the Diaries responder using the Gradle Application
rem distribution. Direct development uses a generated effective responder
rem config so DIARIES_FILES_DIR follows the same environment precedence as the
rem development-infrastructure database selection.
rem ============================================================================

set "SCRIPT_DIR=%~dp0"
set "EXIT_CODE=0"

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "RESPONDER_DIR=%PROJECT_DIR%\diaries-responder"
set "GRADLE_WRAPPER=%PROJECT_DIR%\gradlew.bat"
set "LAUNCHER=%RESPONDER_DIR%\build\install\diaries-responder\bin\diaries-responder.bat"
set "PREPARE_CONFIG=%PROJECT_DIR%\scripts\windows\development-infrastructure\prepare-responder-config.bat"

set "HIBERNATE_LOGLEVEL=OFF"
set "LOGLEVEL=INFO"

if not exist "%RESPONDER_DIR%" (
    echo ERROR: Responder directory not found: "%RESPONDER_DIR%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

if not exist "%GRADLE_WRAPPER%" (
    echo ERROR: Gradle wrapper not found: "%GRADLE_WRAPPER%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

if not exist "%PREPARE_CONFIG%" (
    echo ERROR: Direct-development configuration helper not found: "%PREPARE_CONFIG%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

rem Generate the ignored effective JSON from the developer-owned base. The
rem helper loads development-infrastructure.env followed by local.env, validates
rem the database/Files selectors and prints only non-secret effective paths.
call "%PREPARE_CONFIG%"
if errorlevel 1 (
    set "EXIT_CODE=1"
    goto :cleanup
)
set "CONFIG_FILE=%DIARIES_EFFECTIVE_RESPONDER_CONFIG%"

if not defined CONFIG_FILE (
    echo ERROR: Effective responder configuration was not selected. >&2
    set "EXIT_CODE=1"
    goto :cleanup
)
if not exist "%CONFIG_FILE%" (
    echo ERROR: Effective responder configuration not found: "%CONFIG_FILE%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo.
echo Preparing the Diaries responder runtime distribution...
call "%GRADLE_WRAPPER%" :diaries-responder:installDist
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
    echo ERROR: Could not prepare the Diaries responder runtime distribution. >&2
    goto :cleanup
)

if not exist "%LAUNCHER%" (
    echo ERROR: Diaries responder launcher was not created: "%LAUNCHER%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo Starting Diaries responder...
echo Configuration: "%CONFIG_FILE%"
echo.
call "%LAUNCHER%" --config "%CONFIG_FILE%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo ERROR: Diaries responder exited with code %EXIT_CODE%. >&2
)

:cleanup
popd
endlocal & exit /b %EXIT_CODE%
