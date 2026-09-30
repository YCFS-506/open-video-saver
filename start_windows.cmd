@echo off
cd /d "%~dp0"
if exist ".venv\Scripts\pythonw.exe" (
    start "" ".venv\Scripts\pythonw.exe" "app.pyw"
) else (
    echo Please follow the source installation steps in README.md first.
    echo Expected interpreter: .venv\Scripts\pythonw.exe
    pause
)
