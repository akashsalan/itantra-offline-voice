package org.itantra.app.data
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MessageStore(context: Context, databaseName: String = "itantra-messages.db") {
    private val database = Room.databaseBuilder(context, MessageDatabase::class.java, databaseName)
        .addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN channel TEXT NOT NULL DEFAULT 'VOICE'")
            }
        }, object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("roomId", "roomName", "senderId", "targetId", "targets", "deliveredTo",
                    "playedBy", "acknowledgedBy", "lanPayload").forEach {
                    db.execSQL("ALTER TABLE messages ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL("ALTER TABLE messages ADD COLUMN readLocally INTEGER NOT NULL DEFAULT 0")
            }
        }).build()
    private val dao = database.messages()
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages = mutable.asStateFlow()
    suspend fun refresh() = withContext(Dispatchers.IO) { lock.withLock { mutable.value = dao.all() } }
    suspend fun nextSequence(): Int = withContext(Dispatchers.IO) { lock.withLock { Math.addExact(dao.maxSequence(), 1) } }
    suspend fun insert(message: MessageEntity, mayInsert: () -> Boolean = { true }): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            // Last consent check after any dispatcher/mutex wait, before persistence.
            check(mayInsert()) { "Automatic sending stopped before queueing this part. Earlier queued parts, if any, remain in history." }
            val inserted = dao.insert(message) != -1L; mutable.value = dao.all(); inserted
        }
    }
    suspend fun mutate(key: String, transform: (MessageEntity) -> Unit): MessageEntity? = withContext(Dispatchers.IO) {
        lock.withLock {
            val item = dao.find(key) ?: return@withLock null
            transform(item); dao.update(item); mutable.value = dao.all(); item
        }
    }
    suspend fun find(key: String): MessageEntity? = withContext(Dispatchers.IO) { lock.withLock { dao.find(key) } }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { dao.clear(); mutable.value = emptyList() } }
    suspend fun close() = withContext(Dispatchers.IO) { lock.withLock { database.close() } }
    companion object { fun key(peer: String, direction: String, id: Long) = "$peer:$direction:$id" }
}
