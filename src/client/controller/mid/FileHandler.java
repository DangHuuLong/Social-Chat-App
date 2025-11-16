package client.controller.mid;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;

import java.io.File;

import client.controller.MidController;
import client.controller.mid.UtilHandler.MediaKind;

public class FileHandler {
    private final MidController controller;

    public FileHandler(MidController controller) {
        this.controller = controller;
    }

    public void showOutgoingFile(String filename, String mime, long bytes, String fileId, String duration) {
        final String safeId   = (fileId == null) ? "" : fileId;
        final String safeName = (filename == null) ? "" : filename;
        final String safeMime = (mime == null || mime.isBlank()) ? "application/octet-stream" : mime;
        
        final String sender = (controller.getCurrentUser() != null) ? controller.getCurrentUser().getUsername() : "";
        final long createdAt = System.currentTimeMillis();
        final long updatedAt = 0L;

        if (!safeId.isBlank()) {
            controller.getFileIdToName().put(safeId, safeName);
            controller.getFileIdToMime().put(safeId, safeMime);
        }

        MediaKind kind = UtilHandler.classifyMedia(safeMime, safeName);
        // CHỈ size (không MIME)
        String sizeOnly = (bytes > 0) ? UtilHandler.humanBytes(bytes) : "";
        String metaText = sizeOnly;

        HBox row;

        switch (kind) {
            case IMAGE -> {
                Image img;
                try {
                    if (!safeId.isBlank()) {
                        File f = new File("Uploads", safeId);
                        if (f.exists()) {
                            img = new Image(f.toURI().toString(), 200, 0, true, true);
                        } else {
                            img = new WritableImage(8, 8);
                        }
                    } else {
                        img = new WritableImage(8, 8);
                    }
                } catch (Exception e) {
                    img = new WritableImage(8, 8);
                }

                // Caption: "<name> • <size>" (nếu có size)
                String caption = safeName;
                if (!sizeOnly.isBlank()) caption = safeName + " • " + sizeOnly;

                row = controller.addImageMessage(img, caption, false, safeId.isBlank() ? null : safeId, sender, createdAt, updatedAt);
                if (!safeId.isBlank()) row.setUserData(safeId);
            }

            case AUDIO -> {
                String dur = (duration != null && !duration.isBlank()) ? duration : "--:--";
                row = controller.addVoiceMessage(dur, false, safeId.isBlank() ? null : safeId, sender, createdAt, updatedAt);
                if (!safeId.isBlank()) row.setUserData(safeId);
            }

            case VIDEO -> {
                // Video: tên + size (không MIME)
                row = controller.addVideoMessage(safeName, metaText, false, safeId.isBlank() ? null : safeId, sender, createdAt, updatedAt);
                if (!safeId.isBlank()) row.setUserData(safeId);
            }

            default -> {
                // File thường: tên + size (không MIME)
                row = controller.addFileMessage(safeName, metaText, false, safeId.isBlank() ? null : safeId, sender, createdAt, updatedAt);
                if (!safeId.isBlank()) row.setUserData(safeId);
            }
        }

        if (!safeId.isBlank()) {
            controller.getOutgoingFileBubbles().put(safeId, row);
        }
    }
}
