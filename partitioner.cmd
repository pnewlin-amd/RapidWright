@echo off
setlocal
if defined PYTHON (
  "%PYTHON%" "%~dp0scripts\partition\partitioner.py" %*
) else (
  python "%~dp0scripts\partition\partitioner.py" %*
)
exit /b %errorlevel%
