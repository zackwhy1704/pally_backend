package com.pally.domain.chat.dto;

import java.util.List;

public record ChatHistoryResponse(
        List<SyncMessageDto> messages
) {}
