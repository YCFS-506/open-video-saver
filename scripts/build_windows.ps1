$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pythonPath = Join-Path $projectRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) {
    throw 'Create .venv and install requirements-build.txt as described in README.md.'
}

Push-Location $projectRoot
try {
    & $pythonPath -m PyInstaller --noconfirm --clean --windowed --onedir `
        --name OpenVideoSaver --collect-all playwright `
        --hidden-import bilibili --hidden-import kuaishou `
        --hidden-import xiaohongshu --hidden-import image_posts app.pyw
    if ($LASTEXITCODE -ne 0) {
        throw 'PyInstaller build failed.'
    }
    $bundle = Join-Path $projectRoot 'dist\OpenVideoSaver'
    foreach ($name in @('README.md', 'LICENSE')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination $bundle -Force
    }
    $archive = Join-Path $projectRoot 'dist\OpenVideoSaver-windows.zip'
    Compress-Archive -Path $bundle -DestinationPath $archive -Force
    Write-Output "Created $archive"
    Write-Output 'Microsoft Edge and FFmpeg/FFprobe on PATH are required on the target computer.'
}
finally {
    Pop-Location
}
