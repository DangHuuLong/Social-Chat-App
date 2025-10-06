package client.controller;

import client.ClientConnection;
import client.signaling.CallSignalingService;
import common.User;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import server.dao.UserDAO;

import java.io.File;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.Optional;

public class MainController {

    @FXML private Button loginBtn;
    @FXML private Button registerBtn;

    private final UserDAO userDAO = new UserDAO();

    @FXML private void onLogin()    { showAuthDialog(AuthMode.LOGIN); }
    @FXML private void onRegister() { showAuthDialog(AuthMode.REGISTER); }

    private enum AuthMode { LOGIN, REGISTER }

    private void showAuthDialog(AuthMode mode) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(mode == AuthMode.LOGIN ? "Đăng nhập" : "Đăng ký");
        dialog.setHeaderText(mode == AuthMode.LOGIN ? "Nhập tài khoản để đăng nhập" : "Tạo tài khoản mới");

        Stage owner = (Stage) loginBtn.getScene().getWindow();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);

        ButtonType okType = new ButtonType("Đồng ý", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField username = new TextField(); username.setPromptText("Tên đăng nhập");
        PasswordField password = new PasswordField(); password.setPromptText("Mật khẩu");
        PasswordField confirmField = (mode == AuthMode.REGISTER) ? new PasswordField() : null;
        if (confirmField != null) confirmField.setPromptText("Xác nhận mật khẩu");

        final byte[][] selectedAvatarBytes = new byte[1][];
        final String[] selectedAvatarMime  = new String[1];

        ImageView avatarView = null;
        Button btnChange = null;
        Button btnClear  = null;

        VBox rootBox = new VBox(12);
        rootBox.setPadding(new Insets(10));

        if (mode == AuthMode.REGISTER) {
            avatarView = new ImageView();
            avatarView.setFitWidth(84);
            avatarView.setFitHeight(84);
            avatarView.setPreserveRatio(true);
            avatarView.setSmooth(true);

            Image defaultImg;
            try {
                defaultImg = new Image(getClass().getResourceAsStream("/client/view/images/default user.png"));
            } catch (Exception ex) {
                defaultImg = new Image("https://via.placeholder.com/84");
            }
            avatarView.setImage(defaultImg);

            StackPane avatarWrapper = new StackPane(avatarView);
            avatarWrapper.setMinSize(84, 84);
            avatarWrapper.setPrefSize(84, 84);
            avatarWrapper.setMaxSize(84, 84);
            avatarWrapper.setClip(new Circle(42, 42, 42));

            btnChange = new Button("Đổi ảnh");
            btnClear  = new Button("Xóa ảnh");
            HBox avatarButtons = new HBox(8, btnChange, btnClear);
            avatarButtons.setAlignment(Pos.CENTER);

            VBox avatarBox = new VBox(8, avatarWrapper, avatarButtons);
            avatarBox.setAlignment(Pos.CENTER);

            rootBox.getChildren().add(avatarBox);

            ImageView finalAvatarView = avatarView;
            btnChange.setOnAction(ev -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("Chọn ảnh đại diện");
                fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp")
                );
                File f = fc.showOpenDialog(owner);
                if (f == null) return;
                try {
                    Image img = new Image(f.toURI().toString(), 256, 256, true, true);
                    finalAvatarView.setImage(img);
                    selectedAvatarBytes[0] = Files.readAllBytes(f.toPath());
                    selectedAvatarMime[0]  = guessMimeByName(f.getName());
                } catch (Exception e) {
                    new Alert(Alert.AlertType.ERROR, "Không thể đọc ảnh: " + e.getMessage()).showAndWait();
                }
            });

            Image finalDefaultImg = defaultImg;
            btnClear.setOnAction(ev -> {
                finalAvatarView.setImage(finalDefaultImg);
                selectedAvatarBytes[0] = null;
                selectedAvatarMime[0]  = null;
            });
        }

        GridPane gp = new GridPane();
        gp.setHgap(10); gp.setVgap(10);
        gp.addRow(0, new Label("Tên:"), username);
        gp.addRow(1, new Label("Mật khẩu:"), password);
        if (confirmField != null) gp.addRow(2, new Label("Xác nhận:"), confirmField);

        rootBox.getChildren().add(gp);
        dialog.getDialogPane().setContent(rootBox);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(okType);
        okBtn.setDisable(true);

        Runnable validate = () -> {
            boolean valid = !username.getText().isBlank() && !password.getText().isBlank();
            if (mode == AuthMode.REGISTER) {
                valid = valid && confirmField != null && password.getText().equals(confirmField.getText());
            }
            okBtn.setDisable(!valid);
        };
        username.textProperty().addListener((o,a,b)->validate.run());
        password.textProperty().addListener((o,a,b)->validate.run());
        if (confirmField != null) confirmField.textProperty().addListener((o,a,b)->validate.run());
        validate.run();

        dialog.setResultConverter(bt -> bt == okType);
        Optional<Boolean> result = dialog.showAndWait();

        if (result.orElse(false)) {
            String u = username.getText().trim();
            String p = password.getText();
            try {
                boolean ok;
                if (mode == AuthMode.REGISTER) {
                    ok = userDAO.register(u, p, selectedAvatarBytes[0], selectedAvatarMime[0]);
                    if (!ok) {
                        showAlert(Alert.AlertType.WARNING, "Tên đăng nhập đã tồn tại.");
                        return;
                    }
                    showAlert(Alert.AlertType.INFORMATION, "Đăng ký thành công, vui lòng đăng nhập.");
                } else {
                    ok = userDAO.login(u, p);
                    if (!ok) {
                        showAlert(Alert.AlertType.ERROR, "Sai tài khoản hoặc mật khẩu.");
                        return;
                    }
                    User loggedIn = UserDAO.findByUsername(username.getText());
                    UserDAO.setOnline(loggedIn.getId(), true);
                    goToHome(loggedIn);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                showAlert(Alert.AlertType.ERROR, "Lỗi CSDL: " + e.getMessage());
            }
        }
    }

    private static String guessMimeByName(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private void showAlert(Alert.AlertType type, String msg) {
        new Alert(type, msg).showAndWait();
    }

    private void goToHome(User loggedInUser) {
        try {
            ClientConnection conn = new ClientConnection();
            boolean ok = conn.connect("127.0.0.1", 5000);
            if (!ok) {
                new Alert(Alert.AlertType.ERROR, "Không kết nối được server.").showAndWait();
                return;
            }
            conn.register(loggedInUser.getUsername());
            CallSignalingService callSvc = new CallSignalingService(conn);
            Stage stage = (Stage) loginBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Home.fxml"));
            Parent root = loader.load();
            HomeController home = loader.getController();
            home.setCurrentUser(loggedInUser);
            home.setConnection(conn);
            home.setCallService(callSvc);
            home.reloadAll();
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
            stage.setScene(scene);
            stage.centerOnScreen();
            stage.setOnCloseRequest(ev -> {
                try { UserDAO.setOnline(loggedInUser.getId(), false); } catch (Exception ignore) {}
                try { conn.close(); } catch (Exception ignore) {}
            });
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Không thể mở giao diện Home:\n" + e.getMessage()).showAndWait();
        }
    }
}
