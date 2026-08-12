package com.callerannouncer.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactHelper {

    fun resolveDisplayName(context: Context, phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank()) return "ناشناس"
        if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
            return phoneNumber
        }

        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI
            .buildUpon()
            .appendPath(phoneNumber)
            .build()

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() } ?: phoneNumber
                } else {
                    phoneNumber
                }
            } ?: phoneNumber
        } catch (_: SecurityException) {
            phoneNumber
        } catch (_: Exception) {
            phoneNumber
        }
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

object PermissionHelper {

    fun requiredPermissions(): Array<String> {
        val base = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return base.toTypedArray()
    }

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter { !ContactHelper.hasPermission(context, it) }

    fun allGranted(context: Context): Boolean = missingPermissions(context).isEmpty()

    fun labelFor(permission: String): String = when (permission) {
        Manifest.permission.READ_PHONE_STATE -> "وضعیت تماس"
        Manifest.permission.READ_CALL_LOG -> "تاریخچه تماس"
        Manifest.permission.READ_CONTACTS -> "مخاطبین"
        Manifest.permission.RECEIVE_SMS -> "دریافت پیامک"
        Manifest.permission.READ_SMS -> "خواندن پیامک"
        Manifest.permission.BLUETOOTH_CONNECT -> "بلوتوث"
        Manifest.permission.POST_NOTIFICATIONS -> "اعلان‌ها"
        else -> permission.substringAfterLast('.')
    }
}
