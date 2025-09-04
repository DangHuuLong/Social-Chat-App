package client.controller;

import client.ClientConnection;
import client.model.User;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import server.dao.UserDAO;

import java.sql.SQLException;
import java.time.Instant;

public class MidController {
    // FXML nodes (bind từ HomeController)
    private Label currentChatName;
    private Label currentChatStatus;
    private VBox messageContainer;
    private TextField messageField;
    private Button logoutBtn;

    // Tham chiếu sang RightController để cập nhật info panel
    private RightController rightController;

    // State / net
    private User currentUser;
    private User selectedUser;
    private ClientConnection connection;

    // ===== Wiring từ HomeController =====
    public void bind(Label currentChatName, Label currentChatStatus,
                     VBox messageContainer, TextField messageField, Button logoutBtn) {
        this.currentChatName = currentChatName;
        this.currentChatStatus = currentChatStatus;
        this.messageContainer = messageContainer;
        this.messageField = messageField;
        this.logoutBtn = logoutBtn;

        // event: enter để gửi
        if (this.messageField != null) {
            this.messageField.setOnAction(e -> onSendMessage());
        }
        // event: logout
        if (this.logoutBtn != null) {
            this.logoutBtn.setOnAction(e -> onLogout());
        }
    }

    public void setRightController(RightController rc) { this.rightController = rc; }

    public void setCurrentUser(User user) { this.currentUser = user; }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        if (this.connection != null) {
            this.connection.startListener(
                msg -> Platform.runLater(() -> handleServerMessage(msg)),
                err -> System.err.println("[NET] Disconnected: " + err)
            );
        }
    }

    // ===== Open conversation =====
    public void openConversation(User u) {
        this.selectedUser = u;
        if (currentChatName != null) currentChatName.setText(u.getUsername());

        // cập nhật header + info panel theo presence
        try {
            UserDAO.Presence p = UserDAO.getPresence(u.getId());
            boolean online = p != null && p.online;
            String lastSeen = (p != null) ? p.lastSeenIso : null;

            applyStatusLabel(currentChatStatus, online, lastSeen);
            if (rightController != null) {
                rightController.showUser(u, online, lastSeen);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            applyStatusLabel(currentChatStatus, false, null);
            if (rightController != null) rightController.showUser(u, false, null);
        }

        // clear và xin lịch sử
        if (messageContainer != null) messageContainer.getChildren().clear();
        if (connection != null && connection.isAlive()) {
            connection.send("HISTORY " + u.getUsername() + " 50");
        }
    }

    // ===== Messaging =====
    public void onSendMessage() {
        if (messageField == null) return;
        String text = messageField.getText().trim();
        if (text.isEmpty() || selectedUser == null) return;

        if (connection != null && connection.isAlive()) {
            connection.sendDirectMessage(selectedUser.getUsername(), text);
        }
        addTextMessage(text, false);
        messageField.clear();
    }

    private void handleServerMessage(String msg) {
        if (msg == null || msg.isBlank()) return;
        msg = msg.trim();

        String openPeer = (selectedUser != null) ? selectedUser.getUsername() : null;

        if (msg.startsWith("[DM]")) {
            String payload = msg.substring(4).trim();
            int p = payload.indexOf(": ");
            if (p > 0) {
                String sender = payload.substring(0, p);
                String body   = payload.substring(p + 2);
                if (openPeer != null && openPeer.equals(sender)) {
                    addTextMessage(body, true);
                }
            }
            return;
        }

        if (msg.startsWith("[HIST IN]")) {
            String payload = msg.substring(9).trim();
            int p = payload.indexOf(": ");
            if (p > 0) {
                String sender = payload.substring(0, p);
                String body   = payload.substring(p + 2);
                if (openPeer != null && openPeer.equals(sender)) {
                    addTextMessage(body, true);
                }
            }
            return;
        }

        if (msg.startsWith("[HIST OUT]")) {
            String body = msg.substring(10).trim();
            addTextMessage(body, false);
        }
        // các message khác bỏ qua
    }

    // ===== UI bubbles =====
    public void addTextMessage(String text, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        VBox bubble = new VBox();
        bubble.setMaxWidth(420);
        bubble.setId(incoming ? "incoming-text" : "outgoing-text");

        Label lbl = new Label(text);
        lbl.setWrapText(true);
        bubble.getChildren().add(lbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(bubble, spacer);
        else          row.getChildren().addAll(spacer, bubble);

        messageContainer.getChildren().add(row);
    }

    public void addImageMessage(Image img, String caption, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        Label cap = new Label(caption);
        box.getChildren().addAll(iv, cap);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(box, spacer);
        else          row.getChildren().addAll(spacer, box);

        messageContainer.getChildren().add(row);
    }

    public void addFileMessage(String filename, String meta, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        VBox box = new VBox();
        box.setId(incoming ? "incoming-file" : "outgoing-file");
        box.setPadding(new javafx.geometry.Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename);
        nameLbl.getStyleClass().add("file-name");

        Label metaLbl = new Label(meta);
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.getChildren().addAll(nameLbl, metaLbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btn = new Button(incoming ? "Tải" : "Mở");

        content.getChildren().addAll(icon, info, spacer, btn);
        box.getChildren().add(content);

        if (incoming) row.getChildren().addAll(box, new Region());
        else          row.getChildren().addAll(new Region(), box);

        messageContainer.getChildren().add(row);
    }

    public void addVoiceMessage(String duration, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        HBox voiceBox = new HBox(10);
        voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice");
        voiceBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button playBtn = new Button(incoming ? "▶" : "⏸");
        playBtn.getStyleClass().add("audio-btn");

        Slider slider = new Slider();
        slider.setPrefWidth(200);
        if (!incoming) slider.setValue(35);

        Label dur = new Label(duration);

        voiceBox.getChildren().addAll(playBtn, slider, dur);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(voiceBox, spacer);
        else          row.getChildren().addAll(spacer, voiceBox);

        messageContainer.getChildren().add(row);
    }

    // ===== Logout =====
    private void onLogout() {
        // cập nhật offline DB
        try {
            if (currentUser != null) UserDAO.setOnline(currentUser.getId(), false);
        } catch (SQLException ignored) {}

        // đóng kết nối
        if (connection != null) {
            try { connection.send("QUIT"); } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
        currentUser = null;

        // quay về Main.fxml
        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
            Parent root = loader.load();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== Util =====
    private void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + humanize(lastSeenIso, true));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }

    private String humanize(String iso, boolean withDot) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant t = Instant.parse(iso);
            var d = java.time.Duration.between(t, Instant.now());
            long m = d.toMinutes();
            String p;
            if (m < 1) p = "just now";
            else if (m < 60) p = m + "m ago";
            else {
                long h = m / 60;
                p = (h < 24) ? (h + "h ago") : ((h / 24) + "d ago");
            }
            return withDot ? " • " + p : p;
        } catch (Exception e) {
            return withDot ? " • " + iso : iso;
        }
    }
}
