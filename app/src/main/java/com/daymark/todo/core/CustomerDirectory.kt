package com.daymark.todo.core

enum class CustomerSort(val label: String) {
    RECENT("Recently ordered"), NEWEST("Recently added"), NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"), MOST_ORDERS("Most orders"), BALANCE("Highest balance"),SPEND("Highest spend")
}
data class CustomerDirectoryRow(val latestOrder:Todo,val orderCount:Int,val lastOrderAt:Long,val balancePaisa:Long,val customer:Customer?=null,val spentPaisa:Long=0)
data class CustomerDirectoryQuery(val search:String="",val sort:CustomerSort=CustomerSort.RECENT,val outstandingOnly:Boolean=false,val page:Int=1,val returning:Boolean?=null,
    /** Load-more: number of cumulative pages to return starting at [page]. */
    val loaded:Int=1)

object RepeatOrder {
    fun draft(source:Todo,now:Long=System.currentTimeMillis()):Todo = Todo(
        id=java.util.UUID.randomUUID().toString(),title=source.title,
        customerMobile=source.customerMobile,normalizedMobile=source.normalizedMobile,customerId=source.customerId,
        prescription=source.prescription,orderedAt=now,updatedAt=now,
        orderNumber=OrderNumbers.generate(now)
    )
}
