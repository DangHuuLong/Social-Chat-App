package client.controller;

import client.ClientConnection;
import client.controller.right.SearchMessageHandler;
import client.signaling.CallSignalingService;
import common.Frame;
import common.MessageType;
import common.User;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javax.sound.sampled.*;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.ButtonType;

public class HomeController {
	@FXML private StackPane rootStack;
	@FXML private HBox mainRow;
    @FXML private VBox chatList;
    @FXML private TextField searchField;
    @FXML private Label currentChatName;
    @FXML private Label currentChatStatus;
    @FXML private VBox messageContainer;
    @FXML private TextField messageField;
    @FXML private Button settingsBtn;
    @FXML private Label infoName;
    @FXML private Label chatStatus;
    @FXML private StackPane centerStack;
    @FXML private VBox centerContent;
    @FXML private VBox centerEmpty;
    @FXML private StackPane rightStack;
    @FXML private VBox rightContent;
    @FXML private VBox rightEmpty;
    @FXML private Button callBtn;
    @FXML private Button videoBtn;
    @FXML private VBox sidebar;
    @FXML private Label titleLabel;
    @FXML private Button toggleSidebarBtn;
    @FXML private Button searchIconBtn;
    @FXML private Button toggleRightBtn;
    @FXML private Node rightSearchTrigger;
    @FXML private ImageView midHeaderAvatar;
    @FXML private ImageView rightHeaderAvatar;
    @FXML private VBox inviteBox;
    @FXML private VBox leaveBox;
    @FXML private Label leaveIcon;
    @FXML private Label leaveLabel;

    
    @FXML private StackPane overlayLayer;
    @FXML private StackPane overlayContent;
    @FXML private javafx.scene.shape.Rectangle overlayDim;        
    @FXML private Button btnOverlayClose;
    @FXML private Button btnOverlayDownload;
    @FXML private TabPane mediaTabs;
    @FXML private TilePane photoGrid;
    @FXML private TilePane videoGrid;
    @FXML private VBox     docList;
    @FXML private Accordion rightMenu;
    @FXML private TitledPane mediaPane;
    
    @FXML private HBox leftHeader;
    @FXML private Region leftHeaderSpacer;

    @FXML private HBox replyBar;
    @FXML private ImageView replyThumb;
    @FXML private Label replyFileIcon;
    @FXML private Label replyTitle;
    @FXML private Label replyContent;
    @FXML private Button replyCloseBtn;
    @FXML private Label inviteIcon;
    @FXML private Label inviteLabel;

    private final LeftController leftCtrl = new LeftController();
    private final MidController midCtrl = new MidController();
    private final RightController rightCtrl = new RightController();

    private User currentUser;
    private Image selfAvatar;
    private ClientConnection connection;
    private CallSignalingService callSvc;
    private String currentPeerUsername = null;
    private InviteDialogSession pendingInviteSession;

