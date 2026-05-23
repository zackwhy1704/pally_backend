package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.WikiPage;

import java.util.List;

public interface PhotoQuestionPort {
    List<QuestionAnswerDto> solveQuestions(Avatar avatar, List<WikiPage> wikiPages, List<String> questions);
}
