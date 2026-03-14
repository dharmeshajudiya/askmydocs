package com.askmydocs.desktop.service;

import com.askmydocs.desktop.model.TokenResponse;
import com.askmydocs.desktop.util.TokenStore;

import java.util.Map;

public class AuthService {

    public void login(String email, String password) throws Exception {
        var tokens = ApiClient.postAnon("/auth/login",
            Map.of("email", email, "password", password),
            TokenResponse.class);
        TokenStore.get().save(tokens.accessToken(), tokens.refreshToken());
    }

    public void register(String email, String password) throws Exception {
        var tokens = ApiClient.postAnon("/auth/register",
            Map.of("email", email, "password", password),
            TokenResponse.class);
        TokenStore.get().save(tokens.accessToken(), tokens.refreshToken());
    }

    public void logout() {
        TokenStore.get().clear();
    }
}
