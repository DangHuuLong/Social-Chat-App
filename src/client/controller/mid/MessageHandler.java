package client.controller.mid;

import common.Frame;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import client.controller.MidController;

public class MessageHandler {
    private final MidController controller;

    // === STATE: chống render trùng CallLog & chống tải trùng file ===
    private static final Set<String> renderedCallLogIds =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> requestedDownloads =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    public boolean markDownloadRequested(String key) { return requestedDownloads.add(key); }

    public MessageHandler(MidController controller) {
        this.controller = controller;
    }

    // ===================== UI REFRESH HELPERS (ẢNH/VIDEO) =====================

    private void refreshImageCaption(String fid, Long sizeHint) {
        if (fid == null || fid.isBlank()) return;
        Platform.runLater(() -> {
            HBox row = controller.findRowByFid(fid);
            if (row == null) return;
            var children = row.getChildren();
            if (children.isEmpty()) return;
            javafx.scene.Node bubble = (row.getAlignment() == javafx.geometry.Pos.CENTER_LEFT) ? children.get(0) : children.get(children.size() - 1);
            if (!(bubble instanceof javafx.scene.layout.VBox box)) return;
            String id = box.getId();
            if (id == null || !id.endsWith("-image")) return;
            javafx.scene.control.Label cap = null;
            for (javafx.scene.Node n : box.getChildren()) {
                if (n instanceof javafx.scene.control.Label l) { cap = l; break; }
            }
            if (cap == null) return;
            String name = controller.getFileIdToName().getOrDefault(fid, "");
            String mime = controller.getFileIdToMime().getOrDefault(fid, "");
            long size = -1L;
            if (sizeHint != null && sizeHint >= 0) size = sizeHint;
            else {
                File f = controller.getDlPath().get(fid);
                if (f != null && f.exists()) size = f.length();
            }
            String meta = "";
            if (mime != null && !mime.isBlank()) meta = mime;
            if (size >= 0) {
                String sizeStr = UtilHandler.humanBytes(size);
                meta = meta.isBlank() ? sizeStr : (meta + " • " + sizeStr);
            }
            String caption = (name == null ? "" : name);
            if (!meta.isBlank()) caption = caption.isBlank() ? meta : (caption + " • " + meta);
            cap.setText(caption);
        });
    }


    private void refreshVideoLabels(String fid, Long sizeHint) {
        if (fid == null || fid.isBlank()) return;
        Platform.runLater(() -> {
            HBox row = controller.findRowByFid(fid);
            if (row == null) return;
            var children = row.getChildren();
            if (children.isEmpty()) return;
            javafx.scene.Node bubble = (row.getAlignment() == javafx.geometry.Pos.CENTER_LEFT) ? children.get(0) : children.get(children.size() - 1);
            if (!(bubble instanceof javafx.scene.layout.VBox box)) return;
            String id = box.getId();
            if (id == null || !id.endsWith("-video")) return;
            javafx.scene.layout.VBox controls = null;
            for (javafx.scene.Node n : box.getChildren()) {
                if (n instanceof javafx.scene.layout.VBox v && "videoControls".equals(v.getId())) { controls = v; break; }
            }
            if (controls == null) {
                if (box.getChildren().size() >= 2 && box.getChildren().get(1) instanceof javafx.scene.layout.VBox v) {
                    controls = v;
                }
            }
            if (controls == null) return;
            javafx.scene.control.Label nameLbl = null, metaLbl = null;
            java.util.List<javafx.scene.Node> cs = controls.getChildren();
            for (javafx.scene.Node n : cs) {
                if (n instanceof javafx.scene.control.Label l) {
                    var styles = l.getStyleClass();
                    if (styles != null && styles.contains("file-name")) nameLbl = l;
                    if (styles != null && styles.contains("meta")) metaLbl = l;
                }
            }
            if (nameLbl == null || metaLbl == null) {
                javafx.scene.control.Label firstLabel = null, secondLabel = null;
                for (javafx.scene.Node n : cs) {
                    if (n instanceof javafx.scene.control.Label l) {
                        if (firstLabel == null) firstLabel = l; else { secondLabel = l; break; }
                    }
                }
                if (nameLbl == null) nameLbl = firstLabel;
                if (metaLbl == null) metaLbl = secondLabel;
            }
            if (nameLbl == null && metaLbl == null) return;
            String name = controller.getFileIdToName().getOrDefault(fid, "");
            String mime = controller.getFileIdToMime().getOrDefault(fid, "");
            long size = -1L;
            if (sizeHint != null && sizeHint >= 0) size = sizeHint;
            else {
                File f = controller.getDlPath().get(fid);
                if (f != null && f.exists()) size = f.length();
            }
            if (nameLbl != null) nameLbl.setText(name);
            String meta = "";
            if (mime != null && !mime.isBlank()) meta = mime;
            if (size >= 0) {
                String sizeStr = UtilHandler.humanBytes(size);
                meta = meta.isBlank() ? sizeStr : (meta + " • " + sizeStr);
            }
            if (metaLbl != null) metaLbl.setText(meta);
        });
    }


