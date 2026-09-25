@echo off
setlocal DisableDelayedExpansion

rem ============================================================================
rem migration0024ImageCatalogue.bat
rem
rem Reconcile existing image files with the persistent Image catalogue using
rem the Gradle wrapper from the top-level Diaries project.
rem
rem Usage:
rem
rem     migration0024ImageCatalogue.bat dry-run [0022_CANDIDATES_CSV]
rem     migration0024ImageCatalogue.bat apply   [0022_CANDIDATES_CSV]
rem
rem Run dry-run first. It scans the configured Files root and database without
rem changing them, then writes evidence beneath:
rem
rem     %USERPROFILE%\temp\dry-run
rem
rem Review 0024-create-plan.json, 0024-conflicts.csv and the other dry-run
rem evidence before running apply. Apply automatically reads the reviewed plan
rem from the dry-run directory and writes its own evidence beneath:
rem
rem     %USERPROFILE%\temp\apply
rem
rem The evidence directory for the requested mode must be empty. Archive and
rem empty both directories before starting another complete reconciliation.
rem The optional 0022 candidate CSV cross-references legacy embedded-image
rem candidates; when supplied for dry-run, supply the same unchanged file for
rem apply. The responder config defaults to %USERPROFILE%\.diaries\responder.json
rem and may be overridden with DIARIES_RESPONDER_CONFIG_FILE.
rem ============================================================================


rem ----------------------------------------------------------------------------
rem Initialise common script variables and select the requested mode.
rem
rem Apply is explicit and requires a plan from a reviewed dry-run. Keep delayed
rem expansion disabled so exclamation marks in paths are preserved.
rem ----------------------------------------------------------------------------

set "SCRIPT_DIR=%~dp0"
set "MODE=%~1"
set "PLAN="
set "CANDIDATES="
set "EXIT_CODE=0"
set "CONFIG=%USERPROFILE%\.diaries\responder.json"
set "EVIDENCE_ROOT=%USERPROFILE%\temp"
set "DRY_RUN_OUTPUT=%EVIDENCE_ROOT%\dry-run"
set "APPLY_OUTPUT=%EVIDENCE_ROOT%\apply"
if defined DIARIES_RESPONDER_CONFIG_FILE set "CONFIG=%DIARIES_RESPONDER_CONFIG_FILE%"

if /i "%MODE%"=="dry-run" goto :dry_run_args
if /i "%MODE%"=="apply" goto :apply_args
goto :usage


rem ----------------------------------------------------------------------------
rem Validate the dry-run arguments and select its fixed evidence directory.
rem The candidate inventory is optional.
rem ----------------------------------------------------------------------------

:dry_run_args
set "MODE=dry-run"
if not "%~3"=="" goto :usage
set "OUTPUT=%DRY_RUN_OUTPUT%"
if not "%~2"=="" set "CANDIDATES=%~f2"
goto :common_args


rem ----------------------------------------------------------------------------
rem Validate the apply arguments and select its fixed evidence directory.
rem The reviewed plan is always the plan produced in the dry-run directory.
rem ----------------------------------------------------------------------------

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


rem ----------------------------------------------------------------------------
rem Resolve input paths and prepare the fixed evidence directories.
rem
rem The default responder config matches run-responder.bat; an environment
rem override permits a deliberately selected alternate config. The migration
rem itself verifies that the selected dry-run or apply directory is empty.
rem ----------------------------------------------------------------------------

:common_args
for %%I in ("%CONFIG%") do set "CONFIG=%%~fI"
if not exist "%CONFIG%" (
    echo ERROR: Responder configuration not found: "%CONFIG%" >&2
    endlocal & exit /b 1
)

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


rem ----------------------------------------------------------------------------
rem Locate the top-level Diaries project directory.
rem
rem This script is located under:
rem
rem     diaries-responder\scripts\windows
rem
rem Moving up three levels therefore gives us the Diaries project root.
rem ----------------------------------------------------------------------------

pushd "%SCRIPT_DIR%..\..\.." >nul 2>&1
if errorlevel 1 (
    echo ERROR: Could not locate the Diaries project root. >&2
    echo Script directory: "%SCRIPT_DIR%" >&2
    endlocal & exit /b 1
)

set "PROJECT_DIR=%CD%"
set "GRADLE_WRAPPER=%PROJECT_DIR%\gradlew.bat"


rem ----------------------------------------------------------------------------
rem Validate the Gradle wrapper before attempting reconciliation.
rem ----------------------------------------------------------------------------

if not exist "%GRADLE_WRAPPER%" (
    echo ERROR: Gradle wrapper not found: "%GRADLE_WRAPPER%" >&2
    set "EXIT_CODE=1"
    goto :cleanup
)


rem ----------------------------------------------------------------------------
rem Run the requested reconciliation mode.
rem
rem Capture Gradle's exit code immediately so it can be returned unchanged to
rem the caller. Empty optional properties are ignored by the Gradle task.
rem ----------------------------------------------------------------------------

echo Running 0024 Image catalogue reconciliation in %MODE% mode...
call "%GRADLE_WRAPPER%" :diaries-responder:migration0024ImageCatalogue "-PmigrationConfig=%CONFIG%" "-PmigrationOutput=%OUTPUT%" "-PmigrationMode=%MODE%" "-PmigrationPlan=%PLAN%" "-Pmigration0022Candidates=%CANDIDATES%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo ERROR: Reconciliation failed with exit code %EXIT_CODE%. Inspect the evidence directory if one was created. >&2
    goto :cleanup
)

echo 0024 Image catalogue reconciliation completed. Review the evidence in "%OUTPUT%".


rem ----------------------------------------------------------------------------
rem Common cleanup and exit.
rem ----------------------------------------------------------------------------

:cleanup
popd
endlocal & exit /b %EXIT_CODE%


rem ----------------------------------------------------------------------------
rem Show valid invocations when arguments are missing or invalid.
rem ----------------------------------------------------------------------------

:usage
echo Usage: %~nx0 dry-run [0022_CANDIDATES_CSV] >&2
echo        %~nx0 apply [0022_CANDIDATES_CSV] >&2
echo Config: %%DIARIES_RESPONDER_CONFIG_FILE%% or %%USERPROFILE%%\.diaries\responder.json. >&2
echo Evidence: %%USERPROFILE%%\temp\dry-run and %%USERPROFILE%%\temp\apply. >&2
echo Run dry-run first, review its 0024-create-plan.json, then run apply. >&2
endlocal & exit /b 2
