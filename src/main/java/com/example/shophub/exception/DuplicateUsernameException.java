package com.example.shophub.exception;

/**
 * Registration was well-formed but conflicts with existing state — the textbook
 * case for 409 CONFLICT. Exists so the throw site stops landing in
 * GlobalExceptionHandler's RuntimeException catch-all, which reports 500 and
 * tells the client the server broke when the client simply picked a taken name.
 */
public class DuplicateUsernameException extends RuntimeException {

    private final String username;

    public DuplicateUsernameException(String username) {
        super("Username already taken: " + username);
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
