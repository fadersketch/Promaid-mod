@echo off
rem Compile promaid neo tree: run gen_compile_neo.py first to generate compile_neo.txt
cd /d "%~dp0"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
)
if not defined JAVAC (
  if exist "%APPDATA%\.minecraft\runtime\java-runtime-delta\bin\javac.exe" set "JAVAC=%APPDATA%\.minecraft\runtime\java-runtime-delta\bin\javac.exe"
)
if not defined JAVAC set "JAVAC=javac"
"%JAVAC%" @compile_neo.txt 2>&1
echo EXITCODE=%ERRORLEVEL%
