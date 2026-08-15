package com.pally.domain.classroom.dto;

/** @param wikiPageId the class-corpus page the shared boss's questions are generated from —
 *                     teacher-picked, same pattern as a student picking a page to shareNote(). */
public record ClassroomCreateRequest(String wikiPageId) {}
