package client.controller;

import client.ClientConnection;
import common.User;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;
import server.dao.UserDAO;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public class LeftController {
    private HBox leftHeader;
    private Region leftHeaderSpacer;
    private VBox sidebar;
    private VBox chatList;
    private TextField searchField;
    private Label titleLabel;
    private Button toggleSidebarBtn;
    private Button searchIconBtn;
    private Button settingsBtn;
    private ClientConnection connection;
    private Stage hostStage;

    private final Map<Integer, Label> lastLabels = new HashMap<>();
    private final Map<Integer, User> idToUser = new HashMap<>();
    private Timeline poller;

    private final BooleanProperty collapsed = new SimpleBooleanProperty(false);
    private final BooleanProperty dark = new SimpleBooleanProperty(false);

    private User currentUser;
    private Consumer<User> onOpenConversation;

    // Cache avatar để tránh query lặp
    private final Map<Integer, Image> avatarCache = new HashMap<>();
    private Image defaultAvatar; // lazy-load

    public void bind(
            VBox sidebar,
            VBox chatList,
            TextField searchField,
            Label titleLabel,
            Button toggleSidebarBtn,
            Button searchIconBtn,
            Button settingsBtn,
            HBox leftHeader,
            Region leftHeaderSpacer
    ) {
        this.sidebar = sidebar;
        this.chatList = chatList;
        this.searchField = searchField;
        this.titleLabel = titleLabel;
        this.toggleSidebarBtn = toggleSidebarBtn;
        this.searchIconBtn = searchIconBtn;
        this.settingsBtn = settingsBtn;
        this.leftHeader = leftHeader;
        this.leftHeaderSpacer = leftHeaderSpacer;

        if (this.searchField != null) {
            this.searchField.textProperty().addListener((obs, o, n) -> searchUsers(n));
        }

        if (this.toggleSidebarBtn != null) {
            this.toggleSidebarBtn.setOnAction(e -> collapsed.set(!collapsed.get()));
        }
        if (this.searchIconBtn != null) {
            this.searchIconBtn.setOnAction(e -> {
                collapsed.set(false);
                Platform.runLater(() -> { if (searchField != null) searchField.requestFocus(); });
            });
        }

        if (this.settingsBtn != null) {
            final MenuItem toggleThemeItem = new MenuItem("Đổi giao diện: 🌞 → 🌑");
            final MenuItem logoutItem = new MenuItem("🚪 Đăng xuất");

            dark.addListener((ob, oldV, newV) -> {
                toggleThemeItem.setText(newV ? "Đổi giao diện: 🌑 → 🌞" : "Đổi giao diện: 🌞 → 🌑");
                applyDarkClass(newV);
            });

            toggleThemeItem.setOnAction(e -> dark.set(!dark.get()));
            logoutItem.setOnAction(e -> performLogout());

            final ContextMenu settingsMenu = new ContextMenu(toggleThemeItem, logoutItem);
            settingsMenu.getStyleClass().add("settings-menu");
            logoutItem.getStyleClass().add("logout-item");

            this.settingsBtn.setOnAction(e -> {
                if (settingsMenu.isShowing()) {
                    settingsMenu.hide();
                } else {
                    settingsMenu.show(settingsBtn, Side.TOP, 0, -6);
                }
            });
        }

        collapsed.addListener((o,ov,nv) -> applyCollapsedUI(nv));
        applyCollapsedUI(collapsed.get());
        applyDarkClass(false);
    }

    public void setCurrentUser(User user) { this.currentUser = user; }
    public void setOnOpenConversation(Consumer<User> cb) { this.onOpenConversation = cb; }
    public void setConnection(ClientConnection conn) { this.connection = conn; }
    public void setHostStage(Stage stage) { this.hostStage = stage; }

    private void renderUsers(List<User> users) {
        chatList.getChildren().clear();
        lastLabels.clear();
        idToUser.clear();

        for (User u : users) {
            idToUser.put(u.getId(), u);
            chatList.getChildren().add(createChatItem(u));
        }
    }

    public void reloadAll() {
        if (currentUser == null || chatList == null) return;
        try {
            List<User> others = UserDAO.listOthers(currentUser.getId());
            renderUsers(others);
            if (poller == null) startPollingPresence();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void searchUsers(String keyword) {
        if (currentUser == null) return;
        String k = (keyword == null) ? "" : keyword.trim();
        if (k.isEmpty()) {
            reloadAll();
        } else {
            try {
                List<User> res = UserDAO.searchUsers(k, currentUser.getId());
                renderUsers(res);
                if (poller == null) startPollingPresence();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private HBox createChatItem(User u) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-item");
        row.setPadding(new Insets(8));
        row.setUserData(u.getId());

        Image img = loadAvatarImage(u.getId());
        StackPane avatarPane = buildCircularAvatar(img, 40);

        VBox textBox = new VBox(2);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label name = new Label(u.getUsername());
        name.getStyleClass().add("chat-name");

        Label last = new Label("Offline");
        last.getStyleClass().addAll("chat-last", "chat-status-offline");
        lastLabels.put(u.getId(), last);

        textBox.getChildren().addAll(name, last);

        textBox.visibleProperty().bind(collapsed.not());
        textBox.managedProperty().bind(collapsed.not());

        row.getChildren().addAll(avatarPane, textBox);

        row.setOnMouseClicked(ev -> {
            Integer uid = (Integer) row.getUserData();
            if (uid != null && onOpenConversation != null) {
                User target = idToUser.get(uid);
                if (target != null) onOpenConversation.accept(target);
            }
        });
        return row;
    }

    private Image loadAvatarImage(int userId) {
        try {
            Image cached = avatarCache.get(userId);
            if (cached != null) return cached;

            byte[] bytes = UserDAO.getAvatarById(userId); 
            Image img;
            if (bytes != null && bytes.length > 0) {
                img = new Image(new ByteArrayInputStream(bytes));
            } else {
                if (defaultAvatar == null) {
                    defaultAvatar = new Image(
                        Objects.requireNonNull(
                            getClass().getResource("/client/view/images/default user.png")
                        ).toExternalForm()
                    );
                }
                img = defaultAvatar;
            }
            avatarCache.put(userId, img);
            return img;
        } catch (Exception e) {
            if (defaultAvatar == null) {
                defaultAvatar = new Image(
                    Objects.requireNonNull(
                        getClass().getResource("/client/view/images/default user.png")
                    ).toExternalForm()
                );
            }
            return defaultAvatar;
        }
    }

    public void startPollingPresence() {
        stopPolling();
        poller = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
            refreshPresenceOnce();
            refreshUsersDiff();
        }));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        refreshPresenceOnce();
        refreshUsersDiff();
    }

    public void stopPolling() { if (poller != null) { poller.stop(); poller = null; } }

    private void refreshPresenceOnce() {
        try {
            Map<Integer, UserDAO.Presence> map = UserDAO.getPresenceOfAll();
            Platform.runLater(() -> {
                for (var entry : lastLabels.entrySet()) {
                    int userId = entry.getKey();
                    Label lbl = entry.getValue();
                    UserDAO.Presence p = map.get(userId);
                    if (p == null) continue;

                    lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
                    if (p.online) {
                        lbl.setText("Online");
                        lbl.getStyleClass().add("chat-status-online");
                    } else {
                        lbl.setText("Offline • " + humanize(p.lastSeenIso, false));
                        lbl.getStyleClass().add("chat-status-offline");
                    }
                }
            });
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
    
    private void refreshUsersDiff() {
        if (currentUser == null || chatList == null) return;
        try {
            List<User> latest = UserDAO.listOthers(currentUser.getId());

            Set<Integer> latestIds = new HashSet<>();
            for (User u : latest) {
                latestIds.add(u.getId());
                if (!idToUser.containsKey(u.getId())) {
                    idToUser.put(u.getId(), u);
                    Platform.runLater(() -> chatList.getChildren().add(createChatItem(u)));
                } else {
                    idToUser.put(u.getId(), u);
                }
            }

            List<Node> toRemove = new ArrayList<>();
            for (Node n : chatList.getChildren()) {
                Object ud = n.getUserData();
                if (ud instanceof Integer id && !latestIds.contains(id)) {
                    toRemove.add(n);
                    lastLabels.remove(id);
                    idToUser.remove(id);
                }
            }
            if (!toRemove.isEmpty()) {
                Platform.runLater(() -> chatList.getChildren().removeAll(toRemove));
            }

        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private void applyCollapsedUI(boolean isCollapsed) {
        if (sidebar != null) {
            if (isCollapsed) {
                if (!sidebar.getStyleClass().contains("collapsed")) sidebar.getStyleClass().add("collapsed");
                sidebar.setAlignment(Pos.TOP_CENTER);
            } else {
                sidebar.getStyleClass().remove("collapsed");
                sidebar.setAlignment(Pos.TOP_LEFT);
            }
        }

        if (leftHeader != null) leftHeader.setAlignment(isCollapsed ? Pos.CENTER : Pos.CENTER_LEFT);
        if (leftHeaderSpacer != null) {
            leftHeaderSpacer.setVisible(!isCollapsed);
            leftHeaderSpacer.setManaged(!isCollapsed);
            HBox.setHgrow(leftHeaderSpacer, isCollapsed ? Priority.NEVER : Priority.ALWAYS);
        }

        if (titleLabel != null) {
            titleLabel.setVisible(!isCollapsed);
            titleLabel.setManaged(!isCollapsed);
        }

        if (toggleSidebarBtn != null) {
            toggleSidebarBtn.setText(isCollapsed ? "➡️" : "⬅️");
            toggleSidebarBtn.setTooltip(new Tooltip(isCollapsed ? "Mở rộng" : "Thu gọn"));
        }

        if (searchField != null) { searchField.setVisible(!isCollapsed); searchField.setManaged(!isCollapsed); }
        if (searchIconBtn != null) { searchIconBtn.setVisible(isCollapsed); searchIconBtn.setManaged(isCollapsed); }

        if (settingsBtn != null) {
            settingsBtn.setText(isCollapsed ? "🔧" : "🔧 Cài đặt");
            if (isCollapsed) {
                if (!settingsBtn.getStyleClass().contains("settings-compact"))
                    settingsBtn.getStyleClass().add("settings-compact");
            } else {
                settingsBtn.getStyleClass().remove("settings-compact");
            }
            settingsBtn.setVisible(true);
            settingsBtn.setManaged(true);
        }

        if (chatList != null) chatList.requestLayout();
    }

    private void applyDarkClass(boolean enable) {
        if (sidebar == null) return;
        Node root = sidebar.getScene() != null ? sidebar.getScene().getRoot() : null;
        if (root != null) {
            var sc = root.getStyleClass();
            if (enable) {
                if (!sc.contains("dark")) sc.add("dark");
            } else {
                sc.remove("dark");
            }
        }
    }

    private void performLogout() {
        try { if (currentUser != null) UserDAO.setOnline(currentUser.getId(), false); } catch (SQLException ignored) {}

        stopPolling();

        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }

        try {
            if (hostStage == null && sidebar != null && sidebar.getScene() != null) {
                hostStage = (Stage) sidebar.getScene().getWindow();
            }
            if (hostStage != null) {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(root);
                hostStage.setScene(scene);
                hostStage.centerOnScreen();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private StackPane buildCircularAvatar(Image img, double size) {
        ImageView iv = new ImageView(img);
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setPreserveRatio(true);

        StackPane wrap = new StackPane(iv);
        wrap.setMinSize(size, size);
        wrap.setPrefSize(size, size);
        wrap.setMaxSize(size, size);

        double r = size / 2.0;
        Circle clip = new Circle(r, r, r);
        wrap.setClip(clip);

        Circle ring = new Circle(r, r, r - 0.6);
        ring.getStyleClass().add("avatar-ring");
        ring.setMouseTransparent(true);
        wrap.getChildren().add(ring);

        return wrap;
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
