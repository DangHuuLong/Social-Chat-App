package common;

import java.time.Instant;

public class Message {
    private long id;
    private String sender;
    private String recipient;
    private String body;
    private Long replyTo;        // nullable
    private String status;       // queued / delivered / read ...
    private Instant createdAt;
    private Instant deliveredAt;
    private Instant updatedAt;

    public Message() {}

    public Message(long id, String sender, String recipient, String body, Long replyTo,
                   String status, Instant createdAt, Instant deliveredAt, Instant updatedAt) {
        this.id = id;
        this.sender = sender;
        this.recipient = recipient;
        this.body = body;
        this.replyTo = replyTo;
        this.status = status;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
        this.updatedAt = updatedAt;
    }

    // Getters & Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Long getReplyTo() { return replyTo; }
    public void setReplyTo(Long replyTo) { this.replyTo = replyTo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "Message{" +
                "id=" + id +
                ", sender='" + sender + '\'' +
                ", recipient='" + recipient + '\'' +
                ", body='" + body + '\'' +
                ", replyTo=" + replyTo +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", deliveredAt=" + deliveredAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
