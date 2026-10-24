@echo off
rem Windows twin of scripts/run.
rem   scripts\run.cmd server [args]
rem   scripts\run.cmd client [args]
rem   scripts\run.cmd driver processor^|optimizer^|storage^|concurrency^|recovery [args]
rem   scripts\run.cmd reset-data
rem cmd.exe splits arguments on "=" and ",", so quote flags: "--mock=storage,recovery"
setlocal
set "ROOT=%~dp0.."
set "JAR=%ROOT%\target\rafendb.jar"
set "MAIN="

if /I "%~1"=="reset-data" goto reset
if /I "%~1"=="server" set "MAIN=org.rafendb.server.ServerMain"
if /I "%~1"=="client" set "MAIN=org.rafendb.client.ClientMain"
if /I "%~1"=="driver" goto driver
goto run

:driver
if /I "%~2"=="processor"   set "MAIN=org.rafendb.processor.driver.ProcessorDriver"
if /I "%~2"=="optimizer"   set "MAIN=org.rafendb.optimizer.driver.OptimizerDriver"
if /I "%~2"=="storage"     set "MAIN=org.rafendb.storage.driver.StorageDriver"
if /I "%~2"=="concurrency" set "MAIN=org.rafendb.concurrency.driver.ConcurrencyDriver"
if /I "%~2"=="recovery"    set "MAIN=org.rafendb.recovery.driver.RecoveryDriver"
shift

:run
if not defined MAIN goto usage
shift
if not exist "%JAR%" (
  echo target\rafendb.jar not found. Build it via mvnw.cmd -q package -DskipTests
  exit /b 1
)
cd /d "%ROOT%"
java -cp "%JAR%" %MAIN% %1 %2 %3 %4 %5 %6 %7 %8 %9
exit /b %ERRORLEVEL%

:reset
if exist "%ROOT%\data" rmdir /s /q "%ROOT%\data"
echo Removed data, next run will recreates it from the seed.
exit /b 0

:usage
echo Usage: scripts\run.cmd server ^| client ^| driver ^<component^> ^| reset-data
exit /b 2