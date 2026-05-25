package com.pally.api.chat.dto;

import java.util.List;

public record ChatSyncRequest(
        List<SyncMessageDto> messages
) {}
