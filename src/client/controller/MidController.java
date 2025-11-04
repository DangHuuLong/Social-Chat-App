package client.controller;

import client.ClientConnection;
import client.controller.mid.CallHandler;
import client.controller.mid.FileHandler;
import client.controller.mid.MediaHandler;
import client.controller.mid.MessageHandler;
import client.controller.mid.UIMessageHandler;
import client.controller.mid.UtilHandler;
import client.controller.mid.VoiceRecordHandler;
import client.media.LanAudioSession;
import client.media.LanVideoSession;
import client.signaling.CallSignalListener;
import client.signaling.CallSignalingService;
import common.Frame;
import common.MessageType;
import common.User;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.stage.Window;
import server.dao.UserDAO;
import javafx.util.Duration;
import javax.sound.sampled.AudioFormat;
import java.io.BufferedOutputStream;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class MidController implements CallSignalListener {
	public static final class MsgView {
	    private final long epochMillis;
	    private final boolean incoming;
	    private final String text;
	    public MsgView(long epochMillis, boolean incoming, String text) {
	        this.epochMillis = epochMillis; this.incoming = incoming; this.text = text;
	    }
	    public long epochMillis(){ return epochMillis; }
	    public boolean incoming(){ return incoming; }
	    public String text(){ return text; }
	}
	public List<MsgView> exportMessagesForSearch() {
	    return new ArrayList<>(this.messageSnapshot);
	}
    private Label currentChatName;
    private Label currentChatStatus;
    private VBox messageContainer;
    private TextField messageField;
    private Button logoutBtn;

    private RightController rightController;
    private CallSignalingService callSvc;
    private Stage callStage;
    private VideoCallController callCtrl;
    private String currentCallId;
    private String currentPeer;
    private boolean isCaller = false;

    private User currentUser;
    private User selectedUser;
    private ClientConnection connection;
    private User currentPeerUser;

    private LanVideoSession videoSession;
    private LanAudioSession audioSession;
    
    private ImageView midHeaderAvatar;
	private HBox replyBar;
	private ImageView replyThumb;
	private Label replyFileIcon, replyTitle, replyContent;
	private Button replyCloseBtn;
	private HBox replyingRow = null;
	private boolean replyingIncoming = false;

    private ChangeListener<Number> autoScrollListener;
    private final List<MsgView> messageSnapshot = new ArrayList<>();

    private final Map<String, List<Frame>> pendingFileEvents = new ConcurrentHashMap<>();
    private final Map<String, String> fileIdToName = new ConcurrentHashMap<>();
    private final Map<String, String> fileIdToMime = new ConcurrentHashMap<>();
    public final Map<String, HBox> outgoingFileBubbles = new ConcurrentHashMap<>();

    private final Map<String, BufferedOutputStream> dlOut = new ConcurrentHashMap<>();

    private final VoiceRecordHandler voiceRecordHandler = new VoiceRecordHandler();
    
    private final ArrayDeque<HBox> pendingOutgoingTexts = new ArrayDeque<>();
    
    private final CallHandler callHandler = new CallHandler(this);
    private final Set<String> shownCallLogs = ConcurrentHashMap.newKeySet();
    private final Map<String, HBox> pendingHistoryFileRows = new ConcurrentHashMap<>();
    private final Map<String, MediaPlayer> videoPlayers = new ConcurrentHashMap<>();
    
    
    MediaHandler mediaHandler = new MediaHandler(this);
    private final Map<String, Long> fileIdToSize = new ConcurrentHashMap<>();
    private final java.util.Map<String,String> fileIdToMsgId = new java.util.concurrent.ConcurrentHashMap<>();
    
    private final Map<String, java.util.List<HBox>> pendingReplyLinks = new ConcurrentHashMap<>();
    public Map<String, HBox> getPendingHistoryFileRows() { return pendingHistoryFileRows; }
    public java.util.Map<String,String> getFileIdToMsgId() { return fileIdToMsgId; }
    public Map<String, Long> getFileIdToSize() { return fileIdToSize; }
    public Map<String, java.util.List<HBox>> getPendingReplyLinks() {
        return pendingReplyLinks;
    }
    private final ObservableMap<String, File> dlPath = FXCollections.observableHashMap();
    private final ObservableMap<String, File> downloadedFiles = FXCollections.observableHashMap(); 
    public ObservableMap<String, File> getDlPath() { return dlPath; }
    public ObservableMap<String, File> getDownloadedFiles() {
        return downloadedFiles;
    }
	public MediaHandler getMediaHandler() {
		return mediaHandler;
	}

    public void bind(Label currentChatName, Label currentChatStatus, VBox messageContainer, TextField messageField, ImageView midHeaderAvatar) {
        this.currentChatName = currentChatName;
        this.currentChatStatus = currentChatStatus;
        this.messageContainer = messageContainer;
        this.messageField = messageField;
        this.midHeaderAvatar = midHeaderAvatar;

        if (this.messageField != null) this.messageField.setOnAction(e -> onSendMessage());
    }
    
    public void bindReplyBar(HBox bar, ImageView thumb, Label fileIcon,
            Label title, Label content, Button closeBtn) {
		this.replyBar = bar;
		this.replyThumb = thumb;
		this.replyFileIcon = fileIcon;
		this.replyTitle = title;
		this.replyContent = content;
		this.replyCloseBtn = closeBtn;
		
		if (replyCloseBtn != null) {
			replyCloseBtn.setOnAction(e -> clearReplyPreview());
		}
	}
    public void setRightController(RightController rc) { this.rightController = rc; }
    public void setCurrentUser(User user) { this.currentUser = user; }
    public CallHandler getCallHandler() { return callHandler; }
    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        if (this.connection != null) {
            this.connection.setMidController(this);
        }
        // KHÔNG gọi startListener ở đây nữa
    }

    public void showReplyPreview(HBox row, boolean incoming) {
        if (replyBar == null) return;

        replyThumb.setVisible(false); replyThumb.setManaged(false);
        replyFileIcon.setVisible(false); replyFileIcon.setManaged(false);
        replyTitle.setText("Trả lời");
        String preview = "Tin nhắn";

        Node bubble = incoming ? row.getChildren().get(0)
                               : row.getChildren().get(row.getChildren().size()-1);
        String bid = (bubble instanceof Region r) ? r.getId() : null;

        if (bid != null && bid.endsWith("-text")) {
            if (bubble instanceof VBox vb) {
                for (Node n : vb.getChildren()) {
                    if (n instanceof Label lbl) { preview = lbl.getText(); break; }
                }
            }
        } else if (bid != null && bid.endsWith("-image")) {
            if (bubble instanceof VBox vb) {
                for (Node n : vb.getChildren()) {
                    if (n instanceof ImageView iv && iv.getImage()!=null) {
                        replyThumb.setImage(iv.getImage());
                        replyThumb.setVisible(true); replyThumb.setManaged(true);
                        preview = "Ảnh";
                        break;
                    }
                }
            }
        } else if (bid != null && bid.endsWith("-file")) {
            if (bubble instanceof VBox vb) {
                for (Node c : vb.lookupAll("#fileNamePrimary")) {
                    if (c instanceof Label lbl) { preview = lbl.getText(); break; }
                }
            }
            replyFileIcon.setText("📄");
            replyFileIcon.setVisible(true); replyFileIcon.setManaged(true);
            if (preview == null || preview.isBlank()) preview = "Tệp đính kèm";
        } else if (bid != null && bid.endsWith("-video")) {
            replyFileIcon.setText("🎞️");
            replyFileIcon.setVisible(true); replyFileIcon.setManaged(true);
            preview = "Video";
        } else if (bid != null && bid.endsWith("-voice")) {
            replyFileIcon.setText("🎤");
            replyFileIcon.setVisible(true); replyFileIcon.setManaged(true);
            preview = "Tin nhắn thoại";
        } else {
            replyFileIcon.setText("🎦");
            replyFileIcon.setVisible(true); replyFileIcon.setManaged(true);
            preview = "Tin nhắn cuộc gọi";
        }

        if (preview != null && preview.length() > 140) preview = preview.substring(0,140) + "…";
        replyContent.setText(preview == null ? "" : preview);

        replyBar.setVisible(true);
        replyBar.setManaged(true);

        this.replyingRow = row;
        this.replyingIncoming = incoming;
    }

    public void clearReplyPreview() {
        if (replyBar == null) return;
        replyBar.setVisible(false);
        replyBar.setManaged(false);
        if (replyThumb != null) replyThumb.setImage(null);
        this.replyingRow = null;
    }
    
    public HBox getReplyingRow() { return replyingRow; }
    public boolean isReplyingIncoming() { return replyingIncoming; }
    public boolean hasReplyContext() { return replyingRow != null; }
    
    public void scrollToRow(HBox target) {
        if (target == null || messageContainer == null) return;
        // tìm ScrollPane chứa messageContainer
        Node p = messageContainer.getParent();
        while (p != null && !(p instanceof ScrollPane)) p = p.getParent();
        if (!(p instanceof ScrollPane sp)) return;

        // Tính vvalue tương đối của target trong container
        target.layout(); messageContainer.layout(); sp.layout();
        double y = target.getBoundsInParent().getMinY();
        double contentH = messageContainer.getBoundsInLocal().getHeight();
        double viewportH = sp.getViewportBounds().getHeight();
        double max = Math.max(1e-6, contentH - viewportH);
        double vv = Math.min(1.0, Math.max(0.0, y / max));

        Platform.runLater(() -> {
            sp.setVvalue(vv);
            // hiệu ứng nhỏ để "nháy" highlight (tùy chọn)
            target.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("reply-target"), true);
            new javafx.animation.PauseTransition(Duration.millis(900)).setOnFinished(e ->
                target.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("reply-target"), false)
            );
        });
    }
    
    public void onDownloadCompleted(String fid, File completedFile) {
        if (fid == null || fid.isBlank() || completedFile == null) return;

        downloadedFiles.put(fid, completedFile);

        if (connection != null) {
            try {
                long id = Long.parseLong(fid);      // only works for numeric fids
                connection.markDownloadDone(id);
            } catch (NumberFormatException ignore) {
                // non-numeric (uuid/legacy) -> nothing to do unless you add a String overload
            }
        }
    }
    
    public void onOutgoingFileSaved(String fid, long messageId, long fileId) {
        if (fid == null || fid.isBlank()) return;

        // Lưu map fid -> messageId để nơi khác có thể tra cứu
        if (messageId > 0) {
            fileIdToMsgId.put(fid, String.valueOf(messageId));
        }

        HBox row = outgoingFileBubbles.remove(fid);
        if (row != null) {
            // Gắn lại fid lên properties (để code khác còn truy vết nếu cần)
            row.getProperties().put("fid", fid);

            // QUAN TRỌNG: gắn messageId (numeric) vào userData -> menu Delete dùng được ngay
            row.setUserData(String.valueOf(messageId));
        }
    }

    public void openConversation(User u) {
        System.out.println("[OPEN] conversation with " + u.getUsername());
        this.selectedUser = u;
        this.currentPeerUser = u;
        this.currentPeer = u.getUsername(); // ✅ FIX: remember current peer for 1-1 chat
        if (messageField != null) messageField.clear();
        if (currentChatName != null) currentChatName.setText(u.getUsername());

        try {
            UserDAO.Presence p = UserDAO.getPresence(u.getId());
            boolean online = p != null && p.online;
            String lastSeen = (p != null) ? p.lastSeenIso : null;
            applyStatusLabel(currentChatStatus, online, lastSeen);
            if (rightController != null) rightController.showUser(u, online, lastSeen);
        } catch (SQLException e) {
            e.printStackTrace();
            applyStatusLabel(currentChatStatus, false, null);
            if (rightController != null) rightController.showUser(u, false, null);
        }

        Image peerAvatar = loadAvatarImage(u.getId());
        if (midHeaderAvatar != null && peerAvatar != null) {
            midHeaderAvatar.setImage(peerAvatar);
        }
        if (rightController != null) {
            rightController.setAvatar(peerAvatar);
        }

        if (messageContainer != null) {
            if (messageContainer.getChildren().size() > 100) {
                messageContainer.getChildren().remove(0, messageContainer.getChildren().size() - 100);
            }
            messageContainer.getChildren().clear();
        }

        shownCallLogs.clear();
        messageSnapshot.clear(); 
        enableAutoScroll();

        if (connection != null && connection.isAlive()) {
            try {
                connection.history(currentUser.getUsername(), u.getUsername(), 50);
            } catch (Exception e) {
                System.err.println("[HISTORY] Failed to load history: " + e.getMessage());
                Platform.runLater(() -> showErrorAlert("Lịch sử tin nhắn không tải được: " + e.getMessage()));
            }
        }

        List<Frame> pending = pendingFileEvents.remove(u.getUsername());
        if (pending != null) pending.forEach(this::handleServerFrame);
    }

    
    public void openGroupConversation(LeftController.GroupViewModel g) {
        this.selectedUser = null;
        this.currentPeerUser = null;
        this.currentPeer = "group:" + g.groupId; // để biết peer hiện tại là group

        if (currentChatName != null) currentChatName.setText(g.name);
        if (currentChatStatus != null) currentChatStatus.setText("Nhóm • owner: " + g.owner);

        // avatar nhóm:
        Image groupAvatar = new Image(
            Objects.requireNonNull(
                getClass().getResource("/client/view/images/group.png")
            ).toExternalForm()
        );
        if (midHeaderAvatar != null) midHeaderAvatar.setImage(groupAvatar);
        if (rightController != null) rightController.setAvatar(groupAvatar);

        // clear messageContainer như openConversation(user)
        if (messageContainer != null) {
            messageContainer.getChildren().clear();
        }

        shownCallLogs.clear();
        messageSnapshot.clear();
        enableAutoScroll();

        // xin history từ server cho group
        if (connection != null && connection.isAlive()) {
            try {
                // TOÀN BỘ CHỖ NÀY TÙY VÀO GIAO THỨC SERVER
                // Ví dụ: history(fromUser, toPeer, limit)
                // toPeer = "group:<id>"
            	connection.groupHistory(currentUser.getUsername(), String.valueOf(g.groupId), 50);

            } catch (Exception e) {
                System.err.println("[HISTORY] Failed to load group history: " + e.getMessage());
                Platform.runLater(() -> showErrorAlert("Không tải được lịch sử nhóm."));
            }
        }
        if (rightController != null) {
            User pseudoGroup = new User();
            pseudoGroup.setUsername(g.name); // show group name
            rightController.showGroup(pseudoGroup.getUsername(), groupAvatar);
            rightController.setAvatar(new Image(
                getClass().getResource("/client/view/images/group.png").toExternalForm()
            ));
        }

    }

	private Image loadAvatarImage(int userId) {
	    try {
	        byte[] bytes = server.dao.UserDAO.getAvatarById(userId); 
	        if (bytes != null && bytes.length > 0) {
	            return new Image(new java.io.ByteArrayInputStream(bytes));
	        }
	    } catch (Exception ignore) { }
	    // fallback default
	    return new Image(
	        Objects.requireNonNull(
	            getClass().getResource("/client/view/images/default user.png")
	        ).toExternalForm()
	    );
	}
    
    private void snapshotText(String text, boolean incoming) {
        if (text == null) return;
        messageSnapshot.add(new MsgView(System.currentTimeMillis(), incoming, text));
    }
    
    public boolean markCallLogShownOnce(String callId){
        return callId != null && shownCallLogs.add(callId);
    }
    
    private void handleServerFrame(Frame f) {
        new MessageHandler(this).handleServerFrame(f);
    }

    public HBox findRowByUserData(String id) {
        if (id == null) return null;
        for (Node n : messageContainer.getChildren()) {
            if (n instanceof HBox h) {
                Object ud = h.getUserData();
                if (ud != null && id.equals(String.valueOf(ud))) return h;
            }
        }
        return null;
    }

    public boolean removeMessageById(String id) {
        HBox row = findRowByUserData(id);
        return row != null && messageContainer.getChildren().remove(row); 
    }

    public void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void onSendMessage() {
        if (messageField == null) return;
        String text = messageField.getText().trim();
        if (text.isEmpty()) return;

        if (connection == null || !connection.isAlive()) {
            showErrorAlert("Mất kết nối đến server.");
            return;
        }

        if (currentPeer != null && currentPeer.startsWith("group:")) {
            try {
                int groupId = Integer.parseInt(currentPeer.substring("group:".length()));

                // Gửi frame tin nhắn nhóm lên server
                Frame frame = new Frame(
                        MessageType.GROUP_MSG,
                        currentUser.getUsername(),
                        String.valueOf(groupId), 
                        text
                    );
                connection.sendFrame(frame);

                // Render local cho mượt
                addTextMessage(text, false);
                messageField.clear();

            } catch (Exception e) {
                showErrorAlert("Không gửi được tin nhắn nhóm: " + e.getMessage());
            }
            return; 
        }

        new MessageHandler(this).onSendMessage();
        clearReplyPreview();
    }


    private ScrollPane findMessageScrollPane() {
        if (messageContainer == null) return null;
        Node p = messageContainer.getParent();
        while (p != null && !(p instanceof ScrollPane)) p = p.getParent();
        return (p instanceof ScrollPane sp) ? sp : null;
    }

    private void enableAutoScroll() {
        ScrollPane sp = findMessageScrollPane();
        if (sp == null) return;

        if (autoScrollListener != null) {
            messageContainer.heightProperty().removeListener(autoScrollListener);
        }

        autoScrollListener = (obs, oldV, newV) -> Platform.runLater(() -> {
            sp.layout();
            sp.setVvalue(1.0);
        });
        messageContainer.heightProperty().addListener(autoScrollListener);

        Platform.runLater(() -> {
            sp.layout();
            sp.setVvalue(1.0);
        });
    }
    
    public void updateTextBubbleById(String id, String newText) {
        HBox row = findRowByUserData(id);
        if (row == null) return;
        Node bubble = extractBubble(row);
        if (!(bubble instanceof VBox vb)) return;
        // Chỉ áp dụng cho bubble text
        String bubbleId = (vb).getId();
        if (!"incoming-text".equals(bubbleId) && !"outgoing-text".equals(bubbleId)) return;

        for (Node n : vb.getChildren()) {
            if (n instanceof Label lbl) {
                lbl.setText(newText);
                break;
            }
        }
    }

    private Node extractBubble(HBox row) {
        if (row.getChildren().isEmpty()) return null;
        Node first = row.getChildren().get(0);
        Node last  = row.getChildren().get(row.getChildren().size() - 1);
        if (first instanceof VBox) return first;
        if (last instanceof VBox)  return last;
        return first; 
    }

    
    public void enqueuePendingOutgoing(HBox row) {
        if (row != null) pendingOutgoingTexts.addLast(row);
    }

    public HBox findRowByFid(String fid) {
        if (fid == null || fid.isBlank()) return null;
        var mc = getMessageContainer();
        if (mc == null) return null;
        for (var n : mc.getChildren()) {
            if (n instanceof HBox row) {
                Object p = row.getProperties().get("fid");
                if (p != null && fid.equals(String.valueOf(p))) return row;
            }
        }
        return null;
    }
    public void tagNextPendingOutgoing(String dbIdStr) {
        if (dbIdStr == null || dbIdStr.isBlank()) return;
        try { Long.parseLong(dbIdStr); } catch (Exception e) { return; }
        var mc = getMessageContainer();
        if (mc != null && !mc.getChildren().isEmpty()) {
            for (int i = mc.getChildren().size() - 1; i >= 0; i--) {
                var n = mc.getChildren().get(i);
                if (n instanceof HBox row) {
                    Object ud = row.getUserData();
                    boolean numeric = false;
                    if (ud != null) { try { Long.parseLong(String.valueOf(ud)); numeric = true; } catch (Exception ignore) {} }
                    if (!numeric) { row.setUserData(dbIdStr); break; }
                }
            }
        }
        if (getOutgoingFileBubbles() != null && !getOutgoingFileBubbles().isEmpty()) {
            var it = getOutgoingFileBubbles().entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                HBox row = e.getValue();
                Object ud = row.getUserData();
                boolean numeric = false;
                if (ud != null) { try { Long.parseLong(String.valueOf(ud)); numeric = true; } catch (Exception ignore) {} }
                if (!numeric) {
                    row.getProperties().put("fid", String.valueOf(e.getKey()));
                    row.setUserData(dbIdStr);
                    it.remove();
                    break;
                }
            }
        }
    }




    public HBox addTextMessage(String text, boolean incoming) {
        HBox row = addTextMessage(text, incoming, null);
        snapshotText(text, incoming);
        return row;
    }
    public HBox addImageMessage(Image img, String caption, boolean incoming) {
        return addImageMessage(img, caption, incoming, null);
    }
    public HBox addFileMessage(String filename, String meta, boolean incoming) {
        return addFileMessage(filename, meta, incoming, null);
    }

    public HBox addVoiceMessage(String duration, boolean incoming, String fileId) {
        return new UIMessageHandler(this).addVoiceMessage(duration, incoming, fileId);
    }
    public HBox addVideoMessage(String filename, String meta, boolean incoming, String fileId) {
        return new UIMessageHandler(this).addVideoMessage(filename, meta, incoming, fileId);
    }

    public HBox addTextMessage(String text, boolean incoming, String messageId) {
        HBox row = new UIMessageHandler(this).addTextMessage(text, incoming, messageId);
        snapshotText(text, incoming);
        return row;
    }
    public HBox addImageMessage(Image img, String caption, boolean incoming, String messageId) {
        return new UIMessageHandler(this).addImageMessage(img, caption, incoming, messageId);
    }
    public HBox addFileMessage(String filename, String meta, boolean incoming, String messageId) {
        return new UIMessageHandler(this).addFileMessage(filename, meta, incoming, messageId);
    }

    public void showOutgoingFile(String filename, String mime, long bytes, String fileId, String duration) {
        new FileHandler(this).showOutgoingFile(filename, mime, bytes, fileId, duration);
    }

    public void updateVoiceBubbleFromUrl(HBox row, String fileUrl) {
        new MediaHandler(this).updateVoiceBubbleFromUrl(row, fileUrl);
    }

    public void updateVideoBubbleFromUrl(HBox row, String fileUrl) {
        new MediaHandler(this).updateVideoBubbleFromUrl(row, fileUrl);
    }

    public void updateImageBubbleFromUrl(HBox row, String fileUrl) {
        new MediaHandler(this).updateImageBubbleFromUrl(row, fileUrl);
    }

    private void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        new UIMessageHandler(this).applyStatusLabel(lbl, online, lastSeenIso);
    }
    
    public HBox addCallLog(String icon, String title, String subtitle, boolean incoming) {
        HBox row = new UIMessageHandler(this).addCallLogMessage(icon, title, subtitle, incoming);
        snapshotText(title + " " + (subtitle == null ? "" : subtitle), incoming);
        return row;
    }
    
    public void putVideoPlayer(String key, javafx.scene.media.MediaPlayer p) {
        if (key == null) return;
        var old = videoPlayers.put(key, p);
        if (old != null) {
            try { old.stop(); old.dispose(); } catch (Exception ignore) {}
        }
    }
    public javafx.scene.media.MediaPlayer getVideoPlayer(String key) {
        return key == null ? null : videoPlayers.get(key);
    }
    public void removeVideoPlayer(String key) {
        var old = videoPlayers.remove(key);
        if (old != null) { try { old.stop(); old.dispose(); } catch (Exception ignore) {} }
    }
    
    public static String formatCallDuration(long millis) {
        long totalSec = Math.max(0, millis / 1000);
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + " giờ " + m + " phút";
        } else if (m > 0) {
            return m + " phút " + String.format("%02d", s) + " giây";
        } else {
            return s + " giây";
        }
    }

    public String humanize(String iso, boolean withDot) {
        return new UtilHandler().humanize(iso, withDot);
    }

    private static String humanBytes(long v) {
        return UtilHandler.humanBytes(v);
    }

    private static String formatDuration(int sec) {
        return UtilHandler.formatDuration(sec);
    }

    private static UtilHandler.MediaKind classifyMedia(String mime, String name) {
        return UtilHandler.classifyMedia(mime, name);
    }

    private static String jsonGet(String json, String key) {
        return UtilHandler.jsonGet(json, key);
    }

    private static String guessExt(String mime, String fallbackName) {
        return UtilHandler.guessExt(mime, fallbackName);
    }

    public void setCallService(CallSignalingService svc) { this.callSvc = svc; }
    
    public void callCurrentPeer() { callHandler.callCurrentPeer(); }
    public void startCallTo(User peerUser) { callHandler.startCallTo(peerUser); }

    @Override public void onInvite (String fromUser, String callId){ callHandler.onInvite(fromUser, callId); }
    @Override public void onAccept (String fromUser, String callId){ callHandler.onAccept(fromUser, callId); }
    @Override public void onReject (String fromUser, String callId){ callHandler.onReject(fromUser, callId); }
    @Override public void onCancel (String fromUser, String callId){ callHandler.onCancel(fromUser, callId); }
    @Override public void onBusy   (String fromUser, String callId){ callHandler.onBusy(fromUser, callId); }
    @Override public void onEnd    (String fromUser, String callId){ callHandler.onEnd(fromUser, callId); }
    @Override public void onOffline(String toUser , String callId){ callHandler.onOffline(toUser, callId); }
    @Override public void onOffer  (String fromUser, String callId, String sdp){ callHandler.onOffer(fromUser, callId, sdp); }
    @Override public void onAnswer (String fromUser, String callId, String sdp){ callHandler.onAnswer(fromUser, callId, sdp); }
    @Override
    public void onIce(String from, String id, String c) {
        callHandler.onIce(from, id, c);
    }


    public void showVoiceRecordDialog(Window owner, AudioFormat format, File audioFile, Consumer<byte[]> onComplete) {
        voiceRecordHandler.showVoiceRecordDialog(owner, format, audioFile, onComplete);
    }
    public void onIncomingFrame(Frame f) {
        handleServerFrame(f);
    }

    public Label getCurrentChatName() { return currentChatName; }
    public Label getCurrentChatStatus() { return currentChatStatus; }
    public VBox getMessageContainer() { return messageContainer; }
    public TextField getMessageField() { return messageField; }
    public Button getLogoutBtn() { return logoutBtn; }
    public RightController getRightController() { return rightController; }
    public CallSignalingService getCallSvc() { return callSvc; }
    public Stage getCallStage() { return callStage; }
    public VideoCallController getCallCtrl() { return callCtrl; }
    public String getCurrentCallId() { return currentCallId; }
    public String getCurrentPeer() { return currentPeer; }
    public boolean isCaller() { return isCaller; }
    public User getCurrentUser() { return currentUser; }
    public User getSelectedUser() { return selectedUser; }
    public ClientConnection getConnection() { return connection; }
    public User getCurrentPeerUser() { return currentPeerUser; }
    public LanVideoSession getVideoSession() { return videoSession; }
    public LanAudioSession getAudioSession() { return audioSession; }
    public Map<String, List<Frame>> getPendingFileEvents() { return pendingFileEvents; }
    public Map<String, String> getFileIdToName() { return fileIdToName; }
    public Map<String, String> getFileIdToMime() { return fileIdToMime; }
    public Map<String, HBox> getOutgoingFileBubbles() { return outgoingFileBubbles; }
    public Map<String, BufferedOutputStream> getDlOut() { return dlOut; }

    public void setCallStage(Stage callStage) { this.callStage = callStage; }
    public void setCallCtrl(VideoCallController callCtrl) { this.callCtrl = callCtrl; }
    public void setCurrentCallId(String currentCallId) { this.currentCallId = currentCallId; }
    public void setCurrentPeer(String currentPeer) { this.currentPeer = currentPeer; }
    public void setCaller(boolean isCaller) { this.isCaller = isCaller; }
    public void setCurrentPeerUser(User currentPeerUser) { this.currentPeerUser = currentPeerUser; }
    public void setVideoSession(LanVideoSession videoSession) { this.videoSession = videoSession; }
    public void setAudioSession(LanAudioSession audioSession) { this.audioSession = audioSession; }
    public String getCurrentGroupChatName() {
        if (currentChatName != null) {
            return currentChatName.getText();
        }
        return null;
    }


}
