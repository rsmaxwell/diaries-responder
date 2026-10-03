@echo off
setlocal DisableDelayedExpansion

rem ============================================================================
rem migration0024ImageCatalogue.bat
rem
rem Reconcile existing image files with the persistent Image catalogue using
rem the same generated direct-development responder configuration as
rem run-responder.bat. This keeps the database and Files selection paired when
rem local.env changes.
rem
rem Usage:
rem
rem     migration0024ImageCatalogue.bat dry-run [0022_CANDIDATES_CSV]
rem     migration0024ImageCatalogue.bat apply   [0022_CANDIDATES_CSV]
rem
rem Run dry-run first. It scans the configured Files root and database without
rem changing them, then writes evidence beneath %USERPROFILE%\temp\dry-run.
rem Apply reads the reviewed dry-run plan and writes evidence beneath
rem %USERPROFILE%\temp\apply.
rem
rem DIARIES_RESPONDER_CONFIG_FILE remains supported as an alternate developer-
rem owned base config for compatibility. The shared preparation helper still
rem applies the effective DIARIES_FILES_DIR before this migration runs.
rem ============================================================================

set "SCRIPT_DIR=%~dp0"
set "MODE=%~1"
set "PLAN="
set "CANDIDATES="
set "EXIT_CODE=0"
set "EVIDENCE_ROOT=%USERPROFILE%\temp"
set "DRY_RUN_OUTPUT=%EVIDENCE_ROOT%\dry-run"
set "APPLY_OUTPUT=%EVIDENCE_ROOT%\apply"

if /i "%MODE%"=="dry-run" goto :dry_run_args
if /i "%MODE%"=="apply" goto :apply_args
goto :usage

:dry_run_args
set "MODE=dry-run"
if not "%~3"=="" goto :usage
set "OUTPUT=%DRY_RUN_OUTPUT%"
if not "%~2"=="" set "CANDIDATES=%~f2"
goto :common_args

:apply_args
set "MODE=apply"
if not "%~3"=="" goto :usage
set "OUTPUT=%APPLY_OUTPUT%"
set "PLAN=%DRY_RUN_OUTPUT%\0024-create-plan.json"
if not exist "%PLAN%" (
    echo ERROR: Reviewed dry-run plan not found: "%PLAN%" >&2
    endlocal & exit /b 1
)
if not "%~2"=="" set "CANDIDATES=%~f2"

:common_args
if not exist "%EVIDENCE_ROOT%" mkdir "%EVIDENCE_ROOT%" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not create evidence root: "%EVIDENCE_ROOT%" >&2
    endlocal & exit /b 1
)
if not exist "%DRY_RUN_OUTPUT%" mkdir "%DRY_RUN_OUTPUT%" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not create dry-run evidence directory: "%DRY_RUN_OUTPUT%" >&2
    endlocal & exit /b 1
)
if not exist "%APPLY_OUTPUT%" mkdir "%APPLY_OUTPUT%" >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not create apply evidence directory: "%APPLY_OUTPUT%" >&2
    endlocal & exit /b 1
)
if defined CANDIDATES if not exist "%CANDIDATES%" (
    echo ERROR: 0022 candidate inventory not found: "%CANDIDATES%" >&2
    endlocal & exit /b 1
)

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "GRADLE_WRAPPER=%PROJECT_DIR%\gradlew.bat"
set "PREPARE_CONFIG=%PROJECT_DIR%\scripts\windows\development-infrastructure\prepare-responder-config.bat"

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

rem Use exactly the same effective responder configuration preparation path as
rem run-responder.bat. This is the guard that keeps reconciliation on the same
rem physical Files root as the direct responder for the selected local dataset.
call "%PREPARE_CONFIG%"
if errorlevel 1 (
    set "EXIT_CODE=1"
    goto :cleanup
)
set "CONFIG=%DIARIES_EFFECTIVE_RESPONDER_CONFIG%"
if not defined CONFIG (
    echo ERROR: Effective responder configuration was not selected. >&2
    set "EXIT_CODE=1"
    goto :cleanup
)
if not exist "%CONFIG%" (
    echo ERROR: Effective responder configuration not found: "%CONFIG%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)

echo.
echo Running 0024 Image catalogue reconciliation in %MODE% mode...
call "%GRADLE_WRAPPER%" :diaries-responder:migration0024ImageCatalogue "-PmigrationConfig=%CONFIG%" "-PmigrationOutput=%OUTPUT%" "-PmigrationMode=%MODE%" "-PmigrationPlan=%PLAN%" "-Pmigration0022Candidates=%CANDIDATES%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Reconciliation failed with exit code %EXIT_CODE%. Inspect the evidence directory if one was created. >&2
    goto :cleanup
)

echo 0024 Image catalogue reconciliation completed. Review the evidence in "%OUTPUT%".

:cleanup
popd
endlocal & exit /b %EXIT_CODE%

:usage
echo Usage: %~nx0 dry-run [0022_CANDIDATES_CSV] >&2
echo        %~nx0 apply [0022_CANDIDATES_CSV] >&2
echo Base config: %%DIARIES_RESPONDER_BASE_CONFIG_FILE%%, %%DIARIES_RESPONDER_CONFIG_FILE%%, or %%USERPROFILE%%\.diaries\responder.json. >&2
echo Effective Files selection: development-infrastructure.env then local.env. >&2
echo Evidence: %%USERPROFILE%%\temp\dry-run and %%USERPROFILE%%\temp\apply. >&2
echo Run dry-run first, review its 0024-create-plan.json, then run apply. >&2
endlocal & exit /b 2
