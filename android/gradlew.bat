@echo off
setlocal
set VERSION=8.13
set SHA256=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE_ROOT=%GRADLE_USER_HOME%\dayf-distributions
set DIST_DIR=%CACHE_ROOT%\gradle-%VERSION%
if exist "%DIST_DIR%\bin\gradle.bat" goto run
if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
set ARCHIVE=%CACHE_ROOT%\gradle-%VERSION%-bin.zip
if not exist "%ARCHIVE%" powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ARCHIVE%'"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $actual=(Get-FileHash -Algorithm SHA256 '%ARCHIVE%').Hash.ToLowerInvariant(); if ($actual -ne '%SHA256%') { Remove-Item '%ARCHIVE%' -Force; throw 'Gradle distribution checksum mismatch; refusing to execute.' }; Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%CACHE_ROOT%' -Force"
if not exist "%DIST_DIR%\bin\gradle.bat" exit /b 1
:run
call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
