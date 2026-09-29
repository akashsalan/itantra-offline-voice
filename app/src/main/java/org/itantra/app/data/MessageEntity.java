package org.itantra.app.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "messages")
public class MessageEntity {
    @PrimaryKey @NonNull public String key = "";
    @NonNull public String peerId = "";
    @NonNull public String peerName = "";
    public long messageId;
    public long sessionId;
    public int sequence;
    @NonNull public String direction = "";
    @NonNull public String text = "";
    @NonNull public String language = "";
    public boolean emergency;
    public long createdAtMs;
    public long receiptElapsedMs;
    public long speechEndElapsedMs;
    @NonNull public String delivery = "QUEUED";
    @NonNull public String playback = "PENDING";
    @NonNull public String transport = "Wi-Fi Direct";
    @androidx.room.ColumnInfo(defaultValue = "'VOICE'")
    @NonNull public String channel = "VOICE";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String roomId = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String roomName = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String senderId = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String targetId = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String targets = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String deliveredTo = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String playedBy = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String acknowledgedBy = "";
    @androidx.room.ColumnInfo(defaultValue = "''") @NonNull public String lanPayload = "";
    @androidx.room.ColumnInfo(defaultValue = "0") public boolean readLocally;
    public int attempts;
    public long nextAttemptMs;
    public long firstSendElapsedMs;
    public int wireBytes;
    public boolean humanAcknowledged;
}
