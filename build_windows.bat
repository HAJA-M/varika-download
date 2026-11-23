@echo off
rem Compile VarikaDownload.exe sous Windows (Python 3.10+ requis)
cd /d "%~dp0"
python -m pip install --upgrade pip
python -m pip install -r requirements.txt || goto :err
python -m PyInstaller --noconfirm --clean --onefile --windowed --name VarikaDownload --icon assets\varika-download.ico --add-data "ui;ui" --add-data "extension;extension" --version-file version_info.txt app.py || goto :err
echo.
echo Executable pret : dist\VarikaDownload.exe
pause
exit /b 0
:err
echo La compilation a echoue.
pause
exit /b 1
