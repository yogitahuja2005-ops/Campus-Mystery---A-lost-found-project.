package com.campusmystery;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** A deliberately compact JavaFX implementation of the Campus Mystery workflow. */
public class CampusMysteryApp extends Application {
    private final Database db = new Database();
    private Stage stage;
    private Models.User user;

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("Campus Mystery — Secure Lost & Found");
        stage.setMinWidth(940); stage.setMinHeight(650);
        showLogin(); stage.show();
    }

    private void showLogin() {
        Label title = new Label("CAMPUS MYSTERY"); title.getStyleClass().add("title");
        Label subtitle = new Label("Secure, evidence-driven campus lost and found"); subtitle.getStyleClass().add("subtitle");
        TextField email = new TextField("alex@campus.edu"); email.setPromptText("University email");
        PasswordField password = new PasswordField(); password.setText("password123"); password.setPromptText("Password");
        Button login = primary("Sign in");
        Button register = new Button("Create student account");
        login.setMaxWidth(Double.MAX_VALUE); register.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(e -> {
            try {
                user = db.login(email.getText(), password.getText());
                if (user == null) error("Sign-in failed", "Check your email and password."); else showDashboard();
            } catch (SQLException ex) { databaseError(ex); }
        });
        register.setOnAction(e -> showRegister());
        VBox card = new VBox(12, title, subtitle, new Label("Email"), email, new Label("Password"), password, login,
                new Separator(), register, new Label("Demo: admin@campus.edu or alex@campus.edu / password123"));
        card.getStyleClass().add("card"); card.setMaxWidth(430); card.setPadding(new Insets(32));
        StackPane root = new StackPane(card); root.getStyleClass().add("login-page"); setScene(root);
    }

    private void showRegister() {
        TextField name = new TextField(); TextField email = new TextField(); PasswordField password = new PasswordField();
        GridPane form = form(); add(form, 0, "Name", name); add(form, 1, "University email", email); add(form, 2, "Password (8+ characters)", password);
        Button create = primary("Create account"); Button back = new Button("Back to sign in");
        create.setOnAction(e -> {
            if (blank(name.getText(), email.getText(), password.getText()) || password.getText().length() < 8) { warn("Enter a name, email, and a password of at least 8 characters."); return; }
            try { db.register(name.getText(), email.getText(), password.getText(), "STUDENT"); info("Account created", "You can now sign in."); showLogin(); }
            catch (SQLException ex) { error("Account not created", "That email may already be registered."); }
        });
        back.setOnAction(e -> showLogin());
        VBox card = new VBox(16, heading("Create student account", "Student accounts can report lost items, submit claims, and register found items."), form, new HBox(10, create, back));
        card.getStyleClass().add("card"); card.setMaxWidth(560); card.setPadding(new Insets(32)); StackPane root = new StackPane(card); root.getStyleClass().add("login-page"); setScene(root);
    }

    private void showDashboard() {
        Label welcome = new Label("Signed in as " + user.name() + "  •  " + user.role()); welcome.getStyleClass().add("welcome");
        Button logout = new Button("Sign out"); logout.setOnAction(e -> { user = null; showLogin(); });
        BorderPane root = new BorderPane(); root.setTop(header(welcome, logout));
        TabPane tabs = new TabPane(); tabs.getStyleClass().add("main-tabs");
        if (user.isAdmin()) {
            tabs.getTabs().addAll(foundItemTab(), adminClaimsTab(), foundItemsTab());
        } else {
            tabs.getTabs().addAll(lostReportTab(), smartMatchTab(), myClaimsTab(), foundItemTab());
        }
        root.setCenter(tabs); setScene(root);
    }

    private Tab lostReportTab() {
        TextField name = new TextField(); TextField category = new TextField(); TextField colour = new TextField();
        TextField location = new TextField(); DatePicker date = new DatePicker(LocalDate.now()); TextArea description = area("Public description — do not put private identifiers here.");
        GridPane fields = form(); add(fields, 0, "Item name", name); add(fields, 1, "Category", category); add(fields, 2, "Colour", colour);
        add(fields, 3, "Where was it lost?", location); add(fields, 4, "Approximate date", date); add(fields, 5, "Description", description);
        Button save = primary("Submit lost-item report");
        save.setOnAction(e -> {
            if (blank(name.getText(), category.getText(), colour.getText(), location.getText(), description.getText()) || date.getValue() == null) { warn("Complete every field before submitting the report."); return; }
            try { db.addLostItem(user.id(), name.getText(), category.getText(), colour.getText(), location.getText(), date.getValue(), description.getText()); info("Lost report saved", "Use SmartMatch to rank found-item candidates. A match is not proof of ownership."); clear(name, category, colour, location, description); date.setValue(LocalDate.now()); }
            catch (SQLException ex) { databaseError(ex); }
        });
        return tab("Report lost item", page(heading("Report a lost item", "Only public item details go here. TrustGuard questions are kept with the found-item record."), fields, save));
    }

    private Tab smartMatchTab() {
        ComboBox<Models.LostItem> lost = new ComboBox<>(); lost.setPrefWidth(420);
        Button load = new Button("Load my reports"); Button match = primary("Run SmartMatch");
        TableView<Models.Match> table = matchTable(); TextArea detail = area("Select a candidate to see its public description and explainable score."); detail.setEditable(false);
        Button claim = primary("Claim selected item"); claim.setDisable(true);
        load.setOnAction(e -> loadLostReports(lost));
        match.setOnAction(e -> {
            if (lost.getValue() == null) { warn("Load and select one of your lost reports first."); return; }
            try {
                List<Models.Match> matches = db.availableFoundItems().stream().map(f -> SmartMatch.compare(lost.getValue(), f))
                        .sorted(Comparator.comparingDouble(Models.Match::score).reversed()).toList();
                table.setItems(FXCollections.observableArrayList(matches));
            } catch (SQLException ex) { databaseError(ex); }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            claim.setDisable(selected == null);
            if (selected != null) detail.setText(selected.explanation() + "\n\nPublic item details:\n" + selected.item().description() + "\n\nStatus: " + selected.item().status());
        });
        claim.setOnAction(e -> {
            Models.Match selected = table.getSelectionModel().getSelectedItem(); if (selected == null) return;
            try { db.createClaim(selected.item().id(), user.id()); info("Claim created", "Open My claims and complete the private TrustGuard questions. You have at most three attempts."); }
            catch (SQLException ex) { error("Claim not created", "You may already have a claim for this item, or the item was returned."); }
        });
        HBox controls = new HBox(10, new Label("Lost report:"), lost, load, match); controls.setAlignment(Pos.CENTER_LEFT);
        return tab("SmartMatch", page(heading("SmartMatch candidates", "Weighted score: type 30%, colour 15%, location 20%, date 15%, description 20%. It ranks; it never establishes ownership."), controls, table, detail, claim));
    }

    private Tab myClaimsTab() {
        TableView<Models.Claim> table = claimTable(false); Button refresh = new Button("Refresh claims"); Button verify = primary("Answer TrustGuard questions");
        refresh.setOnAction(e -> loadStudentClaims(table)); verify.setOnAction(e -> { Models.Claim claim = table.getSelectionModel().getSelectedItem(); if (claim == null) warn("Select one of your claims first."); else verifyClaim(claim, table); });
        loadStudentClaims(table);
        return tab("My claims", page(heading("My claims", "Your score and status are confidential. Repeated guesses are limited to three attempts."), new HBox(10, refresh, verify), table));
    }

    private Tab foundItemTab() {
        TextField name = new TextField(); TextField category = new TextField(); TextField colour = new TextField(); TextField location = new TextField(); DatePicker date = new DatePicker(LocalDate.now());
        TextArea description = area("Public description only. Never include an answer below.");
        GridPane details = form(); add(details, 0, "Item name", name); add(details, 1, "Category", category); add(details, 2, "Colour", colour); add(details, 3, "Found location", location); add(details, 4, "Found date", date); add(details, 5, "Public description", description);
        List<TextField> questions = new ArrayList<>(), answers = new ArrayList<>(); GridPane secrets = form();
        for (int i = 0; i < 3; i++) { TextField q = new TextField(); TextField a = new TextField(); questions.add(q); answers.add(a); add(secrets, i, "Private question " + (i + 1), q); add(secrets, i + 3, "Private answer " + (i + 1), a); }
        Button save = primary("Register found item with TrustGuard");
        save.setOnAction(e -> {
            if (blank(name.getText(), category.getText(), colour.getText(), location.getText(), description.getText()) || date.getValue() == null || questions.stream().anyMatch(q -> q.getText().isBlank()) || answers.stream().anyMatch(a -> a.getText().isBlank())) { warn("Complete all public fields and all three private question/answer pairs."); return; }
            String publicText = description.getText().toLowerCase();
            if (answers.stream().anyMatch(a -> a.getText().trim().length() > 2 && publicText.contains(a.getText().trim().toLowerCase()))) { warn("A private answer appears in the public description. Remove it before saving."); return; }
            List<Database.Secret> secretsToSave = new ArrayList<>(); for (int i = 0; i < 3; i++) secretsToSave.add(new Database.Secret(questions.get(i).getText(), answers.get(i).getText()));
            try { db.addFoundItem(user.id(), name.getText(), category.getText(), colour.getText(), location.getText(), date.getValue(), description.getText(), secretsToSave); info("Found item registered", "Its three answers were hashed and are never displayed. Claimants will receive only the questions."); clear(name, category, colour, location, description); questions.forEach(q -> q.clear()); answers.forEach(a -> a.clear()); }
            catch (SQLException | IllegalArgumentException ex) { error("Item not saved", ex.getMessage()); }
        });
        return tab("Register found item", page(heading("Register a found item", "TrustGuard stores private answers as hashes. All competing claimants see the same questions."), details, new Separator(), heading("TrustGuard private verification", "Create three details only the genuine owner should know."), secrets, save));
    }

    private Tab adminClaimsTab() {
        TableView<Models.Claim> table = claimTable(true); TextArea note = area("Required for a decision or handover confirmation.");
        Button refresh = new Button("Refresh & rank"); Button evidence = new Button("Request evidence"); Button approve = primary("Approve selected"); Button reject = new Button("Reject selected"); Button handover = primary("Confirm physical handover");
        refresh.setOnAction(e -> loadAdminClaims(table));
        evidence.setOnAction(e -> review(table, note.getText(), "evidence")); approve.setOnAction(e -> review(table, note.getText(), "approve")); reject.setOnAction(e -> review(table, note.getText(), "reject")); handover.setOnAction(e -> review(table, note.getText(), "handover")); loadAdminClaims(table);
        return tab("Admin review", page(heading("TrustGuard review and handover", "Scores are evidence, not automatic decisions. Compare candidates for an item, request extra proof if needed, then approve. Mark returned only after physical handover."), new HBox(10, refresh, evidence, approve, reject, handover), table, new Label("Administrator note"), note));
    }

    private Tab foundItemsTab() {
        TableView<Models.FoundItem> table = foundTable(); Button refresh = new Button("Refresh items"); refresh.setOnAction(e -> loadFoundItems(table)); loadFoundItems(table);
        return tab("Found-item register", page(heading("Found item register", "Returned items are kept in the audit trail but excluded from claims."), refresh, table));
    }

    private void verifyClaim(Models.Claim claim, TableView<Models.Claim> source) {
        try {
            List<Models.Question> questions = db.questionsForClaim(claim.id(), user.id());
            Dialog<Void> dialog = new Dialog<>(); dialog.setTitle("TrustGuard verification"); dialog.setHeaderText("Attempt " + (claim.attempts() + 1) + " of 3 — answers are checked confidentially.");
            GridPane grid = form(); List<TextField> answers = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) { TextField answer = new TextField(); answer.setPromptText("Your answer"); answers.add(answer); add(grid, i, questions.get(i).text(), answer); }
            ButtonType submit = new ButtonType("Submit confidentially", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(submit, ButtonType.CANCEL); dialog.getDialogPane().setContent(grid);
            Button submitButton = (Button) dialog.getDialogPane().lookupButton(submit);
            submitButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                if (answers.stream().anyMatch(a -> a.getText().isBlank())) { warn("Answer every question."); e.consume(); return; }
                try { double score = db.submitAnswers(claim.id(), user.id(), questions, answers.stream().map(TextInputControl::getText).toList()); info("Verification recorded", "Your score is " + score + "%. " + (score >= 60 ? "An administrator will review the evidence." : "No answer feedback is shown. Check My claims for remaining attempts.")); dialog.close(); loadStudentClaims(source); }
                catch (SQLException | IllegalStateException ex) { error("Verification not submitted", ex.getMessage()); e.consume(); }
            });
            dialog.showAndWait();
        } catch (SQLException | IllegalStateException ex) { error("Verification unavailable", ex.getMessage()); }
    }

    private void review(TableView<Models.Claim> table, String note, String action) {
        Models.Claim claim = table.getSelectionModel().getSelectedItem(); if (claim == null) { warn("Select a claimant row first."); return; }
        if (note.isBlank()) { warn("Add an administrator note for the audit trail."); return; }
        try {
            switch (action) { case "approve" -> db.approveClaim(claim.id(), user.id(), note); case "reject" -> db.rejectClaim(claim.id(), user.id(), note); case "evidence" -> db.requestMoreInformation(claim.id(), user.id(), note); case "handover" -> db.confirmHandover(claim.id(), user.id(), note); }
            info("Decision recorded", action.equals("handover") ? "Physical handover is confirmed and the item is now marked RETURNED." : "The claim status and audit trail have been updated."); loadAdminClaims(table);
        } catch (SQLException | IllegalStateException ex) { error("Action not completed", ex.getMessage()); }
    }

    private TableView<Models.Match> matchTable() {
        TableView<Models.Match> table = new TableView<>(); table.getColumns().addAll(column("Item", m -> m.item().name()), column("Category", m -> m.item().category()), column("Location", m -> m.item().location()), column("Found date", m -> m.item().date().toString()), column("Score", m -> m.score() + "%")); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }
    private TableView<Models.Claim> claimTable(boolean admin) {
        TableView<Models.Claim> table = new TableView<>(); table.getColumns().addAll(column("Claim", c -> "#" + c.id()), column("Item", Models.Claim::itemName));
        if (admin) table.getColumns().add(column("Claimant", Models.Claim::claimant));
        table.getColumns().addAll(column("Score", c -> c.score() + "%"), column("Attempts", c -> c.attempts() + "/3"), column("Status", Models.Claim::status), column("Admin note", Models.Claim::note)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); return table;
    }
    private TableView<Models.FoundItem> foundTable() {
        TableView<Models.FoundItem> table = new TableView<>(); table.getColumns().addAll(column("ID", f -> "#" + f.id()), column("Item", Models.FoundItem::name), column("Category", Models.FoundItem::category), column("Colour", Models.FoundItem::colour), column("Location", Models.FoundItem::location), column("Date", f -> f.date().toString()), column("Status", Models.FoundItem::status)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); return table;
    }
    private <T> TableColumn<T, String> column(String name, java.util.function.Function<T, String> value) { TableColumn<T, String> column = new TableColumn<>(name); column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue()))); return column; }

    private void loadLostReports(ComboBox<Models.LostItem> box) { try { box.getItems().setAll(db.lostItemsFor(user.id())); if (!box.getItems().isEmpty()) box.getSelectionModel().selectFirst(); } catch (SQLException ex) { databaseError(ex); } }
    private void loadStudentClaims(TableView<Models.Claim> table) { try { table.setItems(FXCollections.observableArrayList(db.claimsForStudent(user.id()))); } catch (SQLException ex) { databaseError(ex); } }
    private void loadAdminClaims(TableView<Models.Claim> table) { try { table.setItems(FXCollections.observableArrayList(db.allClaims())); } catch (SQLException ex) { databaseError(ex); } }
    private void loadFoundItems(TableView<Models.FoundItem> table) { try { table.setItems(FXCollections.observableArrayList(db.availableFoundItems())); } catch (SQLException ex) { databaseError(ex); } }

    private BorderPane header(Label welcome, Button logout) { Label brand = new Label("CAMPUS MYSTERY"); brand.getStyleClass().add("brand"); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox bar = new HBox(18, brand, spacer, welcome, logout); bar.setAlignment(Pos.CENTER_LEFT); bar.setPadding(new Insets(16, 24, 16, 24)); bar.getStyleClass().add("header"); return new BorderPane(bar); }
    private VBox page(Node... children) { VBox box = new VBox(16, children); box.setPadding(new Insets(24)); if (children.length > 0) VBox.setVgrow(children[children.length - 1], Priority.ALWAYS); return box; }
    private Tab tab(String title, Parent content) { Tab tab = new Tab(title, content); tab.setClosable(false); return tab; }
    private GridPane form() { GridPane grid = new GridPane(); grid.setHgap(14); grid.setVgap(10); grid.getColumnConstraints().addAll(new ColumnConstraints(190), new ColumnConstraints()); return grid; }
    private void add(GridPane grid, int row, String label, Control input) { input.setMaxWidth(Double.MAX_VALUE); if (input instanceof TextArea a) a.setPrefRowCount(3); grid.add(new Label(label), 0, row); grid.add(input, 1, row); GridPane.setHgrow(input, Priority.ALWAYS); }
    private TextArea area(String prompt) { TextArea value = new TextArea(); value.setPromptText(prompt); value.setWrapText(true); return value; }
    private VBox heading(String title, String text) { Label h = new Label(title); h.getStyleClass().add("section-title"); Label body = new Label(text); body.setWrapText(true); body.getStyleClass().add("hint"); return new VBox(3, h, body); }
    private Button primary(String text) { Button button = new Button(text); button.getStyleClass().add("primary"); return button; }
    private void setScene(Parent root) { Scene scene = new Scene(root, 1050, 720); var css = getClass().getResource("/campus.css"); if (css != null) scene.getStylesheets().add(css.toExternalForm()); stage.setScene(scene); }
    private boolean blank(String... values) { for (String value : values) if (value == null || value.isBlank()) return true; return false; }
    private void clear(TextInputControl... controls) { for (TextInputControl control : controls) control.clear(); }
    private void databaseError(Exception error) { error("Database problem", error.getMessage() == null ? "Please try again." : error.getMessage()); }
    private void warn(String message) { alert(Alert.AlertType.WARNING, "Check the form", message); }
    private void info(String title, String message) { alert(Alert.AlertType.INFORMATION, title, message); }
    private void error(String title, String message) { alert(Alert.AlertType.ERROR, title, message); }
    private void alert(Alert.AlertType type, String title, String message) { Alert alert = new Alert(type, message, ButtonType.OK); alert.setTitle(title); alert.setHeaderText(null); alert.showAndWait(); }

    public static void main(String[] args) { launch(args); }
}
