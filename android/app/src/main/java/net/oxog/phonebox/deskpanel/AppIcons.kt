package net.oxog.phonebox.deskpanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream

/**
 * The icon of the app that posted a notification, as a small PNG in base64, for the Mac to show beside the text. Drawn once for
 * each app and kept for the life of the process; an icon that cannot be had (or is too big) is simply left out.
 */
object AppIcons {
    private const val PIXELS = 96
    private const val SMALL_PIXELS = 64
    private const val RETRY_MS = 10 * 60_000L
    /** The agent ignores anything over 32 KiB; a flat 96 pixel icon is a few kilobytes. */
    private const val MAX_BYTES = 30 * 1024
    private val cache = LruCache<String, String>(60)
    private val none = HashMap<String, Long>() // apps whose icon could not be drawn, and when: tried again after ten minutes

    @Synchronized
    fun base64(context: Context, pkg: String): String? {
        // the listener lives for weeks: keyed by when the app was last installed or updated, so an update that changes the icon is
        // drawn again instead of the old picture being sent until the phone restarts (the old entry simply ages out of the cache)
        val key = "$pkg@${installedAt(context, pkg)}"
        cache.get(key)?.let { return it }
        none[pkg]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null }
        val encoded = try { render(context, pkg, PIXELS) ?: render(context, pkg, SMALL_PIXELS) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        if (encoded == null) none[pkg] = System.currentTimeMillis() else { none.remove(pkg); cache.put(key, encoded) }
        return encoded
    }

    @Suppress("DEPRECATION") // the flags overload of API 33 means the same; this one runs from API 21
    private fun installedAt(context: Context, pkg: String): Long =
        try { context.packageManager.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }

    private fun render(context: Context, pkg: String, pixels: Int): String? {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, pixels, pixels)
        drawable.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        val png = out.toByteArray()
        return if (png.size > MAX_BYTES) null else Base64.encodeToString(png, Base64.NO_WRAP)
    }
}
