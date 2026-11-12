package pk.raheeloptics.orders.core

import java.math.BigDecimal

/**
 * Field-level limits for the order form. Each function returns a user-facing message, or null when the value is fine.
 * Limits match the server (customer name 2..120, notes 4000, amounts well inside the 12-digit paisa cap) so a value the
 * form accepts is never rejected later by the cloud.
 */
object FieldRules {
    const val NameMin = 2
    const val NameMax = 120
    const val NotesMax = 4000
    const val DetailMax = 80
    const val CoatingMax = 120
    /** PKR 10,000,000 per amount field. */
    const val MaxAmountPaisa = 1_000_000_000L
    private val controlChars = Regex("[\\p{Cntrl}]")

    fun name(value: String): String? {
        val t = value.trim()
        return when {
            t.isEmpty() -> "Enter the customer's name."
            t.length < NameMin -> "Name must be at least $NameMin characters."
            value.length > NameMax -> "Name must be $NameMax characters or fewer."
            t.none { it.isLetter() } -> "Name must contain letters."
            controlChars.containsMatchIn(t) -> "Name contains invalid characters."
            else -> null
        }
    }

    fun mobile(value: String): String? = when {
        value.isBlank() -> "Enter the customer's mobile number."
        PakistanPhone.normalize(value) == null -> "Enter a valid Pakistani mobile number, e.g. 0300 1234567."
        else -> null
    }

    fun notes(value: String): String? = if (value.length > NotesMax) "Notes must be $NotesMax characters or fewer." else null

    fun text(value: String, label: String, max: Int): String? = when {
        value.length > max -> "$label must be $max characters or fewer."
        controlChars.containsMatchIn(value.replace("\n", "")) -> "$label contains invalid characters."
        else -> null
    }

    /**
     * Optional PKR amount in whole rupees: blank means none; otherwise digits only, no decimals, never starting with 0,
     * and at most PKR 10,000,000. Amounts are integers end to end (stored as paisa = rupees x 100).
     */
    fun amount(value: String, label: String): String? {
        if (value.isBlank()) return null
        val t = value.trim()
        return when {
            t.any { !it.isDigit() } -> "$label must be whole rupees (digits only, no decimals)."
            t.startsWith("0") -> "$label can't start with 0."
            t.toLongOrNull() == null || t.toLong() * 100 > MaxAmountPaisa -> "$label is too large (maximum PKR ${"%,d".format(MaxAmountPaisa / 100)})."
            else -> null
        }
    }

    /** Reading addition, in dioptres. */
    fun add(value: String, label: String): String? {
        if (value.isBlank()) return null
        val n = value.trim().removePrefix("+").toBigDecimalOrNull() ?: return "$label must be a number, e.g. +2.00."
        return if (n < BigDecimal("0.25") || n > BigDecimal("10")) "$label must be between +0.25 and +10.00." else null
    }

    /** Pupillary distance in millimetres. */
    fun pd(value: String): String? {
        if (value.isBlank()) return null
        val n = value.trim().toBigDecimalOrNull() ?: return "PD must be a number in mm, e.g. 62."
        return if (n < BigDecimal("20") || n > BigDecimal("90")) "PD must be between 20 and 90 mm." else null
    }

    fun eye(eye: EyePrescription): String? = runCatching { OpticalRules.validateEye(eye) }.exceptionOrNull()?.message

    fun discount(discountPaisa: Long, grossPaisa: Long): String? =
        if (discountPaisa > grossPaisa) "Discount can't be more than the order amount (PKR ${Money.format(grossPaisa)})." else null

    fun paid(paidPaisa: Long, totalPaisa: Long): String? =
        if (paidPaisa > totalPaisa) "Amount paid can't be more than the order total (PKR ${Money.format(totalPaisa)})." else null

    /** Whole-rupee input: digits only, no leading zeros, capped at 8 digits (PKR 10,000,000). */
    fun wholeInput(raw: String): String = raw.filter(Char::isDigit).trimStart('0').take(8)

    /** Keeps only the characters of a decimal being typed (digits and one dot), capped in length. */
    fun decimalInput(raw: String, maxLength: Int = 12): String {
        var dot = false
        return raw.filter { c -> if (c == '.') (!dot).also { if (it) dot = true } else c.isDigit() }.take(maxLength)
    }
}

object OrderNumbers {
    // No 0/O/1/I so a number read over the phone is never ambiguous.
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    /** `RO-yyMMdd-XXXXX` in shop time: sortable by date, short enough to say aloud, random suffix avoids offline clashes. */
    fun generate(now: Long = System.currentTimeMillis(), random: java.util.Random = java.security.SecureRandom()): String {
        val date = java.time.format.DateTimeFormatter.ofPattern("yyMMdd").format(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Karachi")))
        return "RO-$date-${(1..5).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")}"
    }
}
