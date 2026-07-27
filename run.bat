@echo off
setlocal EnableDelayedExpansion
title Manga Coloring Tool 2 - Launcher

:: ==========================================
:: CONFIGURATION
:: ==========================================
set "ROOT_DIR=%~dp0"
set "COMFY_DIR=%ROOT_DIR%ComfyUI_windows_portable"
set "PYTHON_EXE=%COMFY_DIR%\python_embeded\python.exe"
set "COMFY_MAIN=%COMFY_DIR%\ComfyUI\main.py"
set "FASTAPI_APP=%ROOT_DIR%server.py"
set "APP_URL=http://127.0.0.1:7860"
set "COMFY_PORT=8188"
set "APP_PORT=7860"
set "MODELS_DIR=%COMFY_DIR%\ComfyUI\models"
set "TEXT_ENCODERS_DIR=%MODELS_DIR%\text_encoders"
set "DIFFUSION_MODELS_DIR=%MODELS_DIR%\diffusion_models"
set "VAE_DIR=%MODELS_DIR%\vae"

cls
echo.
PowerShell -NoP -C "Write-Host '=============================================' -ForegroundColor DarkMagenta"
PowerShell -NoP -C "Write-Host '          MANGA COLORING TOOL 2              ' -ForegroundColor Magenta"
PowerShell -NoP -C "Write-Host '   FLUX-2 Klein 4B  +  ComfyUI  +  FastAPI   ' -ForegroundColor DarkCyan"
PowerShell -NoP -C "Write-Host '=============================================' -ForegroundColor DarkMagenta"
echo.

:: ==========================================
:: 1. BOOTSTRAP: AUTONOMOUS COMFYUI INSTALLATION
:: ==========================================
if not exist "%COMFY_DIR%" (
    PowerShell -NoP -C "Write-Host '[1/5] ComfyUI not detected. Starting automatic installation...' -ForegroundColor Yellow"
    echo.

    if not exist "%ROOT_DIR%7zr.exe" (
        PowerShell -NoP -C "Write-Host '  Downloading 7zr.exe...' -ForegroundColor Cyan"
        curl.exe -# -L -o "%ROOT_DIR%7zr.exe" "https://7-zip.org/a/7zr.exe"
        if errorlevel 1 goto :error_7zr
    )

    if not exist "%ROOT_DIR%ComfyUI_windows_portable_nvidia.7z" (
        echo.
        PowerShell -NoP -C "Write-Host '  Downloading ComfyUI Portable (~1.8 GB), please wait...' -ForegroundColor Cyan"
        curl.exe -# -L -o "%ROOT_DIR%ComfyUI_windows_portable_nvidia.7z" "https://github.com/comfyanonymous/ComfyUI/releases/latest/download/ComfyUI_windows_portable_nvidia.7z"
        if errorlevel 1 goto :error_comfy_dl
    )

    echo.
    PowerShell -NoP -C "Write-Host '  Extracting, this may take a few minutes...' -ForegroundColor Cyan"
    "%ROOT_DIR%7zr.exe" x "%ROOT_DIR%ComfyUI_windows_portable_nvidia.7z" -o"%ROOT_DIR%" -y >nul
    if errorlevel 1 goto :error_extract

    del "%ROOT_DIR%7zr.exe" 2>nul
    del "%ROOT_DIR%ComfyUI_windows_portable_nvidia.7z" 2>nul
    PowerShell -NoP -C "Write-Host '  [OK] ComfyUI installed successfully.' -ForegroundColor Green"
    echo.
)

:: ==========================================
:: 2. PYTHON DEPENDENCIES
:: ==========================================
PowerShell -NoP -C "Write-Host '[2/5] Checking Python dependencies...' -ForegroundColor Cyan"
"%PYTHON_EXE%" -c "import gradio, requests, websocket, PIL, numpy, scipy, fastapi, uvicorn, multipart" >nul 2>&1
if not errorlevel 1 (
    PowerShell -NoP -C "Write-Host '  [OK] Python dependencies verified.' -ForegroundColor Green"
    echo.
) else (
    "%PYTHON_EXE%" -m pip install --quiet --no-warn-script-location gradio requests websocket-client pillow numpy scipy fastapi uvicorn python-multipart
    if errorlevel 1 (
        "%PYTHON_EXE%" -c "import gradio, requests, websocket, PIL, numpy, scipy, fastapi, uvicorn, multipart" >nul 2>&1
        if errorlevel 1 goto :error_pip
    )
    PowerShell -NoP -C "Write-Host '  [OK] Python dependencies ready.' -ForegroundColor Green"
    echo.
)

