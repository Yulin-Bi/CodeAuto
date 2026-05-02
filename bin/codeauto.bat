@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..\
set JAR=%PROJECT_DIR%target\codeauto-0.1.0-SNAPSHOT-shaded.jar

if not exist "%JAR%" (
    echo Building codeauto (first launch^)...
    pushd "%PROJECT_DIR%"
    call mvn -q package -DskipTests 2>nul
    popd
    if errorlevel 1 (
        echo Error: build failed. Run 'mvn package -DskipTests' in codeauto/ manually.
        exit /b 1
    )
)

java -jar "%JAR%" %*
