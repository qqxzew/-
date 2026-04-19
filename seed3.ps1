$adb = 'C:\Users\fandr\AppData\Local\Android\Sdk\platform-tools\adb.exe'

# Clear any existing contacts
& $adb shell content delete --uri content://com.android.contacts/raw_contacts | Out-Null

# Push helper script
& $adb push 'C:\Users\fandr\Desktop\HACK V2\add_contact.sh' /data/local/tmp/add_contact.sh | Out-Null
& $adb shell chmod 755 /data/local/tmp/add_contact.sh | Out-Null

# Demo contacts (English names, diverse roles, for call / SMS testing)
$contacts = @(
    @{ name = 'Mom';           phone = '+14155550111' },
    @{ name = 'Dad';            phone = '+14155550112' },
    @{ name = 'Son';            phone = '+14155550113' },
    @{ name = 'Daughter';       phone = '+14155550114' },
    @{ name = 'Husband';        phone = '+14155550115' },
    @{ name = 'Wife';           phone = '+14155550116' },
    @{ name = 'Brother';        phone = '+14155550117' },
    @{ name = 'Sister';         phone = '+14155550118' },
    @{ name = 'Grandson';       phone = '+14155550119' },
    @{ name = 'Granddaughter';  phone = '+14155550120' },
    @{ name = 'Best Friend';    phone = '+14155550121' },
    @{ name = 'Neighbor';       phone = '+14155550122' },
    @{ name = 'Doctor Smith';   phone = '+14155550123' },
    @{ name = 'Pharmacy';       phone = '+14155550124' },
    @{ name = 'Emergency';      phone = '911' },
    @{ name = 'Test';           phone = '+14155550199' }
)

foreach ($c in $contacts) {
    Write-Host "adding $($c.name) $($c.phone)"
    & $adb shell "sh /data/local/tmp/add_contact.sh '$($c.name)' '$($c.phone)'" | Out-Null
}

Write-Host "---"
Write-Host "Final contacts on device:"
& $adb shell content query --uri content://com.android.contacts/contacts --projection display_name
