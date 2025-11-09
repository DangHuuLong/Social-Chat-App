package common;

public class GroupMessage {
    private final long id;
    private final int groupId;
    private final String sender;
    private final String body;

    public GroupMessage(long id, int groupId, String sender, String body) {
        this.id = id;
        this.groupId = groupId;
        this.sender = sender;
        this.body = body;
    }

    public long getId() {
        return id;
    }

    public int getGroupId() {
        return groupId;
    }

    public String getSender() {
        return sender;
    }

    public String getBody() {
        return body;
    }
}