:: ==========================================
:: 3. MODEL DOWNLOADS
:: ==========================================
PowerShell -NoP -C "Write-Host '[3/5] Checking AI models...' -ForegroundColor Cyan"

if not exist "%TEXT_ENCODERS_DIR%"    mkdir "%TEXT_ENCODERS_DIR%"
if not exist "%DIFFUSION_MODELS_DIR%" mkdir "%DIFFUSION_MODELS_DIR%"
if not exist "%VAE_DIR%"              mkdir "%VAE_DIR%"

set "ALL_MODELS_OK=1"

if not exist "%TEXT_ENCODERS_DIR%\qwen_3_4b.safetensors" (
    set "ALL_MODELS_OK=0"
    PowerShell -NoP -C "Write-Host '  Downloading QWEN Text Encoder...' -ForegroundColor Yellow"
    curl.exe -# -L -o "%TEXT_ENCODERS_DIR%\qwen_3_4b.safetensors" "https://huggingface.co/Comfy-Org/flux2-klein-4B/resolve/main/split_files/text_encoders/qwen_3_4b.safetensors"
    if errorlevel 1 goto :error_qwen
)

if not exist "%DIFFUSION_MODELS_DIR%\flux-2-klein-4b-fp8.safetensors" (
    set "ALL_MODELS_OK=0"
    PowerShell -NoP -C "Write-Host '  Downloading FLUX Diffusion Model...' -ForegroundColor Yellow"
    curl.exe -# -L -o "%DIFFUSION_MODELS_DIR%\flux-2-klein-4b-fp8.safetensors" "https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8/resolve/main/flux-2-klein-4b-fp8.safetensors"
    if errorlevel 1 goto :error_flux
)

if not exist "%VAE_DIR%\flux2-vae.safetensors" (
    set "ALL_MODELS_OK=0"
    PowerShell -NoP -C "Write-Host '  Downloading FLUX VAE...' -ForegroundColor Yellow"
    curl.exe -# -L -o "%VAE_DIR%\flux2-vae.safetensors" "https://huggingface.co/Comfy-Org/flux2-dev/resolve/main/split_files/vae/flux2-vae.safetensors"
    if errorlevel 1 goto :error_vae
)

if "%ALL_MODELS_OK%"=="1" (
    PowerShell -NoP -C "Write-Host '  [OK] All models present.' -ForegroundColor Green"
) else (
    PowerShell -NoP -C "Write-Host '  [OK] Model downloads completed.' -ForegroundColor Green"
)
echo.

:: ==========================================
:: 4. FRONTEND BUILD (IF DIST DOES NOT EXIST)
:: ==========================================
PowerShell -NoP -C "Write-Host '[4/5] Checking Web App Frontend...' -ForegroundColor Cyan"
if not exist "%ROOT_DIR%frontend\dist" (
    PowerShell -NoP -C "Write-Host '  Building React Frontend...' -ForegroundColor Yellow"
    cmd.exe /c "cd /d ""%ROOT_DIR%frontend"" && npm install --no-fund --no-audit && npm run build"
    if errorlevel 1 goto :error_build
    PowerShell -NoP -C "Write-Host '  [OK] React Frontend built successfully.' -ForegroundColor Green"
) else (
    PowerShell -NoP -C "Write-Host '  [OK] Frontend ready.' -ForegroundColor Green"
)
echo.

:: ==========================================
:: 5. SYSTEM STARTUP
:: ==========================================
PowerShell -NoP -C "Write-Host '[5/5] Starting services...' -ForegroundColor Cyan"

