@echo off
rem One-click build chain for maidmods (heartfelt_connection + promaid):
rem   1. fix stale classpath in compile_addon.txt
rem   2. regenerate compile_promaid.txt / compile_heartfelt.txt
rem   3. compile both mods (javac)
rem   4. package both jars into patched/
rem   5. install to BOTH mods dirs (old jars are deleted, only latest kept):
rem      - D:\.minecraft\mods                          (PCL without version isolation)
rem      - D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods  (PCL version isolation,
rem        the one PCL actually loads as of 2026-08-14)
rem ASCII only (cmd codepage): any log text in English.
cd /d "%~dp0"

set "HF_NAME=heartfelt_connection-1.0.0.jar"
set "PM_NAME=promaid-1.0.3.jar"
set "MODS_ROOTS=D:\.minecraft\mods D:\.minecraft\versions\1.20.1-Forge_47.4.21\mods"

echo [1/5] fix classpath
if exist fix_classpath.py (
  python fix_classpath.py || goto :fail
) else (
  echo fix_classpath.py not found - skip (compile_addon.txt classpath already on .minecraft\libraries)
)

echo [2/5] regenerate compile arg files
python gen_compile.py || goto :fail

echo [3/5] compile heartfelt_connection
call compile_heartfelt.bat || goto :fail

echo [3b/5] compile promaid (best-effort)
if exist compile_promaid.bat call compile_promaid.bat

echo [4/5] package jars
python build_heartfelt.py || goto :fail
if exist build_promaid.py python build_promaid.py

echo [5/5] install to mods dirs
for %%R in (%MODS_ROOTS%) do (
  if exist "%%R" (
    for %%j in ("%%R\heartfelt_connection-*.jar") do del /q "%%j" >nul
    copy /y "patched\%HF_NAME%" "%%R\%HF_NAME%" >nul
    if exist "patched\%PM_NAME%" (
      rem 清理所有旧版本 promaid jar（大小写都清；同 modId 双 jar 会冲突崩溃）
      for %%j in ("%%R\Promaid-*.jar") do del /q "%%j" >nul
      for %%j in ("%%R\promaid-*.jar") do del /q "%%j" >nul
      copy /y "patched\%PM_NAME%" "%%R\%PM_NAME%" >nul
      echo   installed %PM_NAME% to %%R
    )
    echo   installed %HF_NAME% to %%R
  )
)
echo.
echo ALL DONE - jars installed
goto :eof

:fail
echo BUILD FAILED - see output above
exit /b 1

