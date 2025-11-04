package client.controller;

import client.ClientConnection;
import common.Frame;
import common.MessageType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class InviteDialogSession {

    private final Stage stage;
    private final TextField groupNameField = new TextField();
    private final TextField searchField = new TextField();
    private final ListView<UserItem> allUsersList = new ListView<>();
    private final ListView<String> selectedList = new ListView<>();
    private final Button createBtn = new Button("Tạo nhóm");
    private final Button cancelBtn = new Button("Hủy");

    private final String currentUser;
    private final String initialPeer;
    private final ClientConnection connection;
    private final ObservableList<UserItem> allItems;
    private final ObservableList<String> selectedUsers;
    private final Set<String> alwaysIncluded = new HashSet<>();
    private final HomeController homeController;

    // mode
    private boolean inviteExistingGroup = false;
    private int existingGroupId = -1;
    private String existingGroupName = null;
    private final Set<String> existingMembers = new HashSet<>();

    public InviteDialogSession(
            HomeController homeController,
            Stage ownerStage,
            String currentUser,
            String initialPeer,
            ClientConnection connection,
            List<String> knownUsers
    ) {
        this.homeController = homeController;
        this.currentUser = currentUser;
        this.initialPeer = initialPeer;
        this.connection = connection;

        alwaysIncluded.add(currentUser);
        if (initialPeer != null && !initialPeer.equals(currentUser)) {
            alwaysIncluded.add(initialPeer);
        }

        this.allItems = FXCollections.observableArrayList();
        for (String u : knownUsers) {
            allItems.add(new UserItem(u));
        }

        this.selectedUsers = FXCollections.observableArrayList();

        this.stage = buildStage(ownerStage);
        initListViews();
        initSearch();
        initDefaults();
        initButtons();
    }

    private Stage buildStage(Stage owner) {
        Label titleLbl = new Label();
        titleLbl.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");

        Label nameLbl = new Label("Tên nhóm:");
        nameLbl.setMinWidth(70);
        HBox nameRow = new HBox(8, nameLbl, groupNameField);
        HBox.setHgrow(groupNameField, Priority.ALWAYS);
        groupNameField.setPromptText("VD: Team dự án A");

        Label searchLbl = new Label("Tìm kiếm:");
        searchLbl.setMinWidth(70);
        HBox searchRow = new HBox(8, searchLbl, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.setPromptText("Nhập tên để lọc...");

        Label leftTitle = new Label("Người có thể mời:");
        leftTitle.setStyle("-fx-font-weight:bold;");
        VBox leftBox = new VBox(5, leftTitle, allUsersList);
        VBox.setVgrow(allUsersList, Priority.ALWAYS);

        Label rightTitle = new Label("Đã chọn:");
        rightTitle.setStyle("-fx-font-weight:bold;");
        VBox rightBox = new VBox(5, rightTitle, selectedList);
        VBox.setVgrow(selectedList, Priority.ALWAYS);

        HBox listsRow = new HBox(10, leftBox, rightBox);
        HBox.setHgrow(leftBox, Priority.ALWAYS);
        HBox.setHgrow(rightBox, Priority.ALWAYS);
        VBox.setVgrow(listsRow, Priority.ALWAYS);

        HBox buttonsRow = new HBox(10, cancelBtn, createBtn);
        buttonsRow.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(10, titleLbl, nameRow, searchRow, listsRow, buttonsRow);
        root.setPadding(new Insets(15));

        Stage dialog = new Stage();
        dialog.setTitle("Tạo nhóm");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setScene(new Scene(root, 480, 400));
        return dialog;
    }

    private void initListViews() {
        allUsersList.setItems(allItems);
        selectedList.setItems(selectedUsers);

        allUsersList.setCellFactory(listView -> new ListCell<UserItem>() {
            private final CheckBox cb = new CheckBox();

            {
                cb.selectedProperty().addListener((obs, was, isNow) -> {
                    UserItem item = getItem();
                    if (item == null) return;

                    if (alwaysIncluded.contains(item.getUsername()) || existingMembers.contains(item.getUsername())) {
                        cb.setSelected(true);
                        item.setSelected(true);
                        addSelectedIfAbsent(item.getUsername());
                        return;
                    }

                    item.setSelected(isNow);
                    if (isNow) addSelectedIfAbsent(item.getUsername());
                    else removeSelected(item.getUsername());
                });
            }

            @Override
            protected void updateItem(UserItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }

                cb.setText(item.getUsername());

                boolean mustKeep = alwaysIncluded.contains(item.getUsername());
                boolean alreadyInGroup = existingMembers.contains(item.getUsername());

                if (mustKeep || alreadyInGroup) {
                    cb.setSelected(true);
                    cb.setDisable(true);
                    item.setSelected(true);
                    addSelectedIfAbsent(item.getUsername());
                } else {
                    cb.setDisable(false);
                    cb.setSelected(item.selectedProperty().get());
                }

                setGraphic(cb);
            }
        });
    }

    private void initSearch() {
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterList(newVal));
    }

    private void filterList(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            allUsersList.setItems(allItems);
            return;
        }
        String lower = keyword.toLowerCase();
        var filtered = allItems.filtered(item -> item.getUsername().toLowerCase().contains(lower));
        allUsersList.setItems(filtered);
    }

    private void initDefaults() {
        addSelectedIfAbsent(currentUser);
        if (initialPeer != null && !initialPeer.equals(currentUser)) {
            addSelectedIfAbsent(initialPeer);
        }
    }

    private void initButtons() {
        cancelBtn.setOnAction(e -> stage.close());
        createBtn.setDefaultButton(true);
        createBtn.setOnAction(e -> {
            if (inviteExistingGroup) {
                sendAddMembers(); // ✅ no argument, new version
            } else {
                onCreateGroup();
            }
        });
    }

    private void addSelectedIfAbsent(String u) {
        if (!selectedUsers.contains(u)) selectedUsers.add(u);
        for (UserItem item : allItems) {
            if (item.getUsername().equals(u)) item.setSelected(true);
        }
    }

    private void removeSelected(String u) {
        if (alwaysIncluded.contains(u) || existingMembers.contains(u)) return;
        selectedUsers.remove(u);
        for (UserItem item : allItems) {
            if (item.getUsername().equals(u)) item.setSelected(false);
        }
    }

    private void onCreateGroup() {
        String groupName = groupNameField.getText();
        if (groupName == null || groupName.isBlank()) {
            groupName = "Unnamed Group";
        }

        Frame createReq = new Frame(
                MessageType.CREATE_GROUP,
                currentUser,
                "",
                groupName
        );

        try {
            connection.sendFrame(createReq);
            homeController.setPendingInviteSession(this);
        } catch (IOException e) {
            System.err.println("[INVITE] Failed to send CREATE_GROUP: " + e.getMessage());
        }
    }

    /** ✅ Fix #5 — Send ADD_MEMBER only for new users **/
    public void sendAddMembers() {
        List<String> toAdd = selectedUsers.stream()
                .filter(u -> !existingMembers.contains(u))
                .collect(Collectors.toList());

        if (toAdd.isEmpty()) {
            showAlert("Không có thành viên mới để thêm.");
            return;
        }

        String json = String.format(
                "{\"group_id\":%d,\"members\":[%s]}",
                existingGroupId,
                toAdd.stream()
                        .map(u -> "\"" + u + "\"")
                        .collect(Collectors.joining(","))
        );

        Frame f = new Frame(MessageType.ADD_MEMBER, currentUser, "", json);
        try {
            connection.sendFrame(f);
            System.out.println("[INVITE] Sent ADD_MEMBER: " + json);
            stage.close();
        } catch (IOException e) {
            System.err.println("[INVITE] Failed to send ADD_MEMBER: " + e.getMessage());
        }
    }

    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    public void setInviteMode(int groupId, String groupName, List<String> members) {
        this.inviteExistingGroup = true;
        this.existingGroupId = groupId;
        this.existingGroupName = groupName;
        this.existingMembers.clear();
        if (members != null) this.existingMembers.addAll(members);

        stage.setTitle("Mời thêm thành viên vào nhóm");
        groupNameField.setText(groupName);
        groupNameField.setDisable(true);
        createBtn.setText("Mời thêm");

        for (UserItem item : allItems) {
            String username = item.getUsername();
            if (existingMembers.contains(username)) {
                item.setSelected(true);
            }
        }

        selectedUsers.clear();
        for (UserItem item : allItems) {
            if (item.selectedProperty().get()) {
                addSelectedIfAbsent(item.getUsername());
            }
        }

        allUsersList.refresh();
        selectedList.refresh();
    }

    public void show() {
        // already set in buildStage(), so just call non-blocking show
        stage.show();
    }
    public void setGroupId(int id) {
        this.existingGroupId = id;
    }


    public static class UserItem {
        private final String username;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        public UserItem(String username) { this.username = username; }
        public String getUsername() { return username; }
        public BooleanProperty selectedProperty() { return selected; }
        public void setSelected(boolean v) { selected.set(v); }
    }
}
