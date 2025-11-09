package common;

import java.sql.Timestamp;

public class GroupMessage {
    private long id;
    private int groupId;
    private String sender;
    private String body;
    private Long replyTo;
    private Timestamp createdAt;
    private Timestamp updatedAt; 

    public GroupMessage(long id, int groupId, String sender, String body, 
                        Long replyTo, Timestamp createdAt, Timestamp updatedAt) {
        this.id = id;
        this.groupId = groupId;
        this.sender = sender;
        this.body = body;
        this.replyTo = replyTo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getters
    public long getId() { return id; }
    public int getGroupId() { return groupId; }
    public String getSender() { return sender; }
    public String getBody() { return body; }
    public Long getReplyTo() { return replyTo; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; } 
}