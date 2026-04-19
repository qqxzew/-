$adb='C:\Users\fandr\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$em='emulator-5554'
$contacts = @(
  @{name='Anna Daughter';  phone='+14155550101'},
  @{name='Mike Son';       phone='+14155550102'},
  @{name='Dr Smith';       phone='+14155550103'},
  @{name='Emergency';      phone='911'},
  @{name='Pharmacy';       phone='+14155550104'}
)
foreach ($c in $contacts) {
  & $adb -s $em shell "content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:null --bind account_name:s:null" | Out-Null
  $q = & $adb -s $em shell "content query --uri content://com.android.contacts/raw_contacts --projection _id --sort '\`"_id DESC\`"'"
  $ids = ($q | Select-String -Pattern '_id=(\d+)' -AllMatches).Matches | ForEach-Object { [int]$_.Groups[1].Value }
  if (-not $ids) { Write-Host "no raw id for $($c.name)"; continue }
  $id = ($ids | Measure-Object -Maximum).Maximum
  & $adb -s $em shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'$($c.name)'" | Out-Null
  & $adb -s $em shell "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:'$($c.phone)' --bind data2:i:2" | Out-Null
  Write-Host "added $($c.name) $($c.phone) raw=$id"
}
Write-Host '--- contacts ---'
& $adb -s $em shell "content query --uri content://com.android.contacts/data --projection display_name:data1 --where \`"mimetype='vnd.android.cursor.item/phone_v2'\`""