    // ===================== MAIN DISPATCH =====================

    public void handleServerFrame(Frame f) {
        if (f == null) return;
        String openPeer = (controller.getSelectedUser() != null) ? controller.getSelectedUser().getUsername() : null;

        switch (f.type) {

            // === DIRECT MESSAGE ===
            case DM -> {
                String sender = f.sender;
                String body = f.body == null ? "" : f.body;

                if (body.startsWith("[CALLLOG]")) {
                    if (openPeer != null && openPeer.equals(sender)) {
                        CallLogData d = parseCallLog(body);
                        renderCallLogOnce(d, true);
                    }
                    break;
                }

                if (openPeer != null && openPeer.equals(sender)) {
                    controller.addTextMessage(body, true, f.transferId);
                }
            }

            // === HISTORY ===
            case HISTORY -> {
                String line = f.body == null ? "" : f.body.trim();

                if (line.startsWith("[HIST IN]")) {
                    String payload = line.substring(9).trim();
                    int p = payload.indexOf(": ");
                    if (p > 0) {
                        String sender = payload.substring(0, p);
                        String body = payload.substring(p + 2);

                        if (body.startsWith("[CALLLOG]")) {
                            if (openPeer != null && openPeer.equals(sender)) {
                                CallLogData d = parseCallLog(body);
                                renderCallLogOnce(d, true);
                            }
                            break;
                        }

                        if (openPeer != null && openPeer.equals(sender)) {
                            long msgId = 0L;
                            try { msgId = Long.parseLong(String.valueOf(f.transferId)); } catch (Exception ignore) {}
                            String msgIdStr = (msgId > 0 ? String.valueOf(msgId) : null);

                            if (body.startsWith("[FILE]")) {
                                String name = body.substring(6).trim();
                                String meta = "";
                                HBox row = controller.addFileMessage(name, meta, true, msgIdStr);
                                if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
                                if (controller.getConnection() != null && controller.getConnection().isAlive() && msgId > 0) {
                                    String key = String.valueOf(msgId);
                                    if (markDownloadRequested(key)) {
                                        try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
                                    }
                                }
                            } else if (body.startsWith("[AUDIO]")) {
                                String dur = "--:--";
                                HBox row = controller.addVoiceMessage(dur, true, msgIdStr);
                                if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
                                if (controller.getConnection() != null && controller.getConnection().isAlive() && msgId > 0) {
                                    String key = String.valueOf(msgId);
                                    if (markDownloadRequested(key)) {
                                        try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
                                    }
                                }
                            } else if (body.startsWith("[VIDEO]")) {
                                String name = body.substring(7).trim();
                                String meta = "";
                                HBox row = controller.addVideoMessage(name, meta, true, msgIdStr);
                                if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
                                if (controller.getConnection() != null && controller.getConnection().isAlive() && msgId > 0) {
                                    String key = String.valueOf(msgId);
                                    if (markDownloadRequested(key)) {
                                        try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
                                    }
                                }
                            } else {
                                controller.addTextMessage(body, true, f.transferId);
                            }
                        }
                    }

                } else if (line.startsWith("[HIST OUT]")) {
                    String body = line.substring(10).trim();

                    if (body.startsWith("[CALLLOG]")) {
                        CallLogData d = parseCallLog(body);
                        renderCallLogOnce(d, false);
                        break;
                    }

                    long msgId = 0L;
                    try { msgId = Long.parseLong(String.valueOf(f.transferId)); } catch (Exception ignore) {}
                    String msgIdStr = (msgId > 0 ? String.valueOf(msgId) : null);

                    if (body.startsWith("[FILE]")) {
                        String name = body.substring(6).trim();
                        String meta = "";
                        HBox row = controller.addFileMessage(name, meta, false, msgIdStr);
                        if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
                        if (controller.getConnection() != null && controller.getConnection().isAlive() && msgId > 0) {
                            String key = String.valueOf(msgId);
                            if (markDownloadRequested(key)) {
                                try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
                            }
                        }
                    } else if (body.startsWith("[AUDIO]")) {
                        String dur = "--:--";
                        HBox row = controller.addVoiceMessage(dur, false, msgIdStr);
                        if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
                        if (controller.getConnection() != null && controller.getConnection().isAlive() && msgId > 0) {
                            String key = String.valueOf(msgId);
                            if (markDownloadRequested(key)) {
                                try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
                            }
                        }
                    } else if (body.startsWith("[VIDEO]")) {
                        String name = body.substring(7).trim();
                        String meta = "";
                        HBox row = controller.addVideoMessage(name, meta, false, msgIdStr);
                        if (msgIdStr != null) controller.getPendingHistoryFileRows().put(msgIdStr, row);
                        if (controller.getConnection() != null && controller.getConnection().isAlive() && msgId > 0) {
                            String key = String.valueOf(msgId);
                            if (markDownloadRequested(key)) {
                                try { controller.getConnection().downloadFileByMsgId(msgId); } catch (IOException ignore) {}
                            }
                        }
                    } else {
                        controller.addTextMessage(body, false, f.transferId);
                    }
                }
            }

            // === FILE_EVT / AUDIO_EVT ===
            case FILE_EVT, AUDIO_EVT -> {
                String json = f.body == null ? "" : f.body;
                String from     = UtilHandler.jsonGet(json, "from");
                String name     = UtilHandler.jsonGet(json, "name");
                String mime     = UtilHandler.jsonGet(json, "mime");
                long   bytes    = UtilHandler.parseLongSafe(UtilHandler.jsonGet(json, "bytes"), 0);
                int    duration = UtilHandler.parseIntSafe(UtilHandler.jsonGet(json, "duration"), 0);

                String uuid   = UtilHandler.jsonGet(json, "uuid");
                String legacy = UtilHandler.jsonGet(json, "id");
                String dbIdStr= UtilHandler.jsonGet(json, "fileId");
                Long   dbId   = null;
                if (dbIdStr != null && !dbIdStr.isBlank()) { try { dbId = Long.parseLong(dbIdStr); } catch (Exception ignore) {} }

                String bubbleKey = (legacy != null && !legacy.isBlank()) ? legacy : (uuid != null && !uuid.isBlank() ? uuid : null);

                if (bubbleKey != null && name != null) controller.getFileIdToName().put(bubbleKey, name);
                if (bubbleKey != null && mime != null && !mime.isBlank()) controller.getFileIdToMime().put(bubbleKey, mime);
                if (dbId != null && dbId > 0) {
                    String dbKey = String.valueOf(dbId);
                    if (name != null) controller.getFileIdToName().put(dbKey, name);
                    if (mime != null && !mime.isBlank()) controller.getFileIdToMime().put(dbKey, mime);
                }

                if (openPeer != null && openPeer.equals(from)) {
                    String displayKey = (dbId != null && dbId > 0) ? String.valueOf(dbId) : (bubbleKey != null ? bubbleKey : "");
                    UtilHandler.MediaKind kind = UtilHandler.classifyMedia(mime, name);
                    String sizeOnly = (bytes > 0) ? UtilHandler.humanBytes(bytes) : "";
                    HBox row;
                    switch (kind) {
                        case IMAGE -> { Image img = new WritableImage(8, 8); row = controller.addImageMessage(img, name + (sizeOnly.isBlank() ? "" : " • " + sizeOnly), true); }
                        case AUDIO -> { String dur = (duration > 0) ? UtilHandler.formatDuration(duration) : "--:--"; row = controller.addVoiceMessage(dur, true, null); }
                        case VIDEO -> { row = controller.addVideoMessage(name, sizeOnly, true, null); }
                        default -> { row = controller.addFileMessage(name, sizeOnly, true, null); }
                    }
                    if (!displayKey.isEmpty()) row.getProperties().put("fid", displayKey);
                    String msgIdStr0 = UtilHandler.jsonGet(json, "messageId");
                    if (msgIdStr0 != null && !msgIdStr0.isBlank()) {
                        controller.getFileIdToMsgId().put(displayKey, msgIdStr0);
                        try { Long.parseLong(msgIdStr0); row.setUserData(msgIdStr0); } catch (Exception ignore) {}
                    }

                    if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                        Long msgId = null;
                        String msgIdS = UtilHandler.jsonGet(json, "messageId");
                        if (msgIdS != null && !msgIdS.isBlank()) { try { msgId = Long.parseLong(msgIdS); } catch (Exception ignore) {} }
                        try {
                            if (dbId != null && dbId > 0) controller.getConnection().downloadFileByFileId(dbId);
                            else if (msgId != null && msgId > 0) controller.getConnection().downloadFileByMsgId(msgId);
                            else if (bubbleKey != null) controller.getConnection().downloadFileLegacy(bubbleKey);
                        } catch (IOException e) { System.err.println("[DL] request failed: " + e.getMessage()); }
                    }
                } else {
                    controller.getPendingFileEvents().computeIfAbsent(from, k -> new ArrayList<>()).add(f);
                }
            }

            // === FILE_META ===
            case FILE_META -> {
                String body = (f.body == null) ? "" : f.body;

                // server có thể trả "fileId" (fid/uuid), "messageId" (MID số), "name", "mime", "size|bytes"
                String fid       = UtilHandler.jsonGet(body, "fileId");
                String msgIdStr  = UtilHandler.jsonGet(body, "messageId");
                String name      = UtilHandler.jsonGet(body, "name");
                String mime      = UtilHandler.jsonGet(body, "mime");
                long   metaSize  = UtilHandler.parseLongSafe(UtilHandler.jsonGet(body, "size"), 0);
                if (metaSize <= 0) metaSize = UtilHandler.parseLongSafe(UtilHandler.jsonGet(body, "bytes"), 0);
                final long sizeHint = metaSize;

                if (fid == null || fid.isBlank()) break; // thiếu fid thì bỏ

                // Lưu map phụ để các nơi khác có thể resolve
                if (msgIdStr != null && !msgIdStr.isBlank()) {
                    controller.getFileIdToMsgId().put(fid, msgIdStr);

                    // Nếu row của lịch sử được tạo tạm bằng MID số, gán lại 'fid' cho đúng
                    HBox rowByHist = controller.getPendingHistoryFileRows().remove(msgIdStr);
                    if (rowByHist != null) {
                        rowByHist.getProperties().put("fid", fid);
                    }

                    // Nếu row hiện tại đang dùng fid (UUID) chưa có MID, gán MID vào userData
                    boolean tagged = false;
                    try {
                        Long.parseLong(msgIdStr); // hợp lệ MID số
                        HBox row = controller.findRowByFid(fid);
                        if (row != null) {
                            Object ud = row.getUserData();
                            boolean hasNumeric = false;
                            if (ud != null) {
                                try { Long.parseLong(String.valueOf(ud)); hasNumeric = true; } catch (Exception ignore) {}
                            }
                            if (!hasNumeric) { // chưa có MID -> gắn luôn
                                row.setUserData(msgIdStr);
                                tagged = true;
                            }
                        }
                    } catch (Exception ignore) {}

                    // Nếu vẫn chưa tag được, rà lùi từ cuối container: tìm bubble outgoing chưa có MID -> gắn
                    if (!tagged) {
                        var mc = controller.getMessageContainer();
                        if (mc != null && !mc.getChildren().isEmpty()) {
                            for (int i = mc.getChildren().size() - 1; i >= 0; i--) {
                                var n = mc.getChildren().get(i);
                                if (n instanceof HBox h) {
                                    Object ud = h.getUserData();
                                    boolean hasNumeric = false;
                                    if (ud != null) {
                                        try { Long.parseLong(String.valueOf(ud)); hasNumeric = true; } catch (Exception ignore) {}
                                    }
                                    if (hasNumeric) continue;

                                    javafx.scene.Node b = h.getChildren().isEmpty()
                                            ? null
                                            : h.getChildren().get(h.getChildren().size() - 1);
                                    if (b instanceof javafx.scene.layout.VBox vb) {
                                        String bid = vb.getId();
                                        if (bid != null && bid.startsWith("outgoing-")) {
                                            h.setUserData(msgIdStr);
                                            h.getProperties().put("fid", fid);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Lưu size/mime/name để các UI refresher dùng
                if (sizeHint > 0) controller.getFileIdToSize().put(fid, sizeHint);
                if (mime == null || mime.isBlank()) mime = "application/octet-stream";
                controller.getFileIdToMime().put(fid, mime);
                if (name != null && !name.isBlank()) controller.getFileIdToName().put(fid, name);

                // Chuẩn bị file tạm để nhận CHUNK
                try {
                    String ext = UtilHandler.guessExt(mime, controller.getFileIdToName().get(fid));
                    File tmp = File.createTempFile("im_", "_" + fid + (ext == null ? "" : ext));
                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmp));
                    controller.getDlPath().put(fid, tmp);
                    controller.getDlOut().put(fid, bos);
                } catch (Exception ex) {
                    // không chặn luồng nếu tạo file tạm lỗi
                }

                // Cập nhật lại UI: file thường/ảnh/video
                UtilHandler.MediaKind kind = UtilHandler.classifyMedia(
                        mime, controller.getFileIdToName().get(fid));

                switch (kind) {
                    case FILE -> Platform.runLater(() -> {
                        HBox row = controller.findRowByFid(fid);
                        if (row != null && controller.getMediaHandler() != null) {
                            // cập nhật label tên/kích thước cho bubble file
                            controller.getMediaHandler().updateGenericFileMeta(row, fid, sizeHint);
                        }
                    });
                    case IMAGE -> { // ảnh chỉ có ImageView, caption sẽ cập nhật bằng tên + mime + size
                        refreshImageCaption(fid, sizeHint);
                    }
                    case VIDEO -> { // video: cập nhật label name/meta trong "videoControls"
                        refreshVideoLabels(fid, sizeHint);
                    }
                    default -> { /* AUDIO/meta khác sẽ cập nhật sau khi nhận xong FILE_CHUNK */ }
                }
            }

            // === FILE_CHUNK ===
            case FILE_CHUNK -> {
                String fid = f.transferId;
                byte[] data = (f.bin == null) ? new byte[0] : f.bin;
                BufferedOutputStream bos = controller.getDlOut().get(fid);
                if (bos != null) {
                    try { if (data.length > 0) bos.write(data); } catch (IOException e) { System.err.println("[DL] write failed: " + e.getMessage()); }
                    if (f.last) {
                        try { bos.flush(); bos.close(); } catch (Exception ignore) {}
                        controller.getDlOut().remove(fid);
                        File file = controller.getDlPath().remove(fid);
                        if (file != null) {
                            Platform.runLater(() -> {
                                HBox row = controller.findRowByFid(fid);
                                if (row == null) return;
                                String mime = controller.getFileIdToMime().getOrDefault(fid, "application/octet-stream");
                                UtilHandler.MediaKind kind = UtilHandler.classifyMedia(mime, controller.getFileIdToName().get(fid));
                                String fileUrl = file.toURI().toString();
                                try {
                                    switch (kind) {
                                        case AUDIO -> controller.getMediaHandler().updateVoiceBubbleFromUrl(row, fileUrl);
                                        case VIDEO -> { controller.getMediaHandler().updateVideoBubbleFromUrl(row, fileUrl); refreshVideoLabels(fid, file.length()); }
                                        case IMAGE -> { controller.getMediaHandler().updateImageBubbleFromUrl(row, fileUrl); refreshImageCaption(fid, file.length()); }
                                        default -> controller.getMediaHandler().updateGenericFileMetaByFid(fid);
                                    }
                                } catch (Exception ex) {}
                            });
                        }
                    }
                }
            }

            // === DELETE & EDIT ===
            case DELETE_MSG -> {
                String id = f.transferId;
                if (id != null) {
                    Platform.runLater(() -> controller.removeMessageById(id));
                }
            }
            case EDIT_MSG -> {
                String id = f.transferId;
                String newBody = (f.body == null) ? "" : f.body;
                if (id != null) {
                    Platform.runLater(() -> controller.updateTextBubbleById(id, newBody));
                }
            }

            // === ACK / ERROR ===
            case ACK -> {
                String body = (f.body == null) ? "" : f.body;

                // 1) Giữ nhánh cũ
                if (f.transferId != null && (body.startsWith("OK DM") || body.startsWith("OK QUEUED"))) {
                    controller.tagNextPendingOutgoing(f.transferId);
                    break;
                }

                // 2) NHÁNH MỚI: ACK khi upload file xong
                if (body.contains("\"status\"") && body.contains("FILE_SAVED")) {
                    // server set transferId = uuid gốc khi gửi
                    String uuid = f.transferId;

                    String msgIdStr = UtilHandler.jsonGet(body, "messageId");
                    String fileIdStr = UtilHandler.jsonGet(body, "fileId");
                    String bytesStr  = UtilHandler.jsonGet(body, "bytes");
                    String mime      = UtilHandler.jsonGet(body, "mime");

                    Long msgId = null, fileId = null, bytes = null;
                    try { if (msgIdStr  != null) msgId  = Long.parseLong(msgIdStr); } catch (Exception ignore) {}
                    try { if (fileIdStr != null) fileId = Long.parseLong(fileIdStr);} catch (Exception ignore) {}
                    try { if (bytesStr  != null) bytes  = Long.parseLong(bytesStr);} catch (Exception ignore) {}

                    // Tìm bubble outgoing bạn vừa tạo bằng fid=uuid
                    HBox row = (uuid != null) ? controller.findRowByFid(uuid) : null;
                    if (row == null && uuid != null) {
                        row = controller.getOutgoingFileBubbles().get(uuid);
                    }
                    if (row != null) {
                        // Cập nhật định danh chuẩn cho row
                        if (fileId != null) {
                            row.getProperties().put("fid", String.valueOf(fileId));
                        }
                        if (msgId != null) {
                            row.setUserData(String.valueOf(msgId));
                        }

                        // Lưu map để các chỗ khác resolve
                        if (fileId != null && msgId != null) {
                            controller.getFileIdToMsgId().put(String.valueOf(fileId), String.valueOf(msgId));
                        }
                        if (mime != null && !mime.isBlank()) {
                            controller.getFileIdToMime().put(String.valueOf(fileId), mime);
                        }
                        if (bytes != null && bytes >= 0) {
                            controller.getFileIdToSize().put(String.valueOf(fileId), bytes);
                        }

                        // Đổi key trong outgoingFileBubbles: uuid -> fileId (nếu có)
                        if (uuid != null && fileId != null) {
                            controller.getOutgoingFileBubbles().remove(uuid);
                            controller.getOutgoingFileBubbles().put(String.valueOf(fileId), row);
                        }
                    }

                    // Quan trọng: CHỦ ĐỘNG tải về để cập nhật duration/thumbnail
                    if (controller.getConnection() != null && controller.getConnection().isAlive() && fileId != null) {
                        try { controller.getConnection().downloadFileByFileId(fileId); } catch (IOException ignore) {}
                    }

                    break;
                }
            }

            case ERROR -> Platform.runLater(() -> controller.showErrorAlert("Lỗi: " + f.body));
        }
    }

    // === SEND TEXT ===
    public void onSendMessage() {
        if (controller.getMessageField() == null) return;
        String text = controller.getMessageField().getText().trim();
        if (text.isEmpty() || controller.getSelectedUser() == null) return;

        if (controller.getConnection() != null && controller.getConnection().isAlive()) {
            try {
                String from = (controller.getCurrentUser() != null ? controller.getCurrentUser().getUsername() : "");
                controller.getConnection().dm(from, controller.getSelectedUser().getUsername(), text);
            } catch (IOException ioe) {
                System.err.println("[NET] DM failed: " + ioe.getMessage());
                Platform.runLater(() -> controller.showErrorAlert("Gửi tin nhắn thất bại: " + ioe.getMessage()));
            }
        }

        HBox row = controller.addTextMessage(text, false);
        controller.enqueuePendingOutgoing(row);
        controller.getMessageField().clear();
    }

    // === CALL LOG UTIL ===
    private static final class CallLogData {
        final String icon, title, subtitle, callId, caller, callee;
        CallLogData(String icon, String title, String subtitle, String callId, String caller, String callee) {
            this.icon = icon; this.title = title; this.subtitle = subtitle;
            this.callId = callId; this.caller = caller; this.callee = callee;
        }
    }

    private static CallLogData parseCallLog(String body) {
        try {
            int i = body.indexOf('{');
            if (!body.startsWith("[CALLLOG]") || i < 0) return null;
            String json = body.substring(i);
            String icon     = UtilHandler.jsonGet(json, "icon");
            String title    = UtilHandler.jsonGet(json, "title");
            String subtitle = UtilHandler.jsonGet(json, "subtitle");
            String callId   = UtilHandler.jsonGet(json, "callId");
            String caller   = UtilHandler.jsonGet(json, "caller");
            String callee   = UtilHandler.jsonGet(json, "callee");
            if (title == null) title = "";
            if (subtitle == null) subtitle = "";
            if (icon == null || icon.isBlank()) icon = "🎥";
            return new CallLogData(icon, title, subtitle, callId, caller, callee);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isIncomingForThisClient(CallLogData d) {
        String me = (controller.getCurrentUser() != null)
                ? controller.getCurrentUser().getUsername() : null;
        if (me == null) return true;
        if (d == null)  return true;

        if (d.caller != null && !d.caller.isBlank()) {
            return !me.equals(d.caller);
        }
        if (d.callee != null && !d.callee.isBlank()) {
            return me.equals(d.callee);
        }
        return true;
    }

    private void renderCallLogOnce(CallLogData d, boolean defaultIncoming) {
        if (d == null) return;
        if (d.callId != null && !d.callId.isBlank()) {
            if (!renderedCallLogIds.add(d.callId)) return;
        }
        boolean incoming = (d.caller != null || d.callee != null)
                ? isIncomingForThisClient(d)
                : defaultIncoming;
        controller.addCallLog(d.icon, d.title, d.subtitle, incoming);
    }
    
    
}
