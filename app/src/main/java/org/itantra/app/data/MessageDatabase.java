package org.itantra.app.data;
import androidx.room.*;
@Database(entities = {MessageEntity.class}, version = 3, exportSchema = false)
public abstract class MessageDatabase extends RoomDatabase {
    public abstract MessageDao messages();
}
