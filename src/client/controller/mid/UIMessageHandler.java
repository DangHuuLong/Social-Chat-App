package client.controller.mid;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JDialog;
import javax.swing.JOptionPane;

import client.controller.MidController;

public class UIMessageHandler {
    private final MidController controller;
    public UIMessageHandler(MidController controller) {
        this.controller = controller;
    }

    /* chỉ giữ size nếu meta có cả MIME • SIZE */
    private static String normalizeSizeOnly(String meta) {
        if (meta == null) return "";
        String m = meta;
        int bullet = m.lastIndexOf('•');
        if (bullet >= 0) m = m.substring(bullet + 1);
        m = m.trim();
        if (m.matches("(?i)\\d+(?:\\.\\d+)?\\s*(B|KB|MB|GB|TB)")) return m;
        if (m.matches("(?i)\\d+(?:\\.\\d+)?(B|KB|MB|GB|TB)")) return m;
        return "";
    }
    
    private void attachSideMenu(HBox row, Region spacer, boolean incoming, String messageId) {
        System.out.println("[MENU] attachSideMenu incoming=" + incoming + " paramMsgId=" + messageId);

        HBox spacerBox = new HBox();
        HBox.setHgrow(spacerBox, Priority.ALWAYS);
        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        StackPane holder = new StackPane();
        holder.setPickOnBounds(false);
        StackPane.setAlignment(holder, incoming ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        Button menuBtn = new Button("⋮");
        menuBtn.setFocusTraversable(false);
        menuBtn.getStyleClass().add("msg-menu");
        menuBtn.setOpacity(0);
        holder.getChildren().add(menuBtn);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(120), menuBtn);
        fadeIn.setToValue(1.0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(120), menuBtn);
        fadeOut.setToValue(0.0);

        ContextMenu cm = new ContextMenu();
        cm.getStyleClass().add("msg-context");
        MenuItem miEdit = new MenuItem("Chỉnh sửa");
        miEdit.getStyleClass().add("msg-context-item");
        MenuItem miDelete = new MenuItem("Xóa");
        miDelete.getStyleClass().add("msg-context-item-danger");
        cm.getItems().addAll(miEdit, miDelete);
        MenuItem miDownload = new MenuItem("Tải xuống…");
        miDownload.getStyleClass().add("msg-context-item");
        cm.getItems().add(new SeparatorMenuItem());
        cm.getItems().add(miDownload);
        MenuItem miReply = new MenuItem("Trả lời");
        miReply.getStyleClass().add("msg-context-item");
        cm.getItems().add(miReply);

        Node bubble = null;
        if (incoming) {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(0);
        } else {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(row.getChildren().size() - 1);
        }
        String bubbleId = (bubble instanceof Region r) ? r.getId() : null;

        final Label labelRef = findTextLabelInRow(row, incoming);
        final boolean canEdit = (labelRef != null) && "outgoing-text".equals(
                (row.getChildren().isEmpty() ? null
                        : ((Region)(incoming ? row.getChildren().get(0) : row.getChildren().get(row.getChildren().size()-1)))).getId());
        miEdit.setDisable(!canEdit);

        miEdit.setOnAction(e -> {
            if (!canEdit) return;
            final Object msgId = (messageId != null) ? messageId : row.getUserData();
            if (msgId == null) return;
            final String current = labelRef.getText();

            TextInputDialog dialog = new TextInputDialog(current);
            dialog.setTitle("Chỉnh sửa tin nhắn");
            dialog.setHeaderText(null);
            dialog.setContentText("Nội dung mới:");
            dialog.getDialogPane().getStyleClass().add("msg-edit-dialog");

            dialog.showAndWait().ifPresent(newText -> {
                String trimmed = (newText == null) ? "" : newText.trim();
                if (trimmed.isEmpty() || trimmed.equals(current)) return;

                labelRef.setText(trimmed);
                try {
                    long id = Long.parseLong(String.valueOf(msgId));
                    if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                        System.out.println("[MENU] EDIT send id=" + id);
                        controller.getConnection().editMessage(
                            id,
                            controller.getCurrentUser().getUsername(),
                            controller.getSelectedUser().getUsername(),
                            trimmed
                        );
                    } else {
                        System.out.println("[MENU] EDIT skipped, no connection");
                    }
                } catch (Exception ex) {
                    System.out.println("[MENU] EDIT failed ex=" + ex);
                }
            });
        });

