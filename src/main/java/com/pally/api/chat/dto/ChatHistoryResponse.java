package com.pally.api.chat.dto;

import java.util.List;

public record ChatHistoryResponse(
        List<SyncMessageDto> messages
) {}
