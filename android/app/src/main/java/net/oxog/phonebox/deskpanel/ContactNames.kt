package net.oxog.phonebox.deskpanel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * The name of the contact with a phone number, for a call whose notification only shows the number. One lookup of one number when
 * a call rings, and only if the owner allowed Contacts; the contacts are never read in bulk, stored or sent: only the name of the
 * caller of that call goes to the Mac, in that call's notification.
 */
object ContactNames {
    // below Android 6 the permission is granted when the app is installed
    fun allowed(context: Context): Boolean =
        context.checkCallingOrSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun lookup(context: Context, number: String): String? {
        if (!allowed(context)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }
}
