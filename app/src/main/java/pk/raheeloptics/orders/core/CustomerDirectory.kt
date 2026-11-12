package pk.raheeloptics.orders.core

enum class CustomerSort(val label: String) {
    RECENT("Recently ordered"), NEWEST("Recently added"), NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"), MOST_ORDERS("Most orders"), BALANCE("Highest balance"),SPEND("Highest spend")
}
data class CustomerDirectoryRow(val latestOrder:Order,val orderCount:Int,val lastOrderAt:Long,val balancePaisa:Long,val customer:Customer?=null,val spentPaisa:Long=0)
data class CustomerDirectoryQuery(val search:String="",val sort:CustomerSort=CustomerSort.RECENT,val page:Int=1,
    /** Load-more: number of cumulative pages to return starting at [page]. */
    val loaded:Int=1)

object RepeatOrder {
    fun draft(source:Order,now:Long=System.currentTimeMillis()):Order = Order(
        id=java.util.UUID.randomUUID().toString(),title=source.title,
        customerMobile=source.customerMobile,normalizedMobile=source.normalizedMobile,customerId=source.customerId,
        prescription=source.prescription,orderedAt=now,updatedAt=now,
        orderNumber=OrderNumbers.generate(now)
    )
}
