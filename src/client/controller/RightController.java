package client.controller;

import common.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.application.Platform;

import java.time.Instant;

public class RightController {
    private Label infoName;
    private Label chatStatus;
    private ImageView rightHeaderAvatar;
    
    private TabPane mediaTabs;
    private TilePane photoGrid;
    private TilePane videoGrid;
    private VBox     docList;

    
    private StackPane overlayLayer;
    private StackPane overlayContent;
    private javafx.scene.shape.Rectangle overlayDim;
    private Button btnOverlayClose;
    private Button btnOverlayDownload;

    public void bind(Label infoName, Label chatStatus, ImageView rightHeaderAvatar, TabPane mediaTabs, TilePane photoGrid, TilePane videoGrid, VBox docList) {
        this.infoName = infoName;
        this.chatStatus = chatStatus;
        this.rightHeaderAvatar = rightHeaderAvatar;
        this.mediaTabs = mediaTabs;
        this.photoGrid = photoGrid;
        this.videoGrid = videoGrid;
        this.docList = docList;
    }
    
    public void bindOverlay(StackPane overlayLayer, StackPane overlayContent,
            javafx.scene.shape.Rectangle overlayDim, Button btnClose, Button btnDownload) {
        this.overlayLayer       = overlayLayer;
        this.overlayContent     = overlayContent;
        this.overlayDim         = overlayDim;
        this.btnOverlayClose    = btnClose;
        this.btnOverlayDownload = btnDownload;

        if (overlayDim != null && overlayLayer != null) {
            overlayDim.widthProperty().bind(overlayLayer.widthProperty());
            overlayDim.heightProperty().bind(overlayLayer.heightProperty());
        }

        if (btnOverlayClose != null) btnOverlayClose.setOnAction(e -> hideOverlay());
        if (overlayDim != null) overlayDim.setOnMouseClicked(e -> hideOverlay());

        if (overlayLayer != null) overlayLayer.setPickOnBounds(true);
        if (overlayContent != null) {
            overlayContent.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            overlayContent.setAlignment(Pos.CENTER);
        }
    }

