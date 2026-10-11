package io.github.mangi.eta.agent.display

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Private bootstrap endpoint. Root must also prove ownership of the current session. */
internal class VirtualScreenBridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != 0) throw SecurityException("ROOT_REQUIRED")
        require(method == "register" && extras != null) { "INVALID_BRIDGE_REQUEST" }
        val callback = VirtualScreenSession.registerRootBridge(
            extras.getString("sessionId").orEmpty(), extras.getString("token").orEmpty(),
            requireNotNull(extras.getBinder("display")),
        )
        return Bundle().apply { putBinder("callback", callback) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
