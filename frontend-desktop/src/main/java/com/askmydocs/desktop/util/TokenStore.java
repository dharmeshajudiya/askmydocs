package com.askmydocs.desktop.util;

/**
 * In-memory singleton token store. Holds JWT access/refresh tokens for the session.
 */
public final class TokenStore {

    private static final TokenStore INSTANCE = new TokenStore();

    private String accessToken;
    private String refreshToken;

    private TokenStore() {}

    public static TokenStore get() { return INSTANCE; }

    public void save(String accessToken, String refreshToken) {
        this.accessToken  = accessToken;
        this.refreshToken = refreshToken;
    }

    public void clear() {
        this.accessToken  = null;
        this.refreshToken = null;
    }

    public String accessToken()  { return accessToken; }
    public String refreshToken() { return refreshToken; }
    public boolean isLoggedIn()  { return accessToken != null; }
}
