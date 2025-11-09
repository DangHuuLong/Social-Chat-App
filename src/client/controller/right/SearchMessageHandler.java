package client.controller.right;

import client.controller.MidController;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SearchMessageHandler {
    private MidController mid;
    public SearchMessageHandler(MidController mid) { this.mid = mid; }
    public void setMidController(MidController m) { this.mid = m; }

    public void open(Window owner) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.APPLICATION_MODAL);
        st.setTitle("Tìm kiếm tin nhắn");

        TextField input = new TextField();
        input.setPromptText("Nhập cụm từ (không phân biệt hoa/thường, dấu/không dấu)");
        Button btnSearch = new Button("Tìm");
        btnSearch.getStyleClass().add("send-btn");
        Button btnClose  = new Button("Đóng");
        btnClose.getStyleClass().add("action-btn"); 

        HBox bar = new HBox(8, input, btnSearch, btnClose);
        HBox.setHgrow(input, Priority.ALWAYS);

        VBox results = new VBox(8);
        results.setFillWidth(true);
        results.setPadding(new Insets(8));

        ScrollPane sp = new ScrollPane(results);
        sp.setFitToWidth(true);
        sp.setPrefHeight(420);
        sp.getStyleClass().add("message-scroll");

        VBox root = new VBox(12, bar, sp);
        root.setPadding(new Insets(14));
        root.setPrefWidth(680);
        root.getStyleClass().add("root-bg");

        Scene sc = new Scene(root);

        if (owner != null && owner.getScene() != null) {
            sc.getStylesheets().addAll(owner.getScene().getStylesheets());
            if (owner.getScene().getRoot().getStyleClass().contains("dark")) {
                sc.getRoot().getStyleClass().add("dark");
            }
        }

        st.setScene(sc);

        Runnable doSearch = () -> {
            results.getChildren().clear();
            String raw = input.getText() == null ? "" : input.getText().trim();
            if (raw.isEmpty() || mid == null) return;

            String key = normalizeKey(raw);
            List<client.controller.MidController.MsgView> all = mid.exportMessagesForSearch();
            List<Item> matched = new ArrayList<>();

            java.util.HashSet<String> seen = new java.util.HashSet<>();

            for (client.controller.MidController.MsgView v : all) {
                String norm = normalizeKey(v.text());

                int colon = norm.indexOf(":");
                String msgOnly = (colon >= 0 && colon + 1 < norm.length())
                        ? norm.substring(colon + 1).trim()
                        : norm;

                if (msgOnly.contains(key)) {
                    String sig = v.epochMillis() + "|" + v.incoming() + "|" + v.text();
                    if (seen.add(sig)) { 
                        matched.add(new Item(v.epochMillis(), v.incoming(), v.text()));
                    }
                }
            }

            matched.sort(Comparator.comparingLong(a -> a.ts));

            var zone = ZoneId.systemDefault();
            LocalDate last = null;
            for (Item m : matched) {
                var d = java.time.Instant.ofEpochMilli(m.ts).atZone(zone).toLocalDate();
                if (!d.equals(last)) {
                    last = d;
                    Label chip = new Label(d.getDayOfMonth() + "/" + d.getMonthValue() + "/" + d.getYear());
                    chip.getStyleClass().add("date-chip");
                    HBox wrap = new HBox(chip);
                    wrap.setAlignment(Pos.CENTER);
                    wrap.setMaxWidth(Double.MAX_VALUE);
                    results.getChildren().add(wrap);
                }

                HBox row = new HBox();
                row.getStyleClass().add("search-result-row");
                row.setSpacing(6);

                VBox bubble = new VBox();
                bubble.setFillWidth(true);
                bubble.setSpacing(2);
                bubble.setPadding(new Insets(8, 12, 8, 12));
                bubble.setId(m.incoming ? "incoming-text" : "outgoing-text");

                Label content = new Label(m.text);
                content.setWrapText(true);
                bubble.getChildren().add(content);

                if (m.incoming) {
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.getChildren().addAll(bubble);
                } else {
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    row.setAlignment(Pos.CENTER_RIGHT);
                    row.getChildren().addAll(spacer, bubble);
                }
                results.getChildren().add(row);
            }

            if (matched.isEmpty()) {
                Label none = new Label("Không tìm thấy kết quả khớp.");
                none.getStyleClass().add("chat-last");
                results.getChildren().add(none);
            }
        };


        btnSearch.setOnAction(e -> doSearch.run());
        input.setOnAction(e -> doSearch.run());
        btnClose.setOnAction(e -> st.close());

        st.centerOnScreen();
        st.show();
        input.requestFocus();
    }

    public static String normalizeKey(String s) {
        String lower = s.toLowerCase().replace('đ', 'd').replace('ð', 'd');
        String norm = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return norm.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private record Item(long ts, boolean incoming, String text) {}
}
