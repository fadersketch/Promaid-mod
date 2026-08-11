@echo off
rem 编译 promaid 模组:先运行 gen_compile.py 生成 compile_promaid.txt,再运行本脚本
rem javac 查找顺序:JAVA_HOME -> Minecraft 运行时 -> PATH
cd /d "%~dp0"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
)
if not defined JAVAC (
  if exist "%APPDATA%\.minecraft\runtime\java-runtime-delta\bin\javac.exe" set "JAVAC=%APPDATA%\.minecraft\runtime\java-runtime-delta\bin\javac.exe"
)
if not defined JAVAC set "JAVAC=javac"
"%JAVAC%" @compile_promaid.txt 2>&1
echo EXITCODE=%ERRORLEVEL%
