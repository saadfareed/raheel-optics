package pk.raheeloptics.orders

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import pk.raheeloptics.orders.data.WorkspaceFiles

class WorkspaceFilesTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun legacyDatabaseIsRenamedWithItsRowsAndNeverOverwritesANewerFile() {
        val legacy="todos-rename-check.db"; val renamed="orders-rename-check.db"
        listOf(legacy,renamed).forEach(context::deleteDatabase)
        try {
            context.openOrCreateDatabase(legacy,0,null).use {db ->
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE t(v TEXT)");db.execSQL("INSERT INTO t VALUES('kept')")
            }
            assertTrue(WorkspaceFiles.migrateLegacy(context))
            assertFalse(context.getDatabasePath(legacy).exists())
            context.openOrCreateDatabase(renamed,0,null).use {db ->
                db.rawQuery("SELECT v FROM t",null).use {assertTrue(it.moveToFirst());assertEquals("kept",it.getString(0))}
            }
            context.openOrCreateDatabase(legacy,0,null).close()
            assertFalse("an existing orders-*.db is never replaced",WorkspaceFiles.migrateLegacy(context))
            assertTrue(context.getDatabasePath(legacy).exists())
            assertEquals("orders-a.db",WorkspaceFiles.upgraded("todos-a.db"));assertEquals("orders-a.db",WorkspaceFiles.upgraded("orders-a.db"))
        } finally {listOf(legacy,renamed).forEach(context::deleteDatabase)}
    }
}
