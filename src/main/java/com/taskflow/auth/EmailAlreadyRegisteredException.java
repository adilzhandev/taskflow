package com.taskflow.auth;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException() {
        super("Email is already registered");
    }
}
