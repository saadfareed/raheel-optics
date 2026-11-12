package pk.raheeloptics.orders.core

/**
 * Labelled demo orders for trying the app and the sync flow. The shop contact and the customer's mobile are the owner's
 * test numbers. Demo orders are tagged in their notes, use deterministic ids (loading twice never duplicates) and cover
 * every status, partial and full payments, and a promised date in the past, today and the future.
 */
object DemoData {
    const val SHOP_MOBILE = "03104585225"
    const val CUSTOMER_NAME = "saad"
    const val CUSTOMER_MOBILE = "03334909162"
    const val TAG = "Demo data"

    private fun id(accountId: String, n: Int) = java.util.UUID.nameUUIDFromBytes("$accountId:demo-order-v2:$n".toByteArray()).toString()

    fun orders(accountId: String, now: Long = System.currentTimeMillis()): List<Order> {
        val day = 86_400_000L
        val normalized = PakistanPhone.normalize(CUSTOMER_MOBILE)!!
        val rx = Prescription(distanceRight = EyePrescription("-2.25", "-0.75", 90), distanceLeft = EyePrescription("-1.75", "-0.50", 85),
            addRight = "1.50", addLeft = "1.50", pd = "63")
        fun order(n: Int, number: String, frame: String, lens: String, framePkr: Long, lensPkr: Long, paidPkr: Long, status: OrderStatus, orderedDaysAgo: Int, promisedInDays: Int?) : Order {
            val ordered = now - orderedDaysAgo * day
            val terminal = status == OrderStatus.COMPLETE || status == OrderStatus.CANCEL
            return Order(id(accountId, n), CUSTOMER_NAME, notes = TAG, customerMobile = CUSTOMER_MOBILE, normalizedMobile = normalized, orderNumber = number,
                prescription = rx, frame = FrameDetails(model = frame), lens = LensDetails(material = lens),
                amounts = OrderAmounts(framePaisa = framePkr * 100, lensPaisa = lensPkr * 100),
                payments = if (paidPkr > 0) listOf(Payment(java.util.UUID.nameUUIDFromBytes("$accountId:demo-pay-v2:$n".toByteArray()).toString(), paidPkr * 100,
                    if (n % 2 == 0) PaymentMethod.JAZZCASH else PaymentMethod.CASH, ordered + 3_600_000)) else emptyList(),
                orderStatus = status, status = when (status) { OrderStatus.COMPLETE -> "completed"; OrderStatus.CANCEL -> "deleted"; else -> "active" },
                orderedAt = ordered, updatedAt = ordered, dueAt = promisedInDays?.let { now + it * day },
                completedAt = ordered.takeIf { status == OrderStatus.COMPLETE }, deliveredAt = (ordered + 2 * day).takeIf { status == OrderStatus.COMPLETE },
                reminderAt = null.takeIf { terminal })
        }
        return listOf(
            order(1, "RO-DEMO-0001", "Ray-Ban metal", "Blue cut", 4500, 3000, 2000, OrderStatus.ACTIVE, 2, 3),
            order(2, "RO-DEMO-0002", "Oakley sport", "Photochromic", 6000, 4500, 0, OrderStatus.ACTIVE, 1, 5),
            order(3, "RO-DEMO-0003", "Titanium rimless", "Progressive", 8000, 9000, 10000, OrderStatus.READY_FOR_PICKUP, 6, 1),
            order(4, "RO-DEMO-0004", "Acetate round", "Single vision", 3500, 2000, 5500, OrderStatus.COMPLETE, 20, null),
            order(5, "RO-DEMO-0005", "Kids flexible", "Anti-scratch", 2500, 1500, 4000, OrderStatus.COMPLETE, 35, null),
            order(6, "RO-DEMO-0006", "Aviator gold", "Polarized", 5000, 3500, 1000, OrderStatus.CANCEL, 12, null),
        )
    }

