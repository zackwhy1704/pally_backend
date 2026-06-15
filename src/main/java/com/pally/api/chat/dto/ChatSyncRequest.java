package com.pally.api.chat.dto;

import com.pally.domain.chat.dto.SyncMessageDto;

import java.util.List;

public record ChatSyncRequest(
        List<SyncMessageDto> messages
) {}
