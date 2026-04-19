$adb='C:\Users\fandr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$em='emulator-5554'
& $adb -s $em shell 'content delete --uri content://com.android.contacts/raw_contacts' | Out-Null
& $adb -s $em push 'C:\Users\fandr\Desktop\HACK V2\add_contact.sh' /data/local/tmp/add_contact.sh | Out-Null
& $adb -s $em shell 'chmod 755 /data/local/tmp/add_contact.sh' | Out-Null
$contacts = @(
  @('Anna Daughter','+14155550101'),
  @('Mike Son','+14155550102'),
  @('Dr Smith','+14155550103'),
  @('Emergency','911'),
  @('Pharmacy','+14155550104')
)
foreach ($c in $contacts) {
  Write-Host "--- $($c[0]) ---"
  & $adb -s $em shell "sh /data/local/tmp/add_contact.sh '$($c[0])' '$($c[1])'"
}
Write-Host "=== FINAL ==="
& $adb -s $em shell "content query --uri content://com.android.contacts/contacts --projection display_name"
