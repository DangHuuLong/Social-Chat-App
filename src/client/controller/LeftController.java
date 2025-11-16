package client.controller;

import client.ClientConnection;
import common.Frame;
import common.MessageType;
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

import java.io.ByteArrayInputStream;
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
    private final Map<Integer, GroupViewModel> groupMap = new HashMap<>();

    private final BooleanProperty collapsed = new SimpleBooleanProperty(false);
    private final BooleanProperty dark = new SimpleBooleanProperty(false);
    private Timeline presenceTimer;
    private User currentUser;
    private Consumer<User> onOpenConversation;

    // Cache avatar để tránh query lặp
    private final Map<Integer, Image> avatarCache = new HashMap<>();
    private Image defaultAvatar; // lazy-load
    private List<GroupViewModel> groupCache = new ArrayList<>();
    private final List<User> userCache = new ArrayList<>();   // ❗ cache tất cả user server gửi
    public User getUserByUsername(String username) {
        if (username == null || username.isBlank()) return null;

        // Tối ưu: Nếu LeftController duy trì Map<Username, User> sẽ nhanh hơn
        // Nhưng dựa trên cấu trúc hiện tại (Map<ID, User>), ta dùng stream/loop trên idToUser.values()
        
        // Vì list userCache có thể lớn, dùng stream/filter. 
        // idToUser theo ID nên không nên dùng để tìm theo username.
        for (User u : userCache) {
            if (username.equals(u.getUsername())) {
                return u;
            }
        }
        
        // Kiểm tra nốt người dùng hiện tại (nếu MidController không check)
        if (currentUser != null && username.equals(currentUser.getUsername())) {
            return currentUser;
        }

        return null;
    }
    public void setUsersForSidebar(List<User> users) {
        userCache.clear();
        if (users != null) userCache.addAll(users);
        renderAll(userCache, groupCache);
    }

    
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
    
    public void handleUserListFrame(Frame f) {
        if (f == null) return;
        String json = f.body;
        if (json == null || json.isBlank()) return;

        String idStr       = jsonGet(json, "id");
        String username    = jsonGet(json, "username");
        String onlineStr   = jsonGet(json, "online");
        String lastSeenIso = jsonGet(json, "lastSeen");
        String avatarB64   = jsonGet(json, "avatarBase64");

        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (Exception e) {
            return;
        }
        if (username == null || username.isBlank()) return;

        // ✅ decode avatar thành bytes + Image
        byte[] avatarBytes = null;
        if (avatarB64 != null && !avatarB64.isBlank()) {
            try {
                avatarBytes = Base64.getDecoder().decode(avatarB64);
                Image img = new Image(new ByteArrayInputStream(avatarBytes));
                avatarCache.put(id, img);
            } catch (IllegalArgumentException ignore) {
                // decode fail -> dùng default
            }
        }

        boolean online = "1".equals(onlineStr) || "true".equalsIgnoreCase(onlineStr);

        // cập nhật / tạo User trong map
        User u = idToUser.get(id);
        boolean isNew = (u == null);
        if (u == null) {
            u = new User();
            u.setId(id);
        }
        u.setUsername(username);
        u.setOnline(online);           // ✅ quan trọng
        u.setLastSeenIso(lastSeenIso); // ✅ quan trọng
        if (avatarBytes != null) {
            u.setAvatar(avatarBytes);  // ✅ để createChatItem dùng được
        }
        idToUser.put(id, u);

        final int uidFinal = id;
        final boolean onlineFinal = online;
        final String lastSeenFinal = lastSeenIso;
        final User userFinal = u;
        final boolean newUserFinal = isNew;

        Platform.runLater(() -> {
            // ✅ update cache cho search
            userCache.removeIf(uu -> uu.getId() == uidFinal);
            userCache.add(userFinal);

            // Nếu là user mới -> tạo row UI
            if (newUserFinal) {
                HBox row = createChatItem(userFinal);
                chatList.getChildren().add(row);
            }

            // Cập nhật label trạng thái
            Label lbl = lastLabels.get(uidFinal);
            if (lbl != null) {
                lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
                if (onlineFinal) {
                    lbl.setText("Online");
                    lbl.getStyleClass().add("chat-status-online");
                } else {
                    String text = "Offline";
                    if (lastSeenFinal != null && !lastSeenFinal.isBlank()) {
                        text += humanize(lastSeenFinal, true); // humanize() đã tự thêm " • "
                    }
                    lbl.setText(text);
                    lbl.getStyleClass().add("chat-status-offline");
                }
            }
        });
    }
    
    public void startPresencePolling() {
        if (connection == null || currentUser == null || !connection.isAlive()) {
            return;
        }
        if (presenceTimer != null) return;

        presenceTimer = new Timeline();  // ✅ dùng constructor không tham số
        presenceTimer.getKeyFrames().setAll(
            new KeyFrame(
                Duration.seconds(10),
                e -> {
                    System.out.println("[PRESENCE] polling reloadAll()");
                    reloadAll();
                }
            )
        );
        presenceTimer.setCycleCount(Timeline.INDEFINITE);
        presenceTimer.play();
    }

    public void stopPresencePolling() {
        if (presenceTimer != null) {
            presenceTimer.stop();
            presenceTimer = null;
        }
    }

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
        if (chatList == null) return;

        // Nếu chưa có connection hoặc chưa biết currentUser → render cache sẵn có (nếu có)
        if (connection == null || currentUser == null || !connection.isAlive()) {
            renderAll(userCache, groupCache);
            return;
        }

        try {
            // body tuỳ theo server, anh giả sử handler trên server đang đọc key "q"
            String body = "{\"q\":\"\"}"; // q="" = lấy tất cả user

            Frame req = new Frame(
                    MessageType.USER_LIST_REQ,          // ✅ yêu cầu danh sách user
                    currentUser.getUsername(),          // from
                    "",                                 // to (không cần)
                    body
            );

            System.out.println("[LEFT] reloadAll() -> send USER_LIST_REQ");
            connection.sendFrame(req);
        } catch (Exception e) {
            System.err.println("[LEFT] cannot send USER_LIST_REQ: " + e.getMessage());
            // fallback: nếu gửi lỗi thì vẫn cứ render cache
            renderAll(userCache, groupCache);
        }
    }


    public void searchUsers(String keyword) {
        String k = (keyword == null) ? "" : keyword.trim();
        if (k.isEmpty()) {
            reloadAll();
            return;
        }

        List<User> filtered = new ArrayList<>();
        for (User u : userCache) {
            String name = u.getUsername();
            if (name != null && name.toLowerCase().contains(k.toLowerCase())) {
                filtered.add(u);
            }
        }
        renderUsers(filtered);
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

        Label last = new Label();
        last.getStyleClass().add("chat-last");

        if (u.isOnline()) {
            last.setText("Online");
            last.getStyleClass().add("chat-status-online");
        } else {
            last.setText("Offline" + humanize(u.getLastSeenIso(), true));
            last.getStyleClass().add("chat-status-offline");
        }
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
    private Image loadGroupAvatar() {
        try {
            var url = getClass().getResource("/client/view/images/group.png");
            if (url != null) {
                return new Image(url.toExternalForm());
            }
        } catch (Exception ignore) {}
        if (defaultAvatar == null) {
            defaultAvatar = new Image(
                Objects.requireNonNull(
                    getClass().getResource("/client/view/images/default user.png")
                ).toExternalForm()
            );
        }
        return defaultAvatar;
    }

    private Image loadAvatarImage(int userId) {
        // cache
        Image cached = avatarCache.get(userId);
        if (cached != null) return cached;

        User u = idToUser.get(userId);
        Image img;
        if (u != null && u.getAvatar() != null && u.getAvatar().length > 0) {
            img = new Image(new ByteArrayInputStream(u.getAvatar()));
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
    	stopPresencePolling(); 
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
    public java.util.List<String> getAllUsernames() {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (User u : idToUser.values()) {
            if (u != null && u.getUsername() != null && !u.getUsername().isBlank()) {
                list.add(u.getUsername());
            }
        }
        if (currentUser != null && currentUser.getUsername() != null) {
            String me = currentUser.getUsername();
            if (!list.contains(me)) {
                list.add(me);
            }
        }
        return list;
    }
    public static class GroupViewModel {
        public final int groupId;
        public final String name;
        public final String owner;
        public GroupViewModel(int gid, String name, String owner) {
            this.groupId = gid;
            this.name = name;
            this.owner = owner;
        }
    }
    private HBox createGroupItem(GroupViewModel g) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-item");
        row.setPadding(new Insets(8));
        row.setUserData("group:" + g.groupId); // đánh dấu đây là group, không phải user

        // avatar nhóm
        Image img = loadGroupAvatar();
        StackPane avatarPane = buildCircularAvatar(img, 40);

        VBox textBox = new VBox(2);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label nameLbl = new Label(g.name);
        nameLbl.getStyleClass().add("chat-name");

        Label infoLbl = new Label("Nhóm • owner: " + g.owner);
        infoLbl.getStyleClass().addAll("chat-last", "chat-status-offline");

        textBox.getChildren().addAll(nameLbl, infoLbl);

        textBox.visibleProperty().bind(collapsed.not());
        textBox.managedProperty().bind(collapsed.not());

        row.getChildren().addAll(avatarPane, textBox);

        // click vào row -> mở hội thoại nhóm
        row.setOnMouseClicked(ev -> {
            openGroupConversation(g);
        });

        return row;
    }

    private void openGroupConversation(GroupViewModel g) {
        if (onOpenGroupConversation != null) {
            onOpenGroupConversation.accept(g);
        }
    }
    private Consumer<GroupViewModel> onOpenGroupConversation;
    public void setOnOpenGroupConversation(Consumer<GroupViewModel> cb) {
        this.onOpenGroupConversation = cb;
    }
    public void setGroupsForSidebar(java.util.List<GroupViewModel> groups) {
        groupCache = new ArrayList<>(groups);
        // sau khi cập nhật cache, re-render
        renderAll(getAllCurrentUsersSnapshot(), groupCache);
    }

    // helper để lấy lại list user hiện tại (để không mất user list khi gọi lại)
    private List<User> getAllCurrentUsersSnapshot() {
        // ta có idToUser map rồi
        return new ArrayList<>(idToUser.values());
    }
    
    private void renderAll(List<User> users, List<GroupViewModel> groups) {
        chatList.getChildren().clear();
        lastLabels.clear();
        idToUser.clear();
        // groupMap clear
        groupMap.clear();

        // 1. render groups TRƯỚC hay SAU tuỳ bạn muốn.
        // ví dụ: render groups trước
        if (groups != null) {
            for (GroupViewModel g : groups) {
                groupMap.put(g.groupId, g);
                chatList.getChildren().add(createGroupItem(g));
            }
        }

        // 2. render từng user
        for (User u : users) {
            idToUser.put(u.getId(), u);
            chatList.getChildren().add(createChatItem(u));
        }
    }
    public void addSingleGroupToSidebar(GroupViewModel gvm) {
        if (groupCache == null) groupCache = new ArrayList<>();
        boolean exists = groupCache.stream().anyMatch(x -> x.groupId == gvm.groupId);
        if (!exists) {
            groupCache.add(gvm);
        } else {
            // nếu đã có trong cache thì cập nhật thông tin (phòng khi owner/name đổi)
            for (int i = 0; i < groupCache.size(); i++) {
                if (groupCache.get(i).groupId == gvm.groupId) {
                    groupCache.set(i, gvm);
                    break;
                }
            }
        }

        groupMap.put(gvm.groupId, gvm);

        // 🔹 XÓA mọi node cũ của group này khỏi UI
        java.util.List<Node> toRemove = new java.util.ArrayList<>();
        for (Node n : chatList.getChildren()) {
            Object ud = n.getUserData();
            if (ud instanceof String s && s.equals("group:" + gvm.groupId)) {
                toRemove.add(n);
            }
        }
        chatList.getChildren().removeAll(toRemove);

        // 🔹 Thêm node mới
        HBox row = createGroupItem(gvm);
        chatList.getChildren().add(0, row);
    }

    public void removeGroupFromSidebar(int groupId) {
        // 1. Xóa trong cache
        if (groupCache != null) {
            groupCache.removeIf(g -> g.groupId == groupId);
        }

        // 2. Xóa khỏi map
        groupMap.remove(groupId);

        // 3. Xóa khỏi giao diện
        Platform.runLater(() -> {
            List<Node> toRemove = new ArrayList<>();
            for (Node n : chatList.getChildren()) {
                Object ud = n.getUserData();
                if (ud instanceof String s && s.equals("group:" + groupId)) {
                    toRemove.add(n);
                }
            }
            chatList.getChildren().removeAll(toRemove);
        });
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
            while (end < json.length()
                    && "-0123456789truefals".indexOf(
                            Character.toLowerCase(json.charAt(end))
                       ) >= 0) {
                end++;
            }
            return json.substring(j, end);
        }
    }

}