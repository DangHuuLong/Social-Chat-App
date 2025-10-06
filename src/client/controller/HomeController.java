package client.controller;

import client.ClientConnection;
import client.controller.right.SearchMessageHandler;
import client.signaling.CallSignalingService;
import common.Frame;
import common.User;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import server.dao.UserDAO;
import javax.sound.sampled.*;
import java.io.*;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import javafx.scene.control.Dialog;
import javafx.scene.image.ImageView;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;

public class HomeController {
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


	private HBox leftHeader;
	private Region leftHeaderSpacer;

    private final LeftController leftCtrl = new LeftController();
    private final MidController midCtrl = new MidController();
    private final RightController rightCtrl = new RightController();

    private User currentUser;
    private ClientConnection connection;
    private CallSignalingService callSvc;
    private String currentPeerUsername = null;

    @FXML
    private void initialize() {
        leftCtrl.bind(
            sidebar, chatList, searchField,
            titleLabel, toggleSidebarBtn, searchIconBtn,
            settingsBtn, leftHeader, leftHeaderSpacer
        );
        rightCtrl.bind(infoName, chatStatus);
        midCtrl.bind(currentChatName, currentChatStatus, messageContainer, messageField);
        midCtrl.setRightController(rightCtrl);

        leftCtrl.setOnOpenConversation(user -> {
            currentPeerUsername = (user != null ? user.getUsername() : null);
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            midCtrl.openConversation(user);
        });

        Platform.runLater(() -> {
            if (centerStack != null && centerStack.getScene() != null) {
                leftCtrl.setHostStage((Stage) centerStack.getScene().getWindow());
            }
        });

        toggleCenterEmpty(true);
        toggleRightEmpty(true);
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
    }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        midCtrl.setConnection(conn);
        leftCtrl.setConnection(conn);
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
}