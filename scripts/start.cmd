@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA set "JAVA=java"
"%JAVA%" %JAVA_OPTS% -cp "Universe.jar;lib/*" com.nekoyu.Universe.Universe %*
exit /b %ERRORLEVEL%
