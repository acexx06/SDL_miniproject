@echo off
rem ============================================================================
rem  Scalable Hospital Management System - run script (Windows)
rem
rem    run.bat                 build if needed, start the application, open the browser
rem    run.bat start [args]    same; extra args go to the application,
rem                            e.g.  run.bat start --hms.kv.nodes=5
rem    run.bat build           compile everything and run the unit tests
rem    run.bat test            run the unit tests only
rem    run.bat demo            scripted walk-through of every workflow (application must be running)
rem    run.bat bench [args]    performance evaluation, e.g.  run.bat bench all --quick
rem    run.bat help
rem
rem  Settings (set before calling):
rem    HMS_PORT        port of the web application            (default 8088)
rem    HMS_NO_BROWSER  set to 1 to not open the browser
rem ============================================================================
setlocal
cd /d "%~dp0"

if "%HMS_PORT%"=="" set "HMS_PORT=8088"
set "APP_JAR=hms-app\target\hms-app.jar"
set "BENCH_JAR=hms-bench\target\hms-bench.jar"

set "COMMAND=%~1"
set "ALL_ARGS=%*"
set "REST="
if not "%COMMAND%"=="" call set "REST=%%ALL_ARGS:*%~1=%%"
if "%COMMAND%"=="" set "COMMAND=start"

if /i "%COMMAND%"=="start" goto start
if /i "%COMMAND%"=="build" goto build
if /i "%COMMAND%"=="test"  goto test
if /i "%COMMAND%"=="demo"  goto demo
if /i "%COMMAND%"=="bench" goto bench
if /i "%COMMAND%"=="help"  goto help
echo Unknown command "%COMMAND%".
echo.
goto help

rem ---------------------------------------------------------------- commands

:start
call :need_java || exit /b 1
call :need_jars || exit /b 1
call :need_mongo || exit /b 1
call :port_open %HMS_PORT%
if not errorlevel 1 (
    echo [error] Port %HMS_PORT% is already in use.
    echo         Choose another one, for example:   set HMS_PORT=8089 ^& run.bat
    exit /b 1
)
echo.
echo   Starting the Hospital Management System on http://localhost:%HMS_PORT%
echo   Demo logins:  patient@hms.test / Patient@12345      doctor@hms.test / Doctor@12345
echo                 pharmacist@hms.test / Pharma@12345    admin@hms.test / Admin@12345
echo   Press Ctrl+C to stop.
echo.
if not "%HMS_NO_BROWSER%"=="1" start "" /b powershell -NoProfile -WindowStyle Hidden -Command "for ($i = 0; $i -lt 90; $i++) { try { Invoke-WebRequest -UseBasicParsing 'http://localhost:%HMS_PORT%/api/health' | Out-Null; Start-Process 'http://localhost:%HMS_PORT%'; break } catch { Start-Sleep -Seconds 1 } }"
java -Xmx1g -jar "%APP_JAR%" --server.port=%HMS_PORT% %REST%
exit /b %errorlevel%

:build
call :need_java || exit /b 1
call :need_maven || exit /b 1
echo Building all modules and running the unit tests ...
call mvn -q package
if errorlevel 1 (
    echo [error] The build failed.
    exit /b 1
)
echo Build finished: %APP_JAR% and %BENCH_JAR%
exit /b 0

:test
call :need_java || exit /b 1
call :need_maven || exit /b 1
call mvn test
exit /b %errorlevel%

:demo
call :need_java || exit /b 1
call :need_jars || exit /b 1
call :port_open %HMS_PORT%
if errorlevel 1 (
    echo [error] The application is not running on port %HMS_PORT%.
    echo         Start it first in another window with:   run.bat
    exit /b 1
)
java -jar "%BENCH_JAR%" demo --url http://localhost:%HMS_PORT% %REST%
exit /b %errorlevel%

:bench
call :need_java || exit /b 1
call :need_jars || exit /b 1
call :need_mongo || exit /b 1
if "%REST%"=="" (
    java -jar "%BENCH_JAR%" help
    echo.
    echo Examples:   run.bat bench all --quick        short trial of everything
    echo             run.bat bench components         the seven components on their own
    echo             run.bat bench load               load levels and baselines
    echo             run.bat bench timeline           kill, restart and add a cache server under load
    echo             run.bat bench report             redraw the graphs and docs\RESULTS.md
    exit /b 0
)
java -Xmx2g -jar "%BENCH_JAR%" %REST%
exit /b %errorlevel%

:help
echo Usage:
echo   run.bat                 build if needed, start the application, open the browser
echo   run.bat start [args]    same; extra args go to the application
echo   run.bat build           compile everything and run the unit tests
echo   run.bat test            run the unit tests only
echo   run.bat demo            scripted walk-through of every workflow, application must be running
echo   run.bat bench [args]    performance evaluation, e.g.  run.bat bench all --quick
echo.
echo Settings:  HMS_PORT (default 8088),  HMS_NO_BROWSER=1 to not open the browser
exit /b 0

rem ---------------------------------------------------------------- checks

:need_java
where java >nul 2>&1
if errorlevel 1 (
    echo [error] Java was not found. Install JDK 17 or newer and make sure "java" is on the PATH.
    exit /b 1
)
exit /b 0

:need_maven
where mvn >nul 2>&1
if errorlevel 1 (
    echo [error] Maven was not found. Install Maven 3.9 or newer and make sure "mvn" is on the PATH.
    exit /b 1
)
exit /b 0

:need_jars
if exist "%APP_JAR%" if exist "%BENCH_JAR%" exit /b 0
call :need_maven || exit /b 1
echo First run: building the project, this takes about a minute ...
call mvn -q package -DskipTests
if errorlevel 1 (
    echo [error] The build failed.
    exit /b 1
)
exit /b 0

:need_mongo
call :port_open 27017
if errorlevel 1 (
    echo [error] MongoDB is not reachable on localhost:27017.
    echo         Start it, for example from an Administrator prompt:   net start MongoDB
    exit /b 1
)
exit /b 0

rem  Sets errorlevel 0 if something is listening on localhost:%1, otherwise 1.
:port_open
powershell -NoProfile -Command "$c = New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', %1); exit 0 } catch { exit 1 } finally { $c.Dispose() }" >nul 2>&1
exit /b %errorlevel%