    @FXML
    private void initialize() {
        leftCtrl.bind(
            sidebar, chatList, searchField,
            titleLabel, toggleSidebarBtn, searchIconBtn,
            settingsBtn, leftHeader, leftHeaderSpacer
        );
        leftCtrl.setOnOpenGroupConversation(gvm -> {
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            currentPeerUsername = "group:" + gvm.groupId;
            // TODO: midCtrl.openGroup(gvm)  -> bạn cần viết hàm này.
            midCtrl.openGroupConversation(gvm);
            updateInviteButtonLabel();
            // TODO: load file/media lịch sử cho group nếu bạn support
            // connection.sendFileHistoryRequest(currentUser.getUsername(), "group:"+gvm.groupId, ...)
        });

        rightCtrl.bind(infoName, chatStatus, rightHeaderAvatar, mediaTabs, photoGrid, videoGrid, docList);
        rightCtrl.bindOverlay(overlayLayer, overlayContent, overlayDim, btnOverlayClose, btnOverlayDownload);
        midCtrl.bind(currentChatName, currentChatStatus, messageContainer, messageField, midHeaderAvatar);
        midCtrl.bindReplyBar(replyBar, replyThumb, replyFileIcon, replyTitle, replyContent, replyCloseBtn);
        midCtrl.setLeftController(leftCtrl);
        rightCtrl.setMidController(midCtrl);
        midCtrl.setRightController(rightCtrl);

        // Cập nhật sự kiện khi mở đoạn chat
        leftCtrl.setOnOpenConversation(user -> {
            currentPeerUsername = (user != null ? user.getUsername() : null);
            // REMOVE this line: currentPeerChatId = (user != null ? user.getChatId() : null);
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            midCtrl.openConversation(user);

            if (currentPeerUsername != null && connection != null && connection.isAlive()) {
                loadChatFiles(currentPeerUsername);
            }
            updateInviteButtonLabel();
        });

        Platform.runLater(() -> {
            if (centerStack != null && centerStack.getScene() != null) {
                leftCtrl.setHostStage((Stage) centerStack.getScene().getWindow());
            }

            if (rootStack != null && mainRow != null) {
                var w = rootStack.widthProperty();
                sidebar.prefWidthProperty().bind(w.multiply(0.20));
                centerStack.prefWidthProperty().bind(w.multiply(0.40));
                rightStack.prefWidthProperty().bind(w.multiply(0.40));
            }
        });
        
        VBox.setVgrow(mediaTabs, Priority.ALWAYS);

        if (rightStack != null) {
            if (rightMenu != null && mediaPane != null) {
                rightMenu.setExpandedPane(mediaPane);
            }
            if (mediaTabs != null && rightMenu != null && mediaPane != null) {
                mediaTabs.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                    rightMenu.setExpandedPane(mediaPane);
                });
            }
        }
        
        toggleCenterEmpty(true);
        toggleRightEmpty(true);
    }

    private void loadChatFiles(String peerUsername) {
        if (connection != null && connection.isAlive()) {
            try {
                // Xóa dữ liệu cũ trên các tab
                rightCtrl.clearMediaTabs();
                
                // Gửi yêu cầu tải lịch sử file
                connection.sendFileHistoryRequest(currentUser.getUsername(), peerUsername, 100, 0);
            } catch (IOException e) {
                System.err.println("[FILE] Failed to request file history: " + e.getMessage());
            }
        }
    }
    
    public User getCurrentUser() {
        return currentUser;
    }
    public String getCurrentPeerUsername() {
        return currentPeerUsername;
    }
    
    @FXML
    private void onRightSearchClick() {
        var owner = (centerStack != null) ? centerStack.getScene().getWindow() : null;
        new SearchMessageHandler(midCtrl).open(owner);
    }


    private void toggleCenterEmpty(boolean showEmpty) {
        centerEmpty.setVisible(showEmpty);
        centerEmpty.setManaged(showEmpty);
        centerContent.setVisible(!showEmpty);
        centerContent.setManaged(!showEmpty);
    }

    private void toggleRightEmpty(boolean showEmpty) {
        rightEmpty.setVisible(showEmpty);
        rightEmpty.setManaged(showEmpty);
        rightContent.setVisible(!showEmpty);
        rightContent.setManaged(!showEmpty);
    }

    public void setCallService(CallSignalingService svc) {
        this.callSvc = svc;
        this.callSvc.setListener(midCtrl);
        midCtrl.setCallService(this.callSvc);
    }

    public void onServerLine(String line) { /* không dùng nữa */ }
    public void onConnectionError(Exception e) { /* optional */ }

    public void setCurrentUser(User user) {
        this.currentUser = user;
        leftCtrl.setCurrentUser(user);
        midCtrl.setCurrentUser(user);
        leftCtrl.reloadAll();
//        requestGroupListFromServer();
        updateInviteButtonLabel();
        leftCtrl.startPresencePolling();
    }
    
    public void setSelfAvatar(Image img) {
        this.selfAvatar = img;
        if (midCtrl != null) {
        	midCtrl.setSelfAvatar(img);
        }
    }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;

        midCtrl.setConnection(conn);
        leftCtrl.setConnection(conn);

        connection.startListener(
            frame -> {
                onServerFrame(frame);
            },
            err -> {
                onConnectionError(err);
            }
        );
    }



    public void reloadAll() { leftCtrl.reloadAll(); }
    public void searchUsers() { leftCtrl.searchUsers(searchField.getText()); }
    
    @FXML
    private void onToggleRightPanel() {
        boolean currentlyShown = rightStack.isManaged(); 
        boolean nextShown = !currentlyShown;

        rightStack.setVisible(nextShown);
        rightStack.setManaged(nextShown);
    }


    @FXML
    private void onSend() {
        if (midCtrl != null) midCtrl.onSendMessage();
    }

    @FXML
    private void onAttach() {
        if (connection == null || !connection.isAlive()) {
            System.out.println("[ATTACH] Chưa kết nối server.");
            return;
        }
        if (currentUser == null) {
            System.out.println("[ATTACH] Chưa đăng nhập.");
            return;
        }
        final String toUser = currentPeerUsername;
        if (toUser == null || toUser.isBlank()) {
            System.out.println("[ATTACH] Chưa chọn đoạn chat / không xác định người nhận.");
            return;
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("Chọn file để gửi");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Tất cả", "*.*"),
            new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
            new FileChooser.ExtensionFilter("Âm thanh", "*.mp3", "*.m4a", "*.aac", "*.wav", "*.ogg"),
            new FileChooser.ExtensionFilter("Video", "*.mp4", "*.mov", "*.mkv", "*.webm")
        );

        Stage stage = (Stage) centerStack.getScene().getWindow();
        File file = fc.showOpenDialog(stage);
        if (file == null) {
            System.out.println("[ATTACH] Người dùng huỷ chọn file.");
            return;
        }

        final String fromUser = currentUser.getUsername();
        final String fileId = UUID.randomUUID().toString();
        System.out.println("[ATTACH] Gửi file: " + file.getAbsolutePath() + " -> @" + toUser + ", fileId=" + fileId);

        Thread t = new Thread(() -> {
            try {
                String mime = ClientConnection.guessMime(file);
                Frame ackF = connection.sendFileWithAck(fromUser, toUser, file, mime, fileId, 15_000);
                System.out.println("[ATTACH] ACK(tid=" + ackF.transferId + "): " + ackF.body);
            } catch (TimeoutException te) {
                System.out.println("[ATTACH] TIMEOUT đợi ACK.");
            } catch (Exception e) {
                System.out.println("[ATTACH] Lỗi gửi file: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }, "send-file");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onVoice() {
        if (connection == null || !connection.isAlive()) {
            System.out.println("[VOICE] Chưa kết nối server.");
            return;
        }
        if (currentUser == null) {
            System.out.println("[VOICE] Chưa đăng nhập.");
            return;
        }
        if (currentPeerUsername == null || currentPeerUsername.isBlank()) {
            System.out.println("[VOICE] Chưa chọn đoạn chat / không xác định người nhận.");
            return;
        }

        File tempDir = new File("temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        String audioId = UUID.randomUUID().toString();
        File audioFile = new File(tempDir, "voice-" + audioId + ".wav");

        AudioFormat format = new AudioFormat(16000, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        midCtrl.showVoiceRecordDialog(centerStack.getScene().getWindow(), format, audioFile, (recordedBytes) -> {
            if (recordedBytes == null) {
                if (audioFile.exists()) {
                    audioFile.delete();
                    System.out.println("[VOICE] Đã xóa file tạm do hủy: " + audioFile.getAbsolutePath());
                }
                return;
            }

            try (AudioInputStream audioInputStream = new AudioInputStream(
                    new ByteArrayInputStream(recordedBytes), format, recordedBytes.length / format.getFrameSize())) {
                AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, audioFile);
            } catch (Exception e) {
                System.out.println("[VOICE] Lỗi lưu file âm thanh: " + e.getMessage());
                e.printStackTrace(System.out);
                if (audioFile.exists()) {
                    audioFile.delete();
                    System.out.println("[VOICE] Đã xóa file tạm do lỗi: " + audioFile.getAbsolutePath());
                }
                Platform.runLater(() -> midCtrl.showErrorAlert("Lỗi lưu file âm thanh: " + e.getMessage()));
                return;
            }

            String fromUser = currentUser.getUsername();
            String toUser = currentPeerUsername;
            String mime = "audio/wav";

            System.out.println("[VOICE] Gửi file âm thanh: " + audioFile.getAbsolutePath() + " -> @" + toUser + ", fileId=" + audioId);

            Thread sendThread = new Thread(() -> {
                try {
                    Frame ack = connection.sendFileWithAck(fromUser, toUser, audioFile, mime, audioId, 15_000);
                    System.out.println("[VOICE] ACK(tid=" + ack.transferId + "): " + ack.body);

                    Platform.runLater(() -> {
                        try {
                            Thread.sleep(1000);
                            if (audioFile.exists()) {
                                audioFile.delete();
                                System.out.println("[VOICE] Đã xóa file tạm: " + audioFile.getAbsolutePath());
                            }
                        } catch (InterruptedException ie) {
                            System.out.println("[VOICE] Lỗi khi trì hoãn xóa file: " + ie.getMessage());
                        }
                    });
                } catch (TimeoutException te) {
                    System.out.println("[VOICE] TIMEOUT đợi ACK.");
                    if (audioFile.exists()) {
                        audioFile.delete();
                        System.out.println("[VOICE] Đã xóa file tạm do timeout: " + audioFile.getAbsolutePath());
                    }
                } catch (Exception e) {
                    System.out.println("[VOICE] Lỗi gửi file âm thanh: " + e.getMessage());
                    e.printStackTrace(System.out);
                    if (audioFile.exists()) {
                        audioFile.delete();
                        System.out.println("[VOICE] Đã xóa file tạm do lỗi: " + audioFile.getAbsolutePath());
                    }
                }
            }, "send-voice");
            sendThread.setDaemon(true);
            sendThread.start();
        });
    }

    @FXML
    private void onCall() {
        if (midCtrl != null) {
            midCtrl.callCurrentPeer();
        }
    }


    private void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String humanBytes(long v) {
        if (v <= 0) return "0 B";
        final String[] u = {"B","KB","MB","GB","TB"};
        int i = 0;
        double d = v;
        while (d >= 1024 && i < u.length - 1) { d /= 1024.0; i++; }
        return (d >= 10 ? String.format("%.0f %s", d, u[i]) : String.format("%.1f %s", d, u[i]));
    }
    
    public void setPendingInviteSession(InviteDialogSession s) {
        this.pendingInviteSession = s;
    }
    public void onGroupCreatedFromServer(int groupId) {
        final InviteDialogSession session = this.pendingInviteSession;

        if (session == null) {
            System.out.println("[WARN] Group created (id=" + groupId + ") but pendingInviteSession was null — delaying retry.");
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                    Platform.runLater(() -> {
                        if (this.pendingInviteSession != null) {
                            System.out.println("[INFO] Retry: now found pendingInviteSession, sending members.");
                            this.pendingInviteSession.setGroupId(groupId);  // ✅ FIX
                            this.pendingInviteSession.sendAddMembers();
                            this.pendingInviteSession = null;
                        } else {
                            System.out.println("[WARN] Still null after retry, skipping sendAddMembers.");
                        }
                    });
                } catch (InterruptedException ignored) {}
            }).start();
            return;
        }

        new Thread(() -> {
            try {
                Thread.sleep(500);
                Platform.runLater(() -> {
                    try {
                        session.setGroupId(groupId);   // ✅ FIX HERE
                        session.sendAddMembers();
                    } catch (Exception e) {
                        System.err.println("[ERROR] sendAddMembers failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                    this.pendingInviteSession = null;
                });
            } catch (InterruptedException ignored) {}
        }).start();
    }



    private Stage getCurrentStage() {
        return (Stage) inviteBox.getScene().getWindow();
    }
    @FXML
    private void onInviteClick() {
        String me = (currentUser != null) ? currentUser.getUsername() : null;
        if (me == null) {
            showErrorAlert("Bạn chưa đăng nhập, không thể tạo nhóm hoặc mời thành viên.");
            return;
        }
        if (connection == null || !connection.isAlive()) {
            showErrorAlert("Chưa kết nối server. Không thể thực hiện thao tác.");
            return;
        }

        // Detect current context
        String currentPeer = midCtrl.getCurrentPeer(); // e.g., "group:11" or "hic"
        if (currentPeer != null && currentPeer.startsWith("group:")) {
            // 👉 Inside group chat
            int groupId;
            try {
                groupId = Integer.parseInt(currentPeer.substring("group:".length()));
            } catch (Exception e) {
                showErrorAlert("Không xác định được ID nhóm.");
                return;
            }

            // Request current members from server (server will reply LIST_MEMBERS)
            onGroupInviteClick(groupId, midCtrl.getCurrentGroupChatName());
            return;
        }

        // 👉 Otherwise: 1-1 chat → open InviteDialogSession to CREATE group
        String peer = currentPeerUsername; // already stored when open 1-1 chat
        List<String> knownUsers = (leftCtrl != null) ? leftCtrl.getAllUsernames() : List.of(me);

        InviteDialogSession dialog = new InviteDialogSession(
                this,
                getCurrentStage(),
                me,
                peer,
                connection,
                knownUsers
        );

        // 🔧 Fix: remember this dialog so we can call sendAddMembers() after ACK
        setPendingInviteSession(dialog);

        dialog.show();

    }

    
    @FXML
    private void onGroupInviteClick(int groupId, String groupName) {
        String me = (currentUser != null) ? currentUser.getUsername() : null;
        if (me == null) {
            showErrorAlert("Bạn chưa đăng nhập, không thể mời thành viên.");
            return;
        }

        if (connection == null || !connection.isAlive()) {
            showErrorAlert("Chưa kết nối server. Không thể mời thành viên.");
            return;
        }

        List<String> knownUsers = (leftCtrl != null) ? leftCtrl.getAllUsernames() : List.of(me);

        try {
            // ✅ FIXED: send groupId inside BODY
            Frame req = new Frame(
                MessageType.LIST_MEMBERS,
                currentUser.getUsername(),
                "",  // recipient empty
                String.valueOf(groupId) // body = groupId
            );
            connection.sendFrame(req);

            System.out.println("[INVITE] Sent LIST_MEMBERS for groupId=" + groupId);
            return; // wait for server reply
        } catch (Exception e) {
            showErrorAlert("Không thể yêu cầu danh sách thành viên từ server.");
            e.printStackTrace();
        }
    }



    public void onServerFrame(Frame f) {

        switch (f.type) {

            // Server gửi danh sách group (mỗi frame = 1 group)
            // Gọi sau login trong sendGroupListToClient()
            case GROUP_LIST: {
                final String json = f.body; // {"group_id":3,"name":"Nhóm A","owner":"siu"}

                if (json != null && !json.isBlank()) {
                    String gidStr = jsonGet(json, "group_id");
                    String gname  = jsonGet(json, "name");
                    String gowner = jsonGet(json, "owner");

                    int gid = 0;
                    try { gid = Integer.parseInt(gidStr); } catch (Exception ignore) {}

                    final int gidFinal       = gid;
                    final String gnameFinal  = gname;
                    final String gownerFinal = gowner;

                    if (gidFinal > 0 && gnameFinal != null) {
                        Platform.runLater(() -> {
                            LeftController.GroupViewModel gvm =
                                new LeftController.GroupViewModel(
                                    gidFinal, gnameFinal, gownerFinal
                                );
                            leftCtrl.addSingleGroupToSidebar(gvm);
                        });
                    }
                }
                break;
            }
            case USER_LIST: {
                // f.body ví dụ: {"id":3,"username":"Long","online":1,"lastSeen":"2025-11-15 09:30:00",...}
                leftCtrl.handleUserListFrame(f);
                break;
            }

            // ACK từ server: dùng cho nhiều mục đích
            // - LOGIN: "OK LOGIN <username>"
            // - OFFLINE messages delivered
            // - OK_GROUP_CREATED {...}  <-- cái này quan trọng
            case ACK: {
                final String body = f.body == null ? "" : f.body;

                // 1. Kiểm tra xem có phải ACK tạo group mới không
                //    Server sendFrame(Frame.ack(respJson))
                //    trong respJson có: "status":"OK_GROUP_CREATED", "group_id":..., "name":..., "owner":...
                String status = jsonGet(body, "status");
                if ("OK_GROUP_CREATED".equals(status)) {

                    String gidStr = jsonGet(body, "group_id");
                    String gname  = jsonGet(body, "name");
                    String gowner = jsonGet(body, "owner");

                    int gid = 0;
                    try { gid = Integer.parseInt(gidStr); } catch (Exception ignore) {}

                    final int gidFinal       = gid;
                    final String gnameFinal  = gname;
                    final String gownerFinal = gowner;

                    Platform.runLater(() -> {
                        // B1: gửi ADD_MEMBER với toàn bộ selectedUsers từ InviteDialogSession
                        if (gidFinal > 0) {
                            onGroupCreatedFromServer(gidFinal);
                        }

                        // B2: add group mới ngay vào sidebar cho người tạo
                        if (gidFinal > 0 && gnameFinal != null) {
                            LeftController.GroupViewModel gvm =
                                new LeftController.GroupViewModel(
                                    gidFinal, gnameFinal, gownerFinal
                                );
                            leftCtrl.addSingleGroupToSidebar(gvm);
                        }
                    });

                    break; // quan trọng: mình xử lý xong case OK_GROUP_CREATED rồi thì break
                }
                if (body.startsWith("🔵 ") || body.startsWith("🔴 ")) {
                    System.out.println("[PRESENCE] broadcast: " + body);
                    Platform.runLater(() -> {
                        // gọi lại USER_LIST_REQ để update online/offline + last_seen
                        leftCtrl.reloadAll();
                    });
                }

                // 2. Các ACK khác (login, delivered...) bạn giữ nguyên logic cũ nếu có
                //    ví dụ: if (body.startsWith("OK LOGIN")) { ... }
                //           if (body.startsWith("Delivered ")) { ... }
                break;
            }
            case LIST_MEMBERS: {
                String json = f.body; // {"group_id":11,"members":["hic","huudat","Long","siu"]}
                int gid = Integer.parseInt(jsonGet(json, "group_id"));

                // Parse mảng JSON rất đơn giản
                String arr = json.substring(json.indexOf('[')+1, json.indexOf(']'));
                List<String> existingMembers = Arrays.stream(arr.replace("\"", "").split(","))
                                                     .map(String::trim)
                                                     .filter(s -> !s.isEmpty())
                                                     .toList();

                String groupName = midCtrl.getCurrentGroupChatName();

                Platform.runLater(() -> {
                    openInviteDialog(gid, groupName, existingMembers);
                });
                break;
            }

            default:
                break;
        }

        // cuối cùng forward frame nào cũng đưa cho midCtrl xử lý chat, file, call, vv.
        if (midCtrl != null && f.type != MessageType.USER_LIST) {
            Platform.runLater(() -> midCtrl.onIncomingFrame(f));
        }
    }



    private void openInviteDialog(int groupId, String groupName, List<String> existingMembers) {
        String me = currentUser.getUsername();
        List<String> knownUsers = (leftCtrl != null) ? leftCtrl.getAllUsernames() : List.of(me);

        InviteDialogSession dialog = new InviteDialogSession(
                this,
                getCurrentStage(),
                me,
                null,
                connection,
                knownUsers
        );

        dialog.setInviteMode(groupId, groupName, existingMembers);
        dialog.show();
    }

    private static String jsonGet(String json, String key) {
        if (json == null) return null;
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) return null;
            return json.substring(j + 1, end);
        } else {
            int end = j;
            while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(j, end);
        }
    }
    private java.util.List<LeftController.GroupViewModel> parseGroupsFromJson(String json) {
        // vì bạn đang dùng parser thủ công jsonGet(),
        // ta viết parser tay đơn giản luôn.

        // giả định json = {"groups":[{"id":5,"name":"abc","owner":"siu"}, {...}]}

        java.util.List<LeftController.GroupViewModel> list = new java.util.ArrayList<>();

        int arrStart = json.indexOf("\"groups\"");
        if (arrStart < 0) return list;

        arrStart = json.indexOf('[', arrStart);
        int arrEnd = json.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return list;

        String arrBody = json.substring(arrStart + 1, arrEnd); // {...},{...}

        // tách thô theo "},{" vì JSON đơn giản
        String[] items = arrBody.split("\\},\\{");

        for (String raw : items) {
            String obj = raw.trim();
            if (!obj.startsWith("{")) obj = "{" + obj;
            if (!obj.endsWith("}")) obj = obj + "}";

            String gidStr = jsonGet(obj, "id");
            String gname  = jsonGet(obj, "name");
            String gowner = jsonGet(obj, "owner");

            int gid = 0;
            try { gid = Integer.parseInt(gidStr); } catch (Exception ignore) {}

            if (gid > 0 && gname != null) {
                list.add(new LeftController.GroupViewModel(gid, gname, gowner));
            }
        }

        return list;
    }

    private void requestGroupListFromServer() {
        if (connection == null || !connection.isAlive()) return;
        if (currentUser == null) return;

        // body yêu cầu
        String body = "{\"username\":\"" + currentUser.getUsername() + "\",\"op\":\"list\"}";

        Frame req = new Frame(
                MessageType.GROUP_LIST,        // dùng luôn GROUP_LIST
                currentUser.getUsername(),     // from
                "",                            // to (không cần)
                body
        );
        try {
            connection.sendFrame(req);
        } catch (IOException e) {
            System.err.println("[GROUP] cannot request group list: " + e.getMessage());
        }
    }
    private void updateInviteButtonLabel() {
        String currentPeer = midCtrl.getCurrentPeer();
        System.out.println("[DEBUG] updateInviteButtonLabel -> currentPeer = " + currentPeer);
        if (inviteLabel != null) {
            if (currentPeer != null && currentPeer.startsWith("group:")) {
                inviteLabel.setText("Mời");
                inviteIcon.setText("👥");
                Tooltip.install(inviteBox, new Tooltip("Mời thêm thành viên"));
             // ✅ Hiện nút Rời nhóm
                if (leaveBox != null) {
                    leaveBox.setVisible(true);
                    leaveBox.setManaged(true);
                }

            } else {
                inviteLabel.setText("Tạo nhóm");
                inviteIcon.setText("👥");
                Tooltip.install(inviteBox, new Tooltip("Tạo nhóm mới từ đoạn chat này"));
                if (leaveBox != null) {
                    leaveBox.setVisible(false);
                    leaveBox.setManaged(false);
                }
            }
        }
    }

    @FXML
    private void onLeaveGroupClick() {
        String currentPeer = midCtrl.getCurrentPeer();
        if (currentPeer == null || !currentPeer.startsWith("group:")) {
            showErrorAlert("Chỉ có thể rời nhóm trong đoạn chat nhóm.");
            return;
        }

        int groupId;
        try {
            groupId = Integer.parseInt(currentPeer.substring("group:".length()));
        } catch (Exception e) {
            showErrorAlert("Không xác định được ID nhóm.");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Rời nhóm");
        confirm.setHeaderText(null);
        confirm.setContentText("Bạn có chắc muốn rời nhóm này?");
        if (confirm.showAndWait().filter(btn -> btn == ButtonType.OK).isEmpty()) return;

        try {
            // Gửi yêu cầu lên server
            String json = String.format("{\"group_id\":%d,\"username\":\"%s\"}", groupId, currentUser.getUsername());
            Frame frame = new Frame(MessageType.REMOVE_MEMBER, currentUser.getUsername(), "", json);
            connection.sendFrame(frame);
            System.out.println("[GROUP] Sent leave group request: " + json);

            // 🔹 1. Cập nhật giao diện ngay lập tức
            Platform.runLater(() -> {
                // Xóa nhóm khỏi LeftController cache và sidebar
                leftCtrl.removeGroupFromSidebar(groupId);

                // Dọn giao diện trung tâm
                toggleCenterEmpty(true);
                toggleRightEmpty(true);

                // Cập nhật lại danh sách nhóm
                requestGroupListFromServer();
            });

        } catch (IOException e) {
            showErrorAlert("Không thể gửi yêu cầu rời nhóm: " + e.getMessage());
        }
    }



}