package common;

import java.time.Instant;

public class FileResource {
    private long id;
    private long messageId;
    private String fileName;
    private String filePath;
    private String mimeType;
    private long fileSize;
    private Instant uploadedAt;

    public FileResource() {}

    public FileResource(long id, long messageId, String fileName, String filePath,
                        String mimeType, long fileSize, Instant uploadedAt) {
        this.id = id;
        this.messageId = messageId;
        this.fileName = fileName;
        this.filePath = filePath;
        this.mimeType = mimeType;
        this.fileSize = fileSize;
        this.uploadedAt = uploadedAt;
    }

    // Getters & Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getMessageId() { return messageId; }
    public void setMessageId(long messageId) { this.messageId = messageId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    @Override
    public String toString() {
        return "FileResource{" +
                "id=" + id +
                ", messageId=" + messageId +
                ", fileName='" + fileName + '\'' +
                ", filePath='" + filePath + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", fileSize=" + fileSize +
                ", uploadedAt=" + uploadedAt +
                '}';
    }
}
