package com.daymark.todo.core

enum class CustomerSort(val label: String) {
    RECENT("Recently ordered"), NEWEST("Recently added"), NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"), MOST_ORDERS("Most orders"), BALANCE("Highest balance")
}
data class CustomerDirectoryRow(val latestOrder:Todo,val orderCount:Int,val lastOrderAt:Long,val balancePaisa:Long,val customer:Customer?=null)
data class CustomerDirectoryQuery(val search:String="",val sort:CustomerSort=CustomerSort.RECENT,val outstandingOnly:Boolean=false,val page:Int=1)

object RepeatOrder {
    fun draft(source:Todo,now:Long=System.currentTimeMillis()):Todo = Todo(
        id=java.util.UUID.randomUUID().toString(),title=source.title,
        customerMobile=source.customerMobile,normalizedMobile=source.normalizedMobile,customerId=source.customerId,
        prescription=source.prescription,orderedAt=now,updatedAt=now,
        orderNumber="RO-${java.time.format.DateTimeFormatter.ofPattern("yyMMdd-HHmmss").format(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Karachi")))}-${java.util.UUID.randomUUID().toString().take(4).uppercase()}"
    )
}
