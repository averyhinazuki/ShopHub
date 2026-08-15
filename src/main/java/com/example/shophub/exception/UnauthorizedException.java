package com.example.shophub.exception;

/**
 * The request carried a structurally valid token that no longer identifies anyone
 * — most obviously a token whose user has since been deleted. That is a 401, not
 * a 500: the server is fine, the credential is not.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
