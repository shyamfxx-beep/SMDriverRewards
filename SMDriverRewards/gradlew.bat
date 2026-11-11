@rem
@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
java -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*
endlocal
