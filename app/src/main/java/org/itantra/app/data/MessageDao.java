package org.itantra.app.data;
import androidx.room.*;
import java.util.List;
@Dao
public interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY createdAtMs DESC, sequence DESC")
    List<MessageEntity> all();
    @Query("SELECT * FROM messages WHERE [key] = :key LIMIT 1")
    MessageEntity find(String key);
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(MessageEntity message);
    @Update void update(MessageEntity message);
    @Query("SELECT COALESCE(MAX(sequence), 0) FROM messages WHERE direction = 'OUT'")
    int maxSequence();
    @Query("DELETE FROM messages") void clear();
}