    // ====== ẢNH ======
    public void addPhotoThumb(Image image, String idHint) {
        StackPane cell = makeSquareThumb(92); // ô vuông 92x92
        ImageView iv = new ImageView(image);
        iv.setFitWidth(92);
        iv.setFitHeight(92);
        iv.setPreserveRatio(false); // "cover" – tràn đều để phủ kín ô vuông
        cell.getChildren().add(iv);

        // rounded
        Rectangle clip = new Rectangle(92, 92);
        clip.setArcWidth(18); clip.setArcHeight(18);
        cell.setClip(clip);

        // Click mở overlay (UI only)
        cell.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) showImageOverlay(image);
        });

        cell.setUserData(idHint);
        photoGrid.getChildren().add(cell);
    }

    // ====== VIDEO ======
    public void addVideoThumb(Image thumbnail, String idHint) {
        StackPane cell = makeSquareThumb(92);

        ImageView iv = new ImageView(thumbnail);
        iv.setFitWidth(92);
        iv.setFitHeight(92);
        iv.setPreserveRatio(false);
        cell.getChildren().add(iv);

        // Play overlay ▶️
        Label play = new Label("▶️");
        play.setStyle("-fx-font-size: 26px; -fx-opacity: 0.9;");
        StackPane.setAlignment(play, Pos.CENTER);
        cell.getChildren().add(play);

        // rounded
        Rectangle clip = new Rectangle(92, 92);
        clip.setArcWidth(18); clip.setArcHeight(18);
        cell.setClip(clip);

        cell.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) showVideoOverlay(thumbnail /* tạm dùng thumbnail làm nền */);
        });

        cell.setUserData(idHint);
        videoGrid.getChildren().add(cell);
    }

    // ====== TÀI LIỆU ======
    public void addDocumentItem(String filename, String meta, String idHint) {
        // UI giống bubble outgoing-file
        HBox row = makeDocRow(filename, meta);
        row.setUserData(idHint);
        docList.getChildren().add(row);
    }

    // ----------------------
    // Helper tạo ô vuông
    private StackPane makeSquareThumb(double size) {
        StackPane p = new StackPane();
        p.setPrefSize(size, size);
        p.setMinSize(size, size);
        p.setMaxSize(size, size);
        p.getStyleClass().add("media-thumb");
        return p;
    }

    // Bubble tài liệu kiểu outgoing-file (UI only)
    private HBox makeDocRow(String filename, String meta) {
        // vỏ ngoài
        HBox row = new HBox();
        row.setSpacing(0);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox();
        box.setId("outgoing-file");
        box.setPadding(new Insets(8,12,8,12));

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename == null ? "" : filename);
        nameLbl.setId("fileNamePrimary");
        nameLbl.getStyleClass().add("file-name");

        Label metaLbl = new Label(meta == null ? "" : meta);
        metaLbl.setId("fileMeta");
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.setId("fileInfoBox");
        info.getChildren().addAll(nameLbl, metaLbl);

        Region innerSpacer = new Region();
        HBox.setHgrow(innerSpacer, Priority.ALWAYS);

        content.getChildren().addAll(icon, info, innerSpacer);
        box.getChildren().add(content);

        row.getChildren().add(box);

        return row;
    }
	
	 private void showImageOverlay(Image image) {
	     if (overlayLayer == null || overlayContent == null || image == null) return;
	
	     // Xóa nội dung cũ
	     overlayContent.getChildren().clear();
	
	     ImageView iv = new ImageView(image);
	     iv.setPreserveRatio(true);
	     iv.setSmooth(true);
	
	     // Cố định kích thước tối đa của hình ảnh
	     iv.setFitHeight(600);
	     iv.setFitWidth(900);
	
	     overlayContent.getChildren().add(iv);
	     showOverlayElements();
	 }
	
	
	 private void showVideoOverlay(Image thumbnailAsBackdrop) {
		    if (overlayLayer == null || overlayContent == null) return;
	
		    overlayContent.getChildren().clear();
	
		    // nền: dùng thumbnail cho đẹp
		    ImageView iv = new ImageView(thumbnailAsBackdrop);
		    iv.setPreserveRatio(true);
		    iv.setSmooth(true);
		    iv.setFitHeight(600);
		    iv.setFitWidth(900);
	
		    // Lớp điều khiển video (UI)
		    Button playBtn = new Button("▶");
		    playBtn.getStyleClass().add("overlay-action");
	
		    // Thanh tiến trình
		    Slider progress = new Slider(0, 1, 0);
		    HBox.setHgrow(progress, Priority.ALWAYS); // Cho thanh tiến trình mở rộng
	
		    // Nút âm lượng và thanh âm lượng
		    Button volumeBtn = new Button("🔊");
		    volumeBtn.getStyleClass().add("overlay-action");
		    Slider volumeSlider = new Slider(0, 1, 1);
		    volumeSlider.setPrefWidth(80); // Cố định chiều rộng cho thanh âm lượng
	
		    // Container cho các nút điều khiển
		    HBox controls = new HBox(10, playBtn, progress, volumeBtn, volumeSlider);
		    controls.setAlignment(Pos.CENTER_LEFT);
		    controls.setPadding(new Insets(10));
	
		    // Đặt controls vào dưới cùng của media khổ giữa
		    BorderPane playerPane = new BorderPane();
		    playerPane.setCenter(iv);
		    BorderPane.setAlignment(iv, Pos.CENTER);
		    
		    // Sử dụng StackPane để căn giữa controls
		    StackPane bottomBar = new StackPane(controls);
		    bottomBar.setMaxWidth(Double.MAX_VALUE);
		    playerPane.setBottom(bottomBar);
	
		    // căn giữa tất cả trong overlayContent
		    overlayContent.getChildren().add(playerPane);
	
		    showOverlayElements();
		}
	
	 private void showOverlayElements() {
	     overlayLayer.setVisible(true);
	     overlayLayer.setManaged(true);
	
	     // Đảm bảo thứ tự hiển thị cuối cùng sau khi mọi thứ đã được thêm vào
	     Platform.runLater(() -> {
	         if (overlayDim != null) overlayDim.toBack();
	         if (overlayContent != null) overlayContent.toFront();
	         // Lấy HBox chứa các nút và đưa lên trên cùng
	         Node hBoxButtons = overlayLayer.lookup(".overlay-action").getParent();
	         if (hBoxButtons != null) {
	             hBoxButtons.toFront();
	         }
	     });
	 }

    private void hideOverlay() {
        if (overlayLayer == null) return;
        overlayLayer.setVisible(false);
        overlayLayer.setManaged(false);
        if (overlayContent != null) overlayContent.getChildren().clear();
    }

    public void showUser(User u, boolean online, String lastSeenIso) {
        if (infoName != null) infoName.setText(u.getUsername());
        applyStatusLabel(chatStatus, online, lastSeenIso);
    }
    
    public void setAvatar(Image img) {
        if (rightHeaderAvatar != null && img != null) {
            rightHeaderAvatar.setImage(img);
        }
    }

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