    private val firstNames = listOf("Ayesha","Bilal","Hina","Usman","Sana","Imran","Fatima","Hamza","Maryam","Zain","Noor","Ali","Sadia","Farhan","Iqra","Talha",
        "Rabia","Kamran","Amna","Danish","Mehwish","Shahid","Laiba","Waqar","Zainab","Adeel","Saima","Faisal","Nimra","Yasir","Hira","Junaid")
    private val frames = listOf("Ray-Ban metal","Acetate round","Titanium rimless","Kids flexible","Aviator gold","Oakley sport","Cat-eye acetate","Half-rim steel")
    private val lenses = listOf("Single vision","Blue cut","Progressive","Photochromic","Anti-scratch","Polarized","Bifocal")
    const val EXTRA_CUSTOMERS = 32

    /**
     * 32 more customers (unique mobiles 0300 1000001..) with one to three orders each, spread over every status and
     * payment state, so lists need more than one page. Combined with [orders] that is 33 customers and about 50 orders.
     */
    fun extraOrders(accountId: String, now: Long = System.currentTimeMillis()): List<Order> {
        val day = 86_400_000L
        val statuses = listOf(OrderStatus.ACTIVE, OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETE, OrderStatus.ACTIVE, OrderStatus.CANCEL, OrderStatus.COMPLETE, OrderStatus.ACTIVE)
        return (1..EXTRA_CUSTOMERS).flatMap { c ->
            val mobile = "0300" + (1_000_000 + c)
            val normalized = PakistanPhone.normalize(mobile)!!
            val name = "${firstNames[c - 1]} Demo"
            val count = 1 + (if (c % 3 == 0) 1 else 0) + (if (c % 7 == 0) 1 else 0)
            (1..count).map { k ->
                val n = c * 10 + k
                val status = statuses[(c + k) % statuses.size]
                val framePkr = 2500L + 500 * ((c + k) % 9)
                val lensPkr = 1500L + 500 * ((c * k) % 7)
                val paidPkr = when ((c + k) % 4) { 0 -> 0L; 1 -> (framePkr + lensPkr) / 2; else -> framePkr + lensPkr }
                val ordered = now - ((c * 3 + k * 5) % 60) * day
                Order(java.util.UUID.nameUUIDFromBytes("$accountId:demo-extra-v1:$n".toByteArray()).toString(), name, notes = TAG,
                    customerMobile = mobile, normalizedMobile = normalized, orderNumber = "RO-DEMO-C%02d-%d".format(c, k),
                    prescription = Prescription(distanceRight = EyePrescription("-%.2f".format(0.5 + (c % 8) * 0.5)), distanceLeft = EyePrescription("-%.2f".format(0.25 + (c % 6) * 0.5))),
                    frame = FrameDetails(model = frames[(c + k) % frames.size]), lens = LensDetails(material = lenses[(c * k) % lenses.size]),
                    amounts = OrderAmounts(framePaisa = framePkr * 100, lensPaisa = lensPkr * 100),
                    payments = if (paidPkr > 0) listOf(Payment(java.util.UUID.nameUUIDFromBytes("$accountId:demo-extra-pay-v1:$n".toByteArray()).toString(), paidPkr * 100,
                        PaymentMethod.entries[(c + k) % 3], ordered + 3_600_000)) else emptyList(),
                    orderStatus = status, status = when (status) { OrderStatus.COMPLETE -> "completed"; OrderStatus.CANCEL -> "deleted"; else -> "active" },
                    orderedAt = ordered, updatedAt = ordered, dueAt = (now + ((c + k) % 10 + 1) * day).takeIf { status == OrderStatus.ACTIVE || status == OrderStatus.READY_FOR_PICKUP },
                    completedAt = ordered.takeIf { status == OrderStatus.COMPLETE }, deliveredAt = (ordered + 2 * day).takeIf { status == OrderStatus.COMPLETE })
            }
        }
    }

    /** Everything the debug "Load demo data" action adds. */
    fun all(accountId: String, now: Long = System.currentTimeMillis()): List<Order> = orders(accountId, now) + extraOrders(accountId, now)
}
