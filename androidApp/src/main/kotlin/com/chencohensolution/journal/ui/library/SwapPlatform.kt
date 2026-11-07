package com.chencohensolution.journal.ui.library

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import com.chencohensolution.journal.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File
import java.security.KeyStore
import java.text.DateFormat
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Swap with a friend, the Android pieces: the QR code, the passphrase kept on the phone and in Google Password Manager,
// the recovery sheet, Share and Copy (docs/plans/swap-from-phones.md, "Keeping the passphrase"). Never on the journal
// server or the friend's: a copy guards against losing those.

/** [text] as a QR code, [size] pixels square, black on white with a quiet zone. */
internal fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = createBitmap(matrix.width, matrix.height)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) bitmap[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
    return bitmap
}

/**
 * The copy passphrase on this phone: encrypted with a key in the Android Keystore (it never leaves the phone's secure
 * hardware), for "Show my copy passphrase" behind the phone's lock and to fill joinSwap again. Google Password Manager
 * keeps the synced copy, for a new phone.
 */
internal class PassphraseKeeper(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("swap_passphrase", Context.MODE_PRIVATE)

    /** Whether one is kept here. */
    val kept: Boolean get() = prefs.contains(KEY_SEALED)

    fun save(passphrase: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_SEALED, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    /** The passphrase kept here; null when none is, or the Keystore key is gone (a restored backup of another phone). */
    fun read(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val sealed = prefs.getString(KEY_SEALED, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(sealed, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "journal-copy-passphrase"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_IV = "iv"
        const val KEY_SEALED = "sealed"
    }
}

/** The name the passphrase is saved under in Google Password Manager: it says what it opens. */
internal fun passphraseAccount(context: Context, serverId: String?): String =
    context.getString(R.string.swap_password_account, serverId?.take(7) ?: "journal")

/**
 * Saves [passphrase] to Google Password Manager through Credential Manager: the system's sheet asks the user. True
 * when saved; false when the user declined or there is no provider.
 */
internal suspend fun saveToPasswordManager(activity: Activity, serverId: String?, passphrase: String): Boolean = runCatching {
    CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(passphraseAccount(activity, serverId), passphrase))
    true
}.getOrDefault(false)

/** The passphrase saved in Google Password Manager (the user picks it in the system's sheet); null when none or cancelled. */
internal suspend fun passphraseFromPasswordManager(activity: Activity): String? = runCatching {
    val answer = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPasswordOption())))
    (answer.credential as? PasswordCredential)?.password
}.getOrNull()

/** The share sheet with [text]. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/** Copies [text]; [sensitive] keeps it out of the clipboard's preview (a passphrase). */
internal fun copyText(context: Context, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText(context.getString(R.string.app_name), text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** What the recovery sheet says: the words, whose copy, at which friend's, from which server. */
internal class RecoverySheet(
    val words: List<String>,
    val friend: String,
    val serverId: String?,
    val user: String?,
)

/**
 * Writes the recovery sheet, one A4 page: the words numbered and as a QR code, the server id, the friend's name and
 * how to restore; and hands it to the share sheet (to print, keep in Files or Drive). The file stays in the app's
 * cache (cache/shared/), which Android clears.
 */
internal fun shareRecoverySheet(context: Context, sheet: RecoverySheet) {
    val file = File(File(context.cacheDir, "shared").apply { mkdirs() }, context.getString(R.string.swap_sheet_file) + ".pdf")
    writeRecoverySheet(context, sheet, file)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/pdf")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

internal fun writeRecoverySheet(context: Context, sheet: RecoverySheet, file: File) {
    val width = 595
    val height = 842
    val margin = 56f
    val document = PdfDocument()
    val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
    val canvas = page.canvas
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f }
    val bold = TextPaint(text).apply { typeface = Typeface.DEFAULT_BOLD }
    val title = TextPaint(bold).apply { textSize = 22f }
    val word = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f; typeface = Typeface.MONOSPACE }
    var y = margin

    fun paragraph(s: String, paint: TextPaint, w: Float = width - 2 * margin, x: Float = margin, after: Float = 10f) {
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        y += layout.height + after
    }

    paragraph(context.getString(R.string.swap_sheet_title), title, after = 6f)
    paragraph(context.getString(R.string.swap_sheet_date, DateFormat.getDateInstance(DateFormat.LONG).format(Date())), text, after = 18f)
    paragraph(context.getString(R.string.swap_sheet_intro, sheet.friend), text, after = 18f)

    // The words on the left, numbered, with their QR code on the right.
    val qrSize = 150
    val top = y
    sheet.words.forEachIndexed { i, w -> paragraph("${i + 1}.  $w", word, w = 300f, after = 8f) }
    canvas.drawBitmap(qrBitmap(sheet.words.joinToString(" "), qrSize * 2), null, android.graphics.RectF(width - margin - qrSize, top, width - margin, top + qrSize), null)
    y = maxOf(y, top + qrSize) + 18f

    paragraph(context.getString(R.string.swap_sheet_friend), bold, after = 2f)
    paragraph(sheet.friend, text, after = 10f)
    sheet.user?.let {
        paragraph(context.getString(R.string.swap_sheet_user), bold, after = 2f)
        paragraph(it, text, after = 10f)
    }
    paragraph(context.getString(R.string.swap_sheet_server), bold, after = 2f)
    paragraph(sheet.serverId ?: context.getString(R.string.swap_sheet_server_unknown), text, after = 18f)

    paragraph(context.getString(R.string.swap_sheet_restore_title), bold, after = 4f)
    paragraph(context.getString(R.string.swap_sheet_restore, sheet.friend, sheet.user ?: "<user>"), text, after = 18f)
    paragraph(context.getString(R.string.swap_sheet_keep), bold)
    document.finishPage(page)
    file.outputStream().use { document.writeTo(it) }
    document.close()
}
