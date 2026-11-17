package com.abhishek.attendance.web;

/** The request is valid but clashes with the current state (duplicate, already decided, and so on). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
