package com.pally.api.admin;

import com.pally.domain.centre.CentreInviteService;
import com.pally.domain.centre.OrgSubscriptionService;
import com.pally.domain.demo.DemoLeadService;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import com.pally.infrastructure.persistence.demo.DemoLeadJpaRepository;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock ChatMessageJpaRepository chatRepo;
    @Mock OrganizationJpaRepository orgRepo;
    @Mock UserJpaRepository userRepo;
    @Mock CentreInviteService inviteService;
    @Mock DemoLeadJpaRepository leadRepo;
    @Mock OrgSubscriptionService orgSubService;
    @Mock DemoLeadService leadService;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;

    @InjectMocks AdminController controller;

    private UserJpaEntity user(String id, String email, String name) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName(name);
        return u;
    }

    @Test
    void listUsers_blankQuery_browsesAll_andNeverSearches() {
        when(userRepo.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user("u1", "a@x.com", "Alice"))));

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> res =
                controller.listUsers(0, 50, null);

        assertThat(res.getBody().data()).hasSize(1);
        assertThat(res.getBody().data().get(0).get("email")).isEqualTo("a@x.com");
        verify(userRepo).findAll(any(Pageable.class));
        verify(userRepo, never())
                .findByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(any(), any(), any());
    }

    @Test
    void listUsers_withQuery_searchesEmailOrDisplayName_trimmed_andNeverBrowsesAll() {
        when(userRepo.findByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                eq("alice"), eq("alice"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user("u1", "alice@x.com", "Alice Tan"))));

        // Leading/trailing whitespace must be trimmed before the DB query.
        ResponseEntity<ApiResponse<List<Map<String, Object>>>> res =
                controller.listUsers(0, 50, "  alice  ");

        assertThat(res.getBody().data()).hasSize(1);
        assertThat(res.getBody().data().get(0).get("displayName")).isEqualTo("Alice Tan");
        verify(userRepo, never()).findAll(any(Pageable.class));
    }
}
