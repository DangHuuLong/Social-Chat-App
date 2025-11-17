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
import javafx.scene.layout.Priority; 
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.util.Objects;
import java.util.Optional;
import java.io.ByteArrayInputStream;
public class MainController {

    @FXML private Button loginBtn;
    @FXML private Button registerBtn;

    @FXML private void onLogin()    { showAuthDialog(AuthMode.LOGIN); }
    @FXML private void onRegister() { showAuthDialog(AuthMode.REGISTER); }

    private enum AuthMode { LOGIN, REGISTER }
    
    private static final String SERVER_HOST = "192.168.1.162"; // sau này đổi thành IP server
    private static final int    SERVER_PORT = 5000;

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
        username.setMaxWidth(Double.MAX_VALUE);
        password.setMaxWidth(Double.MAX_VALUE);
        if (confirmField != null) {
            confirmField.setMaxWidth(Double.MAX_VALUE);
        }
        if (confirmField != null) confirmField.setPromptText("Xác nhận mật khẩu");

        Label pwdErrorLabel = null;
        Label confirmErrorLabel = null;
        if (mode == AuthMode.REGISTER) {
            pwdErrorLabel = new Label();
            pwdErrorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 11;");

            confirmErrorLabel = new Label();
            confirmErrorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 11;");
        }
        final Label finalPwdErrorLabel = pwdErrorLabel;
        final Label finalConfirmErrorLabel = confirmErrorLabel;

        final byte[][] selectedAvatarBytes = new byte[1][];
        final String[] selectedAvatarMime  = new String[1];

        ImageView avatarView = null;
        Button btnChange = null;
        Button btnClear  = null;

        VBox rootBox = new VBox(12);
        rootBox.setPadding(new Insets(10));
        rootBox.setFillWidth(true);

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

        if (mode == AuthMode.REGISTER) {
            VBox pwdBox = new VBox(4, password, finalPwdErrorLabel);
            pwdBox.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(pwdBox, Priority.ALWAYS);         

            VBox confirmBox = new VBox(4, confirmField, finalConfirmErrorLabel);
            confirmBox.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(confirmBox, Priority.ALWAYS);

            gp.addRow(1, new Label("Mật khẩu:"), pwdBox);
            gp.addRow(2, new Label("Xác nhận:"), confirmBox);
        } else {
            gp.addRow(1, new Label("Mật khẩu:"), password);
            GridPane.setHgrow(password, Priority.ALWAYS);      
        }


        rootBox.getChildren().add(gp);
        dialog.getDialogPane().setContent(rootBox);
        rootBox.setAlignment(Pos.CENTER);
        dialog.getDialogPane().setMinWidth(420);
        dialog.getDialogPane().setPrefWidth(420);
        dialog.getDialogPane().setMaxWidth(420);
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(okType);
        okBtn.setDisable(true);

        Runnable validate = () -> {
            String u = username.getText().trim();
            String p = password.getText();

            boolean validUsername = !u.isBlank();
            boolean validPasswordNotEmpty = !p.isBlank();

            if (finalPwdErrorLabel != null) {
                finalPwdErrorLabel.setText("");
            }
            if (finalConfirmErrorLabel != null) {
                finalConfirmErrorLabel.setText("");
            }

            if (mode == AuthMode.REGISTER) {
                boolean strongPwd = isStrongPassword(p);

                if (!p.isEmpty() && !strongPwd) {
                    if (finalPwdErrorLabel != null) {
                        finalPwdErrorLabel.setText(
                            "Mật khẩu phải ≥ 8 ký tự, gồm chữ, số và ký tự đặc biệt."
                        );
                    }
                }

                boolean confirmOk = false;
                if (confirmField != null) {
                    String c = confirmField.getText();
                    confirmOk = !c.isEmpty() && p.equals(c);
                    if (!c.isEmpty() && !p.equals(c)) {
                        if (finalConfirmErrorLabel != null) {
                            finalConfirmErrorLabel.setText("Mật khẩu xác nhận không khớp.");
                        }
                    }
                }

                boolean valid = validUsername && strongPwd && confirmOk;
                okBtn.setDisable(!valid);
            } else {
                boolean valid = validUsername && validPasswordNotEmpty;
                okBtn.setDisable(!valid);
            }
        };

        username.textProperty().addListener((o, a, b) -> validate.run());
        password.textProperty().addListener((o, a, b) -> validate.run());
        if (confirmField != null) confirmField.textProperty().addListener((o, a, b) -> validate.run());
        validate.run();

        dialog.setResultConverter(bt -> bt == okType);
        Optional<Boolean> result = dialog.showAndWait();

        if (result.orElse(false)) {
            String u = username.getText().trim();
            String p = password.getText();

            try {
                ClientConnection conn = new ClientConnection();
                boolean connected = conn.connect(SERVER_HOST, SERVER_PORT);
                if (!connected) {
                    showAlert(Alert.AlertType.ERROR, "Không kết nối được server.");
                    return;
                }

                if (mode == AuthMode.REGISTER) {
                    boolean ok = conn.authRegister(u, p, selectedAvatarBytes[0], selectedAvatarMime[0]);
                    conn.close(); // đăng ký xong cho login lại
                    if (!ok) {
                        showAlert(Alert.AlertType.WARNING, "Tên đăng nhập đã tồn tại hoặc đăng ký thất bại.");
                        return;
                    }
                    showAlert(Alert.AlertType.INFORMATION, "Đăng ký thành công, vui lòng đăng nhập.");
                } else {
                    User loggedIn = conn.authLogin(u, p);
                    if (loggedIn == null) {
                        showAlert(Alert.AlertType.ERROR, "Sai tài khoản hoặc mật khẩu.");
                        conn.close();
                        return;
                    }

                    // Sau khi auth OK -> đăng ký presence (REGISTER) để server bind username + gửi offline msg
                    conn.register(loggedIn.getUsername());

                    // Mở màn hình Home, dùng chính connection này
                    goToHome(loggedIn, conn);
                }
            } catch (Exception e) {
                e.printStackTrace();
                showAlert(Alert.AlertType.ERROR, "Lỗi kết nối: " + e.getMessage());
            }
        }

    }

    private static boolean isStrongPassword(String p) {
        if (p == null || p.length() < 8) return false;

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (char c : p.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }
        return hasLetter && hasDigit && hasSpecial;
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

    private void goToHome(User loggedInUser, ClientConnection conn) {
        try {
            CallSignalingService callSvc = new CallSignalingService(conn);
            Stage stage = (Stage) loginBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Home.fxml"));
            Parent root = loader.load();
            HomeController home = loader.getController();
            home.setConnection(conn);
            home.setCurrentUser(loggedInUser);
            home.setCallService(callSvc);
            
            byte[] avatarBytes = loggedInUser.getAvatar();
            Image selfAvatar;
            if (avatarBytes != null && avatarBytes.length > 0) {
                selfAvatar = new Image(new ByteArrayInputStream(avatarBytes));
            } else {
                selfAvatar = new Image(
                    Objects.requireNonNull(
                        getClass().getResource("/client/view/images/default user.png")
                    ).toExternalForm()
                );
            }
            home.setSelfAvatar(selfAvatar);

            
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
            stage.setScene(scene);
            stage.centerOnScreen();
            stage.setOnCloseRequest(ev -> {
                try { conn.close(); } catch (Exception ignore) {}
            });
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Không thể mở giao diện Home:\n" + e.getMessage()).showAndWait();
        }
    }
}
