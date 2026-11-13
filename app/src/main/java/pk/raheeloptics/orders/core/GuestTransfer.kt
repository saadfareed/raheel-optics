package pk.raheeloptics.orders.core

/** Rebuilds valid cloud mutations from retained guest history, keeping original event times. */
object GuestTransfer {
    fun sourceKey(id:String)="guest-v1:$id"
    fun plan(account:String,current:Order,history:List<HistoryEntry>,now:Long):List<Operation> {
        val id=TaskImport.copyId(account,sourceKey(current.id))
        val events=history.sortedWith(compareBy<HistoryEntry> {it.todo.revision}.thenBy {it.occurredAt}.thenBy {it.id})
        val result=mutableListOf<Operation>()
        var before:Order?=null
        fun append(todo:Order,action:String,time:Long) {
            val next=todo.copy(id=id,revision=result.size+1L,updatedAt=time,
                reminderAt=todo.reminderAt?.takeIf {it>now && todo.status=="active"})
            // Fixed seed: operation ids must stay identical so an interrupted guest transfer resumes safely.
            val opId=java.util.UUID.nameUUIDFromBytes("daymark-guest-op-v1:$account:${current.id}:${result.size}".toByteArray(Charsets.UTF_8)).toString()
            result+=Operation(opId,next,action,before?.revision ?: 0,result.lastOrNull()?.id,time,before?.status ?: "absent")
            before=next
        }
        val first=events.firstOrNull()?.todo ?: current
        append(first.copy(status="active",completedAt=null,reminderAt=null),"create",events.firstOrNull()?.occurredAt ?: current.updatedAt)
        for(event in events) {
            if(before?.status=="deleted") break
            // History may have been cleared before later transitions were retained.
            if(event.action=="reopen" && before?.status=="active")
                append(event.todo.copy(status="completed",completedAt=event.occurredAt,reminderAt=null),"complete",event.occurredAt)
            if(event.action=="complete" && before?.status=="completed")
                append(event.todo.copy(status="active",completedAt=null,reminderAt=null),"reopen",event.occurredAt)
            append(event.todo,event.action,event.occurredAt)
        }
        if(current.status=="active") {
            if(before?.status=="completed") append(current.copy(reminderAt=null),"reopen",current.updatedAt)
            append(current,"edit",current.updatedAt)
        } else if(before?.status!=current.status) append(current,if(current.status=="completed") "complete" else "delete",current.updatedAt)
        return result
    }
}
