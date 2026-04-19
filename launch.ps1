$adb='C:\Users\fandr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$em='emulator-5554'
& $adb -s $em shell settings put global window_animation_scale 0.5 | Out-Null
& $adb -s $em shell settings put global transition_animation_scale 0.5 | Out-Null
& $adb -s $em shell settings put global animator_duration_scale 0.5 | Out-Null
& $adb -s $em shell am start-foreground-service -n com.meemaw.defender/.DefenderService | Out-Null
Start-Sleep -Seconds 2
& $adb -s $em shell monkey -p com.meemaw.assist -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 6
& $adb -s $em shell "dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'"
& $adb -s $em exec-out screencap -p > 'C:\Users\fandr\Desktop\HACK V2\emulator-live.png'
Write-Host "shot-saved"
