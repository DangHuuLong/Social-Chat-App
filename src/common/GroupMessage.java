package common;

import java.time.Instant;

public class GroupMessage {
    private long id;
    private int groupId;
    private String sender;
    private String body;
    private Long replyTo;
    private Instant  createdAt;
    private Instant  updatedAt; 

    public GroupMessage(long id, int groupId, String sender, String body, 
                        Long replyTo, Instant  createdAt, Instant  updatedAt) {
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
    public Instant  getCreatedAt() { return createdAt; }
    public Instant  getUpdatedAt() { return updatedAt; } 
}