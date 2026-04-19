#!/system/bin/sh
# args: $1=name $2=phone
content insert --uri content://com.android.contacts/raw_contacts --bind account_name:s:test@example.com --bind account_type:s:com.google
RAW=$(content query --uri content://com.android.contacts/raw_contacts --projection _id | tail -n 1 | sed 's/.*_id=//')
echo "raw=$RAW"
content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"$1"
content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$RAW --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:"$2" --bind data2:i:2
