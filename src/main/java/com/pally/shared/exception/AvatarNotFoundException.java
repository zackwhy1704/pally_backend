package com.pally.shared.exception;

public class AvatarNotFoundException extends PallyException {

    public AvatarNotFoundException(String avatarId) {
        super("Avatar not found: " + avatarId, 404);
    }
}
