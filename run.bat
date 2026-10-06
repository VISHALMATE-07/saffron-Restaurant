@echo off
REM ---------------------------------------------------------------
REM  Runs SAFFRON with ONLY the JDK (no Maven needed).
REM  It downloads the SQLite driver once into the "lib" folder.
REM ---------------------------------------------------------------
setlocal
cd /d "%~dp0"
set JAR=lib\sqlite-jdbc-3.41.2.2.jar

where javac >nul 2>nul
if errorlevel 1 (
  echo ERROR: JDK not found. Install JDK 21 and reopen this window.
  pause
  exit /b 1
)

if not exist lib mkdir lib
if not exist "%JAR%" (
  echo Downloading the SQLite driver ^(one time only^)...
  powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.41.2.2/sqlite-jdbc-3.41.2.2.jar' -OutFile '%JAR%'"
  if not exist "%JAR%" (
    echo ERROR: Download failed. Check your internet connection and try again.
    pause
    exit /b 1
  )
)

if not exist out mkdir out
javac -encoding UTF-8 -d out src\main\java\com\saffron\App.java src\main\java\com\saffron\Db.java src\main\java\com\saffron\Dao.java src\main\java\com\saffron\Models.java src\main\java\com\saffron\Ui.java
if errorlevel 1 (
  echo ERROR: Compile failed.
  pause
  exit /b 1
)
xcopy /y /q src\main\resources\* out\ >nul

java -cp "out;%JAR%" com.saffron.App
pause