:: — Check if ComfyUI is already running
call :port_in_use %COMFY_PORT%
if "!PORT_USED!"=="1" (
    PowerShell -NoP -C "Write-Host '  [OK] ComfyUI is already running.' -ForegroundColor Green"
) else (
    PowerShell -NoP -C "Write-Host '  Starting ComfyUI Engine...' -ForegroundColor Cyan"
    start "ComfyUI Engine" /min "%PYTHON_EXE%" "%COMFY_MAIN%" --port %COMFY_PORT% --listen 127.0.0.1
    echo.
    PowerShell -NoP -C "Write-Host '  Waiting for ComfyUI to start...' -ForegroundColor Yellow"
    call :wait_for_port %COMFY_PORT% 90
    if "!PORT_READY!"=="0" (
        PowerShell -NoP -C "Write-Host '[ERROR] ComfyUI did not respond in 90 seconds.' -ForegroundColor Red"
        PowerShell -NoP -C "Write-Host 'Check the ComfyUI window to see the error.' -ForegroundColor Red"
        pause
        exit /b 1
    )
    PowerShell -NoP -C "Write-Host '  [OK] ComfyUI ready.' -ForegroundColor Green"
)

:: — Check if App is already running
call :port_in_use %APP_PORT%
if "!PORT_USED!"=="1" (
    PowerShell -NoP -C "Write-Host '  Port %APP_PORT% already in use. Opening browser...' -ForegroundColor Yellow"
    start %APP_URL%
    goto :done
)

echo.
PowerShell -NoP -C "Write-Host '  Launching Manga Coloring Tool 2 Web App...' -ForegroundColor Cyan"
PowerShell -NoP -C "Write-Host '  The browser will open automatically as soon as ComfyUI is ready.' -ForegroundColor White"
PowerShell -NoP -C "Write-Host '  Web App URL: http://127.0.0.1:7860' -ForegroundColor White"
echo.

cd /d "%ROOT_DIR%"
"%PYTHON_EXE%" "%FASTAPI_APP%"

:done
echo.
PowerShell -NoP -C "Write-Host '  Manga Coloring Tool 2 closed.' -ForegroundColor Magenta"
pause
exit /b 0

:: ==========================================
:: ERROR MESSAGES
:: ==========================================
:error_7zr
PowerShell -NoP -C "Write-Host '[ERROR] Failed to download 7zr.exe' -ForegroundColor Red"
pause & exit /b 1
:error_comfy_dl
PowerShell -NoP -C "Write-Host '[ERROR] Failed to download ComfyUI' -ForegroundColor Red"
pause & exit /b 1
:error_extract
PowerShell -NoP -C "Write-Host '[ERROR] Failed to extract ComfyUI' -ForegroundColor Red"
pause & exit /b 1
:error_pip
PowerShell -NoP -C "Write-Host '[ERROR] Failed to install Python dependencies' -ForegroundColor Red"
pause & exit /b 1
:error_qwen
PowerShell -NoP -C "Write-Host '[ERROR] Failed to download QWEN' -ForegroundColor Red"
pause & exit /b 1
:error_flux
PowerShell -NoP -C "Write-Host '[ERROR] Failed to download FLUX' -ForegroundColor Red"
pause & exit /b 1
:error_vae
PowerShell -NoP -C "Write-Host '[ERROR] Failed to download VAE' -ForegroundColor Red"
pause & exit /b 1
:error_build
PowerShell -NoP -C "Write-Host '[ERROR] Failed to build React Frontend' -ForegroundColor Red"
pause & exit /b 1

:: ==========================================
:: SUBRUTINAS
:: ==========================================
:port_in_use
set "PORT_USED=0"
netstat -ano | findstr ":%~1 " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 set "PORT_USED=1"
exit /b

:wait_for_port
set "PORT_READY=0"
set /a "MAX_TRIES=%~2/2"
set "TRY=0"
:wait_loop
timeout /t 2 /nobreak >nul
set /a "TRY+=1"
netstat -ano | findstr ":%~1 " | findstr "LISTENING" >nul 2>&1
if not errorlevel 1 (
    set "PORT_READY=1"
    exit /b
)
if !TRY! LSS !MAX_TRIES! goto :wait_loop
exit /b