        miDelete.setOnAction(e -> {
            Object ud = (messageId != null) ? messageId : row.getUserData();
            String midStr = (ud == null) ? null : String.valueOf(ud);

            boolean numeric = false;
            if (midStr != null) {
                try { Long.parseLong(midStr); numeric = true; } catch (Exception ignore) {}
            }
            if (!numeric) {
                Object fidObj = row.getProperties().get("fid");
                String fid = (fidObj == null) ? null : String.valueOf(fidObj);
                if (fid != null) {
                    String mapped = controller.getFileIdToMsgId().get(fid);
                    if (mapped != null && !mapped.isBlank()) {
                        try { Long.parseLong(mapped); numeric = true; midStr = mapped; } catch (Exception ignore) {}
                    }
                }
                if (numeric) {
                    row.setUserData(midStr);
                }
            }

            if (!numeric) {
                System.out.println("[MENU] DELETE blocked: missing numeric messageId (waiting for FILE_META to map fid->mid)");
                controller.showErrorAlert("Tin nhắn vừa gửi chưa đồng bộ ID. Hãy thử lại sau một lát.");
                return;
            }

            try {
                long id = Long.parseLong(midStr);
                System.out.println("[MENU] DELETE send id=" + id);
                if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                    controller.getConnection().deleteMessage(id);
                }
                controller.getMessageContainer().getChildren().remove(row);
            } catch (Exception ex) {
                System.out.println("[MENU] DELETE failed ex=" + ex);
            }
        });

        miDownload.setOnAction(e -> {
            System.out.println("[DLSAVE] click Tải xuống…");
            try {
                Object fidObj = row.getProperties().get("fid");
                String fid = (fidObj == null) ? null : String.valueOf(fidObj);

                String midStr = null;
                Object ud = row.getUserData();
                if (ud != null && isNumeric(String.valueOf(ud))) {
                    midStr = String.valueOf(ud);
                } else if (fid != null) {
                    String mapped = controller.getFileIdToMsgId().get(fid);
                    if (isNumeric(mapped)) midStr = mapped;
                }

                String suggestedName = null;
                if (fid != null) suggestedName = controller.getFileIdToName().get(fid);
                if (suggestedName == null || suggestedName.isBlank()) suggestedName = "download";

                FileChooser fc = new FileChooser();
                String suggested = suggestedName;
                if (!suggested.contains(".")) {
                    String ext = UtilHandler.guessExt(
                            (fid != null ? controller.getFileIdToMime().get(fid) : null),
                            suggestedName
                    );
                    if (ext != null && !ext.isBlank()) suggested += ext;
                }
                fc.setInitialFileName(suggested);
                Window owner = (controller.getMessageContainer()!=null && controller.getMessageContainer().getScene()!=null)
                        ? controller.getMessageContainer().getScene().getWindow() : null;
                File dest = fc.showSaveDialog(owner);
                if (dest == null) {
                    System.out.println("[DLSAVE] user canceled save dialog");
                    return;
                }
                System.out.println("[DLSAVE] user choose dest=" + dest.getAbsolutePath());

                if (fid == null) {
                    fid = (midStr != null) ? ("MID_" + midStr) : null;
                    if (fid != null) row.getProperties().put("fid", fid);
                }
                if (fid == null) {
                    controller.showErrorAlert("Không xác định được tệp để tải xuống.");
                    return;
                }

                BufferedOutputStream existedBos = controller.getDlOut().get(fid);
                File existedPath = controller.getDlPath().get(fid);

                if (existedBos != null && existedPath != null) {
                    row.getProperties().put("moveToChosen", dest);
                    row.getProperties().put("saveToChosen", Boolean.TRUE);
                    System.out.println("[DLSAVE] mark move after done. temp=" + existedPath.getAbsolutePath()
                            + " -> chosen=" + dest.getAbsolutePath());
                } else {
                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dest));
                    controller.getDlOut().put(fid, bos);
                    controller.getDlPath().put(fid, dest);
                    row.getProperties().put("saveToChosen", Boolean.TRUE);
                }

                if (controller.getConnection()!=null && controller.getConnection().isAlive()) {
                    if (midStr != null && isNumeric(midStr)) {
                        System.out.println("[DLSAVE] request download by messageId=" + midStr);
                        controller.getConnection().downloadFileByMsgId(Long.parseLong(midStr));
                    } else if (isNumeric(fid)) {
                        System.out.println("[DLSAVE] request download by fileId=" + fid);
                        controller.getConnection().downloadFileByFileId(Long.parseLong(fid));
                    } else {
                        System.out.println("[DLSAVE] request download legacy by fid=" + fid);
                        controller.getConnection().downloadFileLegacy(fid);
                    }
                } else {
                    controller.showErrorAlert("Không có kết nối tới máy chủ.");
                }
            } catch (Exception ex) {
                System.out.println("[DLSAVE] failed ex=" + ex);
                controller.showErrorAlert("Tải xuống thất bại: " + ex.getMessage());
            }
        });
        
        miReply.setOnAction(e -> {
            controller.showReplyPreview(row, incoming);
        });

        cm.setOnShowing(e -> {
            Node b = null;
            if (incoming) { if (!row.getChildren().isEmpty()) b = row.getChildren().get(0); }
            else { if (!row.getChildren().isEmpty()) b = row.getChildren().get(row.getChildren().size() - 1); }
            String bid = (b instanceof Region r) ? r.getId() : null;
            boolean isOutgoing = bid != null && bid.startsWith("outgoing-");
            System.out.println("[MENU] onShowing bubbleId=" + bid + " isOutgoing=" + isOutgoing);

            Object ud = row.getUserData();
            boolean hasNumeric = false;
            if (ud != null) {
                try { Long.parseLong(String.valueOf(ud)); hasNumeric = true; } catch (Exception ignore2) {}
            }
            System.out.println("[MENU] onShowing userData=" + ud + " hasNumeric=" + hasNumeric);

            if (!hasNumeric) {
                Object fid = row.getProperties().get("fid");
                System.out.println("[MENU] onShowing tryResolve from fid=" + fid);
                if (fid != null) {
                    String mid = controller.getFileIdToMsgId().get(String.valueOf(fid));
                    System.out.println("[MENU] onShowing map(fid->mid)=" + mid);
                    if (mid != null && !mid.isBlank()) {
                        try {
                            Long.parseLong(mid);
                            row.setUserData(mid);
                            hasNumeric = true;
                            System.out.println("[MENU] onShowing resolved mid set userData=" + mid);
                        } catch (Exception ignore3) {
                            System.out.println("[MENU] onShowing mid not numeric=" + mid);
                        }
                    }
                }
            }

            boolean hasFid = (row.getProperties().get("fid") != null);
            boolean enableDelete = isOutgoing && (hasNumeric || hasFid);
            miDelete.setDisable(!enableDelete);
            System.out.println("[MENU] onShowing enableDelete=" + enableDelete);
            boolean isFileLike = false;
            if (bid != null) {
                isFileLike = bid.endsWith("-file") || bid.endsWith("-image")
                        || bid.endsWith("-video") || bid.endsWith("-voice");
            }
            miDownload.setDisable(!isFileLike);

            Object fidObj2 = row.getProperties().get("fid");
            System.out.println("[DLSAVE] onShowing bubbleId=" + bid
                    + " isFileLike=" + isFileLike
                    + " fid=" + (fidObj2==null?null:String.valueOf(fidObj2)));

            menuBtn.setOpacity(1.0);
        });

        menuBtn.setOnAction(e -> {
            System.out.println("[MENU] open clicked");
            cm.show(menuBtn, Side.BOTTOM, 0, 0);
        });
        cm.setOnHiding(e -> {
            System.out.println("[MENU] menu hiding");
            fadeOut.playFromStart();
        });

        row.setOnMouseEntered(e -> {
            fadeIn.playFromStart();
        });
        row.setOnMouseExited(e -> {
            if (!cm.isShowing()) fadeOut.playFromStart();
        });

        if (incoming) spacerBox.getChildren().addAll(holder, filler);
        else spacerBox.getChildren().addAll(filler, holder);

        int idx = row.getChildren().indexOf(spacer);
        if (idx >= 0) row.getChildren().set(idx, spacerBox);

        if (messageId != null) {
            row.setUserData(messageId);
            System.out.println("[MENU] preset userData from param=" + messageId);
        }
    }
    
    /*REPLY*/
    private VBox buildReplyChipForRowToRow(HBox srcRow, HBox newMsgRow, boolean newMsgIncoming) {
        // Tạo chip giống buildReplyChipForCurrentContext nhưng nguồn là srcRow
        Node srcBubble = (srcRow.getAlignment() == Pos.CENTER_LEFT)
                ? srcRow.getChildren().get(0)
                : srcRow.getChildren().get(srcRow.getChildren().size()-1);
        String srcId = (srcBubble instanceof Region r) ? r.getId() : null;

        String title = buildReplyTitleForRows(srcRow, newMsgRow);

        String snippet = "Tin nhắn";
        if (srcId != null && srcId.endsWith("-text")) {
            if (srcBubble instanceof VBox vb) {
                for (Node n : vb.getChildren()) if (n instanceof Label l) { snippet = l.getText(); break; }
            }
        } else if (srcId != null && srcId.endsWith("-image"))  snippet = "Ảnh";
        else if (srcId != null && srcId.endsWith("-video"))    snippet = "Video";
        else if (srcId != null && srcId.endsWith("-voice"))    snippet = "Tin nhắn thoại";
        else if (srcId != null && srcId.endsWith("-file")) {
            if (srcBubble instanceof VBox vb) {
                for (Node c : vb.lookupAll("#fileNamePrimary")) if (c instanceof Label l) { snippet = l.getText(); break; }
            }
            if (snippet == null || snippet.isBlank()) snippet = "Tệp đính kèm";
        } else snippet = "Tin nhắn";

        if (snippet != null && snippet.length() > 140) snippet = snippet.substring(0,140) + "…";

        VBox chip = new VBox(2);
        chip.getStyleClass().add("reply-chip");
        Label lbTitle = new Label("↪ " + title); lbTitle.getStyleClass().add("reply-chip-title");
        Label lbText  = new Label(snippet == null ? "" : snippet);
        lbText.setWrapText(true); lbText.getStyleClass().add("reply-chip-text");
        chip.getChildren().addAll(lbTitle, lbText);

        // click để scroll tới nguồn
        chip.setOnMouseClicked(ev -> controller.scrollToRow(srcRow));

        return chip;
    }

    public void attachReplyChipById(HBox newMsgRow, boolean newMsgIncoming, String replyToId) {
        if (replyToId == null || replyToId.isBlank() || newMsgRow == null) return;

        // Tìm nguồn (message bị reply)
        HBox srcRow = controller.findRowByUserData(replyToId);
        if (srcRow == null) {
            // treo chờ tới khi message gốc render xong
            controller.getPendingReplyLinks()
                    .computeIfAbsent(replyToId, k -> new java.util.ArrayList<>())
                    .add(newMsgRow);
            return;
        }

        // Tìm/chuẩn hoá bubble đích (new message) – hỗ trợ mọi loại bubble
        VBox targetBox = findBubbleBox(newMsgRow);
        if (targetBox == null) return;

        // Tránh gắn trùng
        if (hasReplyChip(targetBox)) return;

        // Build chip
        VBox chip = buildReplyChipForRowToRow(srcRow, newMsgRow, newMsgIncoming);

        // Gắn chip lên đầu bubble
        targetBox.getChildren().add(0, chip);

        // Lưu lại replyTo để có thể re-attach nếu bubble bị thay thế sau này
        newMsgRow.getProperties().putIfAbsent("replyTo", replyToId);
    }
    
    // ===== Reply title helpers =====
    // Tìm VBox bubble (hoặc wrapper) để gắn chip, hỗ trợ mọi loại bubble
    private VBox findBubbleBox(HBox row) {
        if (row == null || row.getChildren().isEmpty()) return null;

        Node bubble = (row.getAlignment()==Pos.CENTER_LEFT)
                ? row.getChildren().get(0)
                : row.getChildren().get(row.getChildren().size()-1);

        // Nếu đã là VBox thì kiểm tra id
        if (bubble instanceof VBox box) {
            String id = box.getId();
            if (id == null) return null;
            if (id.endsWith("-text") || id.endsWith("-file") ||
                id.endsWith("-image") || id.endsWith("-video") ||
                id.endsWith("-voice")) {
                return box;
            }
            // Trường hợp là wrapper không có id, vẫn dùng được
            return box;
        }

        // Với voice hoặc video ban đầu là HBox -> chưa có wrapper
        if (bubble instanceof HBox hb) {
            // Tạo wrapper để chứa chip + HBox gốc
            VBox wrapper = new VBox(6);
            // giữ nguyên id để CSS hoạt động như cũ
            if (hb.getId() != null) { 
                wrapper.setId(hb.getId()); 
                hb.setId(null);
            }
            int idx = (row.getAlignment()==Pos.CENTER_LEFT) ? 0 : row.getChildren().size()-1;
            wrapper.getChildren().add(hb);
            row.getChildren().set(idx, wrapper);
            return wrapper;
        }

        return null;
    }

    // Kiểm tra đã có chip hay chưa (tránh duplicate)
    private boolean hasReplyChip(VBox bubbleBox) {
        if (bubbleBox == null) return false;
        for (Node n : bubbleBox.getChildren()) {
            if (n instanceof VBox vb && vb.getStyleClass().contains("reply-chip")) return true;
        }
        return false;
    }

    private String peerName() {
        return (controller.getSelectedUser() != null && controller.getSelectedUser().getUsername() != null)
                ? controller.getSelectedUser().getUsername()
                : "đối phương";
    }

    /** 
     * Tạo title theo góc nhìn người xem:
     * - actorIsViewer: người trả lời có phải "tôi" (viewer) không
     * - sourceIsViewer: tin nhắn được trả lời có phải của "tôi" không
     */
    private String buildReplyTitle(boolean actorIsViewer, boolean sourceIsViewer) {
        String actorPhrase  = actorIsViewer  ? "Bạn" : peerName();
        String sourcePhrase;
        if (actorIsViewer == sourceIsViewer) {
            // cùng phía -> trả lời chính mình
            sourcePhrase = "tin nhắn chính mình";
        } else if (sourceIsViewer) {
            sourcePhrase = "tin nhắn của bạn";
        } else {
            sourcePhrase = peerName();
        }
        return actorPhrase + " đã trả lời " + sourcePhrase;
    }

    /** Dựa trên alignment của 2 hàng để biết actor/source có phải viewer không */
    private String buildReplyTitleForRows(HBox srcRow, HBox newMsgRow) {
        boolean actorIsViewer  = (newMsgRow.getAlignment() == Pos.CENTER_RIGHT); // outgoing từ viewer
        boolean sourceIsViewer = (srcRow.getAlignment()    == Pos.CENTER_RIGHT); // tin nguồn do viewer gửi
        return buildReplyTitle(actorIsViewer, sourceIsViewer);
    }

    public void reattachReplyChipIfAny(HBox row) {
        if (row == null) return;
        Object rt = row.getProperties().get("replyTo");
        if (rt == null) return;
        // gắn lại nếu bubble vừa bị replace
        VBox target = findBubbleBox(row);
        if (target == null) return;
        if (!hasReplyChip(target)) {
            attachReplyChipById(row, row.getAlignment()==Pos.CENTER_LEFT, String.valueOf(rt));
        }
    }

    /**/
    private void scrollToBottom() {
        var n = controller.getMessageContainer();
        var p = n.getParent();
        while (p != null && !(p instanceof ScrollPane)) p = p.getParent();
        if (p instanceof ScrollPane sp) {
            Platform.runLater(() -> {
                sp.layout();
                sp.setVvalue(1.0);
            });
        }
    }
    
    private static boolean isNumeric(String s){
        if (s == null) return false;
        try { Long.parseLong(s); return true; } catch (Exception ignore){ return false; }
    }

    private HBox addRowWithBubble(Node bubble, boolean incoming, String messageId) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }

        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(bubble, spacer);
        else          row.getChildren().addAll(spacer, bubble);

        attachSideMenu(row, spacer, incoming, messageId);

        controller.getMessageContainer().getChildren().add(row);
        scrollToBottom();
        
        Object ud = row.getUserData();
        if (ud != null) {
            String id = String.valueOf(ud);
            var waiters = controller.getPendingReplyLinks().remove(id);
            if (waiters != null) {
                for (HBox waiter : waiters) {
                    boolean incomingWaiter = (waiter.getAlignment() == Pos.CENTER_LEFT);
                    attachReplyChipById(waiter, incomingWaiter, id); // bây giờ id đã có srcRow
                }
            }
        }
        return row;
    }

    public HBox addTextMessage(String text, boolean incoming, String messageId) {
        VBox bubble = new VBox();
        bubble.setMaxWidth(420);
        bubble.setId(incoming ? "incoming-text" : "outgoing-text");

        Label lbl = new Label(text);
        lbl.setWrapText(true);
        bubble.getChildren().add(lbl);

        HBox row = addRowWithBubble(bubble, incoming, messageId);
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null && bubble instanceof VBox vb) vb.getChildren().add(0, chip);
            controller.clearReplyPreview();
        }
        return row;
    }

    /* IMAGE: chỉ ImageView, không label */
    public HBox addImageMessage(Image img, String caption, boolean incoming, String messageId) {
        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        box.getChildren().add(iv);
        HBox row = addRowWithBubble(box, incoming, messageId);
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) box.getChildren().add(0, chip);
            controller.clearReplyPreview();
        }
        return row;
    }

    /* FILE: chỉ tên + kích thước (lọc MIME) */
    public HBox addFileMessage(String filename, String meta, boolean incoming, String messageId) {
        // Tạo bubble
        VBox box = new VBox();
        box.setId(incoming ? "incoming-file" : "outgoing-file");
        box.setPadding(new Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename == null ? "" : filename);
        nameLbl.setId("fileNamePrimary");
        nameLbl.getStyleClass().add("file-name");

        // --- TÍNH SIZE CHỈ KHI messageId LÀ fid (UUID), KHÔNG PHẢI MID SỐ ---
        String sizeOnly = normalizeSizeOnly(meta);
        boolean messageIdIsNumeric = false;
        try { if (messageId != null) Long.parseLong(messageId); messageIdIsNumeric = true; } catch (Exception ignore) {}

        if ((sizeOnly == null || sizeOnly.isBlank()) && messageId != null && !messageIdIsNumeric) {
            // dlPath key theo fid (UUID) trong giai đoạn mới gửi
            var f = controller.getDlPath().get(messageId);
            if (f != null && f.exists()) sizeOnly = UtilHandler.humanBytes(f.length());
        }

        Label metaLbl = new Label(sizeOnly == null ? "" : sizeOnly);
        metaLbl.setId("fileMeta");
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.setId("fileInfoBox");
        info.getChildren().addAll(nameLbl, metaLbl);

        Region innerSpacer = new Region();
        HBox.setHgrow(innerSpacer, Priority.ALWAYS);

        content.getChildren().addAll(icon, info, innerSpacer);
        box.getChildren().add(content);

        // Thêm bubble vào hàng và gắn side menu
        HBox row = addRowWithBubble(box, incoming, messageId);

        // Gắn userData/fid để còn resolve về sau
        if (messageId != null) {
            row.setUserData(messageId);
            boolean numeric = false;
            try { Long.parseLong(messageId); numeric = true; } catch (Exception ignore) {}
            if (!numeric) {
                // messageId lúc mới gửi là fid (UUID)
                row.getProperties().put("fid", messageId);
                if (!incoming) controller.getOutgoingFileBubbles().put(messageId, row);
            }
        }

        // Chỉ gọi update meta theo **fid** (UUID) khi chưa có size và messageId là **fid**
        if ((sizeOnly == null || sizeOnly.isBlank())
                && messageId != null
                && !messageIdIsNumeric
                && controller.getMediaHandler() != null) {
            Platform.runLater(() -> controller.getMediaHandler().updateGenericFileMetaByFid(messageId));
        }

        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) box.getChildren().add(0, chip);
            controller.clearReplyPreview();
        }
        return row;
    }

    public HBox addTextMessage(String text, boolean incoming) { return addTextMessage(text, incoming, (String) null); }
    public HBox addImageMessage(Image img, String caption, boolean incoming) { return addImageMessage(img, caption, incoming, (String) null); }
    public HBox addFileMessage(String filename, String meta, boolean incoming) { return addFileMessage(filename, meta, incoming, (String) null); }

    public HBox addVoiceMessage(String duration, boolean incoming, String fileId) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        row.setUserData(fileId);
        if (fileId != null) row.getProperties().put("fid", fileId);
        HBox voiceBox = new HBox(10);
        voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice");
        voiceBox.setAlignment(Pos.CENTER_LEFT);

        Button playBtn = new Button("▶");
        playBtn.getStyleClass().add("audio-btn");
        playBtn.setId("voicePlay");

        Slider slider = new Slider();
        slider.setPrefWidth(200);
        slider.setId("voiceSlider");

        Label dur = new Label(duration);
        dur.setId("voiceDuration");

        voiceBox.getChildren().addAll(playBtn, slider, dur);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(voiceBox, spacer);
        else          row.getChildren().addAll(spacer, voiceBox);

        attachSideMenu(row, spacer, incoming, fileId);

        controller.getMessageContainer().getChildren().add(row);
        if (!incoming && fileId != null) {
            controller.getOutgoingFileBubbles().put(fileId, row);
        }
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                // voice là HBox; ta quấn chip + voiceBox trong VBox cho gọn
                VBox wrapper = new VBox(6);
                wrapper.setId(voiceBox.getId()); // giữ id để CSS áp dụng như cũ
                voiceBox.setId(null);
                wrapper.getChildren().addAll(chip, voiceBox);

                // thay bubble trong row
                int idx = incoming ? 0 : row.getChildren().size()-1;
                row.getChildren().set(idx, wrapper);
            }
            controller.clearReplyPreview();
        }
        return row;
    }

    /* VIDEO: chỉ khu vực phát + nút Play + Slider, KHÔNG label */
    public HBox addVideoMessage(String filename, String meta, boolean incoming, String fileId) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        row.setUserData(fileId);
        if (fileId != null) row.getProperties().put("fid", fileId);
        VBox box = new VBox(6);
        box.setId(incoming ? "incoming-video" : "outgoing-video");
        box.setAlignment(Pos.CENTER_LEFT);

        Region videoArea = new Region();
        videoArea.setPrefSize(320, 180);
        videoArea.setStyle("-fx-background-color: #111111; -fx-background-radius: 8;");
        videoArea.setId("videoArea");

        VBox controls = new VBox(4);
        controls.setId("videoControls");

        Button playBtn = new Button("▶");
        playBtn.setId("videoPlay");

        Slider slider = new Slider();
        slider.setPrefWidth(220);
        slider.setId("videoSlider");

        controls.getChildren().addAll(playBtn, slider);
        box.getChildren().addAll(videoArea, controls);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(box, spacer);
        else          row.getChildren().addAll(spacer, box);

        attachSideMenu(row, spacer, incoming, fileId);

        controller.getMessageContainer().getChildren().add(row);
        if (!incoming && fileId != null) {
            controller.getOutgoingFileBubbles().put(fileId, row);
        }
        if (controller.hasReplyContext()) {
            VBox chip = buildReplyChipForCurrentContext(row, incoming);
            if (chip != null) {
                VBox wrapper = new VBox(6);
                wrapper.setId(box.getId());
                box.setId(null);
                wrapper.getChildren().addAll(chip, box);
                int idx = incoming ? 0 : row.getChildren().size()-1;
                row.getChildren().set(idx, wrapper);
            }
            controller.clearReplyPreview();
        }
        return row;
    }

    public void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + controller.humanize(lastSeenIso, true));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }

    private Label findTextLabelInRow(HBox row, boolean incoming) {
        Node bubble = null;
        if (incoming) {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(0);
        } else {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(row.getChildren().size() - 1);
        }
        if (!(bubble instanceof VBox vb)) return null;

        for (Node n : vb.getChildren()) {
            // bỏ qua reply-chip
            if (n instanceof VBox sub && sub.getStyleClass().contains("reply-chip")) continue;
            if (n instanceof Label lbl) return lbl;
            // nếu nested VBox, tìm tiếp một lớp (bubble text thường đơn giản, nhưng để chắc ăn)
            if (n instanceof VBox nested) {
                for (Node m : nested.getChildren()) {
                    if (m instanceof Label lbl2) return lbl2;
                }
            }
        }
        return null;
    }

    public HBox addCallLogMessage(String iconText, String title, String subtitle, boolean incoming) {
        VBox box = new VBox(8);
        box.setId(incoming ? "incoming-call" : "outgoing-call");
        box.setMaxWidth(420);

        HBox rowTop = new HBox(10);
        rowTop.getStyleClass().add("call-row");
        Label icon = new Label(iconText == null || iconText.isBlank() ? "🎥" : iconText);
        icon.getStyleClass().add("call-icon");

        VBox texts = new VBox(2);
        Label t1 = new Label(title == null ? "" : title);
        t1.getStyleClass().add("call-title");
        Label t2 = new Label(subtitle == null ? "" : subtitle);
        t2.getStyleClass().add("call-subtitle");
        texts.getChildren().addAll(t1, t2);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        rowTop.getChildren().addAll(icon, texts, grow);

        Region sep = new Region();
        sep.getStyleClass().add("call-sep");

        Button redial = new Button("Gọi lại");
        redial.getStyleClass().add("call-redial");
        redial.setOnAction(e -> controller.callCurrentPeer());

        box.getChildren().addAll(rowTop, sep, redial);

        return addRowWithBubble(box, incoming, (String) null);
    }
    
    private VBox buildReplyChipForCurrentContext(HBox newMsgRow, boolean newMsgIncoming) {
        // lấy nguồn đang được reply
        HBox srcRow = controller.getReplyingRow();
        if (srcRow == null) return null;

        boolean srcIncoming = controller.isReplyingIncoming();

        // Lấy bubble nguồn để trích nội dung/loại
        Node srcBubble = srcIncoming ? srcRow.getChildren().get(0)
                                     : srcRow.getChildren().get(srcRow.getChildren().size()-1);
        String srcId = (srcBubble instanceof Region r) ? r.getId() : null;
        boolean actorIsViewer  = !newMsgIncoming; // tin mới: incoming=false -> viewer là actor
        boolean sourceIsViewer = !srcIncoming; // nguồn: incoming=false -> viewer là source
        // Tạo title & snippet
        String title = buildReplyTitle(actorIsViewer, sourceIsViewer);

        String snippet = "Tin nhắn";
        if (srcId != null && srcId.endsWith("-text")) {
            if (srcBubble instanceof VBox vb) {
                for (Node n : vb.getChildren()) if (n instanceof Label l) { snippet = l.getText(); break; }
            }
        } else if (srcId != null && srcId.endsWith("-image")) {
            snippet = "Ảnh";
        } else if (srcId != null && srcId.endsWith("-file")) {
            String name = null;
            if (srcBubble instanceof VBox vb) {
                for (Node c : vb.lookupAll("#fileNamePrimary")) if (c instanceof Label l) { name = l.getText(); break; }
            }
            snippet = (name != null && !name.isBlank()) ? name : "Tệp đính kèm";
        } else if (srcId != null && srcId.endsWith("-video")) {
            snippet = "Video";
        } else if (srcId != null && srcId.endsWith("-voice")) {
            snippet = "Tin nhắn thoại";
        } else {
            snippet = "Tin nhắn cuộc gọi";
        }

        if (snippet != null && snippet.length() > 140) snippet = snippet.substring(0, 140) + "…";

        // dựng chip
        VBox chip = new VBox(2);
        chip.getStyleClass().add("reply-chip");

        Label lbTitle = new Label("↪ " + title);
        lbTitle.getStyleClass().add("reply-chip-title");

        Label lbText = new Label(snippet == null ? "" : snippet);
        lbText.setWrapText(true);
        lbText.getStyleClass().add("reply-chip-text");

        chip.getChildren().addAll(lbTitle, lbText);

        // click để scroll đến nguồn
        chip.setOnMouseClicked(ev -> controller.scrollToRow(srcRow));

        // trỏ liên kết để dùng về sau (nếu cần)
        Object srcMsgId = srcRow.getUserData();
        if (srcMsgId != null) newMsgRow.getProperties().put("replyTo", String.valueOf(srcMsgId));

        return chip;
    }

}
