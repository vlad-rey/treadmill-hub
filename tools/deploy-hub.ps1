# Сборка и установка хаба на телефон (нужен root: MIUI блокирует adb install).
# Запуск: powershell -ExecutionPolicy Bypass -File tools\deploy-hub.ps1 -Serial <IP>:5555 [-NoBuild]

param([Parameter(Mandatory)][string]$Serial, [switch]$NoBuild)

$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot
$Adb = 'C:\Users\vreds\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$Pkg = 'io.github.vladrey.treadmillhub'
$Apk = "$Root\android\app\build\outputs\apk\debug\app-debug.apk"

if (-not $NoBuild) {
    $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot'
    Push-Location "$Root\android"
    try { .\gradlew.bat testDebugUnitTest assembleDebug --console=plain -q; if ($LASTEXITCODE) { throw 'сборка не удалась' } }
    finally { Pop-Location }
}

& $Adb -s $Serial push $Apk /data/local/tmp/treadmill-hub.apk | Out-Null
& $Adb -s $Serial shell "su -c 'pm install -r /data/local/tmp/treadmill-hub.apk && rm /data/local/tmp/treadmill-hub.apk'"

# Разрешения (Android 9: для BLE-сканирования нужна геолокация) и исключение из ограничений MIUI/Doze
& $Adb -s $Serial shell "su -c 'pm grant $Pkg android.permission.ACCESS_FINE_LOCATION; pm grant $Pkg android.permission.ACCESS_COARSE_LOCATION; dumpsys deviceidle whitelist +$Pkg; cmd appops set $Pkg RUN_IN_BACKGROUND allow'"

# Перезапуск сервиса
& $Adb -s $Serial shell "su -c 'am force-stop $Pkg; am start-foreground-service -n $Pkg/.HubService'"
Write-Host "хаб установлен и запущен: http://$($Serial.Split(':')[0]):8080"
