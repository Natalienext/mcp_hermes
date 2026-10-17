package org.example.wardrobe.memory.domain;

public class MemoryException extends RuntimeException {

    private final ErrorCode code;

    public MemoryException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
