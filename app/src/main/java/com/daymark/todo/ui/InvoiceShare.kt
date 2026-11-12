package com.daymark.todo.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import androidx.core.content.FileProvider
import com.daymark.todo.core.*
import java.io.File

/** Draws an [Invoice] as a PNG-ready bitmap (no layout engine: fixed-width column, height from content). */
object InvoiceRenderer {
    private const val W = 900
    private const val PAD = 48f
    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun render(invoice: Invoice): Bitmap {
        val ink = 0xFF102A3A.toInt(); val muted = 0xFF587080.toInt(); val brand = 0xFF075A91.toInt(); val red = 0xFFB64032.toInt(); val line = 0xFFD5E3EC.toInt()
        val rows = invoice.sections.sumOf { it.lines.size + 2 } + invoice.totals.size
        val height = (360 + rows * 52 + invoice.sections.size * 24 + 200).coerceAtLeast(700)
        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.WHITE)
        c.drawRect(0f, 0f, W.toFloat(), 150f, Paint().apply { color = brand })
        c.drawText(invoice.shopName, PAD, 78f, paint(46f, Color.WHITE, true))
        c.drawText(listOf(invoice.shopContact, invoice.shopAddress).filter { it.isNotBlank() }.joinToString("  ·  ").take(70).ifBlank { "Invoice" }, PAD, 122f, paint(26f, 0xFFD9ECF8.toInt()))
        var y = 215f
        c.drawText("INVOICE", PAD, y, paint(34f, brand, true))
        c.drawText(invoice.issued, W - PAD - paint(26f, muted).measureText(invoice.issued), y, paint(26f, muted))
        y += 48f
        c.drawText("Billed to", PAD, y, paint(24f, muted)); y += 36f
        c.drawText(invoice.customerName.take(40), PAD, y, paint(34f, ink, true)); y += 40f
        c.drawText(invoice.customerMobile, PAD, y, paint(26f, muted)); y += 36f
        val divider = Paint().apply { color = line; strokeWidth = 2f }
        fun row(l: InvoiceLine, bigger: Boolean = false) {
            val labelPaint = paint(if (bigger) 30f else 26f, if (l.strong) ink else muted, l.strong)
            val valuePaint = paint(if (bigger) 32f else 27f, if (l.alert) red else ink, l.strong || bigger)
            c.drawText(l.label.take(44), PAD, y, labelPaint)
            c.drawText(l.value, W - PAD - valuePaint.measureText(l.value), y, valuePaint)
            y += if (bigger) 54f else 46f
        }
        invoice.sections.forEach { section ->
            y += 14f; c.drawLine(PAD, y, W - PAD, y, divider); y += 44f
            c.drawText(section.title.take(48), PAD, y, paint(29f, brand, true)); y += 46f
            section.lines.forEach { row(it) }
        }
        y += 14f
        c.drawRect(PAD - 16f, y, W - PAD + 16f, y + 64f + invoice.totals.size * 54f, Paint().apply { color = 0xFFEEF5FA.toInt() })
        y += 52f
        invoice.totals.forEach { row(it, bigger = true) }
        y += 34f
        c.drawText(invoice.footer.take(78), PAD, y, paint(24f, muted))
        return Bitmap.createBitmap(bitmap, 0, 0, W, (y + 48f).toInt().coerceAtMost(height))
    }
}

object WhatsAppShare {
    /**
     * Opens WhatsApp addressed to [mobile] with the invoice picture and [message]. wa.me links cannot carry a picture,
     * so this uses a share intent aimed at the customer's chat; if WhatsApp is missing it falls back to the system sheet.
     * The user always reviews and presses send; nothing is sent automatically.
     */
    fun send(context: Context, mobile: String, message: String, invoice: Invoice) {
        val digits = (PakistanPhone.normalize(mobile) ?: error("This customer has no valid WhatsApp number.")).filter(Char::isDigit)
        val folder = File(context.cacheDir, "invoices").apply { mkdirs(); listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach(File::delete) }
        val file = File(folder, "invoice-${System.currentTimeMillis()}.png")
        val bitmap = InvoiceRenderer.render(invoice)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Couldn't create the invoice picture." } }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, message)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent(intent).setPackage("com.whatsapp").putExtra("jid", "$digits@s.whatsapp.net"))
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(intent, "Share invoice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
