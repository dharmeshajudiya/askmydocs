package com.askmydocs.desktop.controller;

import com.askmydocs.desktop.AskMyDocsApp;
import com.askmydocs.desktop.service.AuthService;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public class LoginController {

    @FXML private TextField     emailField;
    @FXML private PasswordField passwordField;
    @FXML private Label         errorLabel;
    @FXML private Button        submitBtn;
    @FXML private Button        btnLoginTab;
    @FXML private Button        btnRegisterTab;

    private final AuthService authService = new AuthService();
    private boolean registerMode = false;

    @FXML
    public void switchToLogin() {
        registerMode = false;
        submitBtn.setText("Sign In");
        btnLoginTab.getStyleClass().add("tab-active");
        btnRegisterTab.getStyleClass().remove("tab-active");
        clearError();
    }

    @FXML
    public void switchToRegister() {
        registerMode = true;
        submitBtn.setText("Create Account");
        btnRegisterTab.getStyleClass().add("tab-active");
        btnLoginTab.getStyleClass().remove("tab-active");
        clearError();
    }

    @FXML
    public void handleSubmit() {
        String email    = emailField.getText().trim();
        String password = passwordField.getText();

        if (email.isEmpty() || password.isEmpty()) {
            showError("Email and password are required.");
            return;
        }

        setLoading(true);
        clearError();

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                if (registerMode) {
                    authService.register(email, password);
                } else {
                    authService.login(email, password);
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            setLoading(false);
            try { AskMyDocsApp.showMain(); }
            catch (Exception ex) { showError("Failed to open main window."); }
        });

        task.setOnFailed(e -> {
            setLoading(false);
            Throwable ex = task.getException();
            if (ex.getMessage() != null && ex.getMessage().contains("409")) {
                showError("Email already registered.");
            } else if (ex.getMessage() != null && ex.getMessage().contains("401")) {
                showError("Invalid email or password.");
            } else {
                showError("Connection failed. Is the server running?");
            }
        });

        new Thread(task).start();
    }

    private void setLoading(boolean loading) {
        submitBtn.setDisable(loading);
        submitBtn.setText(loading ? "Please wait…" :
            (registerMode ? "Create Account" : "Sign In"));
    }

    private void showError(String msg) {
        errorLabel.setText(msg);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void clearError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
