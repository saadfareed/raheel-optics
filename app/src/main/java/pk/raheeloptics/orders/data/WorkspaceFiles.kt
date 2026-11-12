package pk.raheeloptics.orders.data

import android.content.Context

/** Local database file names. Builds before the orders rename used `todos-*.db`; [migrateLegacy] renames those in place. */
object WorkspaceFiles {
    val name=Regex("orders-[a-zA-Z0-9-]+\\.db")
    val legacy=Regex("todos-[a-zA-Z0-9-]+\\.db")
    fun forKey(key:String)="orders-${key.replace(Regex("[^a-zA-Z0-9-]"),"")}.db"
    fun upgraded(filename:String)=if(legacy.matches(filename)) "orders-"+filename.removePrefix("todos-") else filename

    /**
     * Runs before any database is opened. Renames each legacy database with its WAL/SHM/journal companions; a file is
     * skipped if its new name already exists, so nothing is overwritten. Returns true when anything was renamed.
     */
    fun migrateLegacy(context:Context):Boolean {
        var renamed=false
        for(old in context.databaseList().filter(legacy::matches)) {
            val from=context.getDatabasePath(old)
            val to=context.getDatabasePath(upgraded(old))
            if(to.exists()) continue
            for(suffix in listOf("-wal","-shm","-journal")) {
                val companion=java.io.File(from.path+suffix)
                if(companion.exists()) check(companion.renameTo(java.io.File(to.path+suffix))) {"Couldn't move ${companion.name}"}
            }
            check(from.renameTo(to)) {"Couldn't move $old"}
            renamed=true
        }
        return renamed
    }
}
