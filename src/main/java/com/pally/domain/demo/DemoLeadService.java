package com.pally.domain.demo;

import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.demo.DemoLeadJpaEntity;
import com.pally.infrastructure.persistence.demo.DemoLeadJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class DemoLeadService {

    private final EmailService emailService;
    private final DemoLeadJpaRepository leadRepo;

    @Value("${demo.leads.email:hello@apalchi.com}")
    private String leadsEmail;

    /**
     * Backward-compatible 4-arg overload. Delegates to the full signature
     * with nulls for optional segment/size fields.
     */
    public String submitLead(String orgName, String contactName, String email, String phone) {
        return submitLead(orgName, contactName, email, phone, null, null, null);
    }

    /**
     * Full lead submission: validates, persists a {@code demo_leads} row,
     * then emails the sales inbox and sends a confirmation to the requester.
     *
     * @return the new lead id
     */
    public String submitLead(String orgName, String contactName, String email, String phone,
                             String segment, Integer estClasses, Integer estStudents) {
        if (orgName == null || orgName.isBlank())
            throw new BusinessException("Organisation name is required", 400);
        if (contactName == null || contactName.isBlank())
            throw new BusinessException("Contact name is required", 400);
        if (email == null || !email.matches(".+@.+\\..+"))
            throw new BusinessException("Valid email is required", 400);
        if (phone == null || phone.isBlank())
            throw new BusinessException("Contact number is required", 400);

        String seg = (segment != null && !segment.isBlank()) ? segment.toUpperCase() : "CENTRE";
        if (!Set.of("SOLO", "CENTRE", "SCHOOL").contains(seg)) seg = "CENTRE";

        log.info("[Demo] New lead org='{}' contact='{}' email='{}' segment='{}'",
                orgName, contactName, email, seg);

        // Persist lead row
        DemoLeadJpaEntity lead = new DemoLeadJpaEntity();
        lead.setId(IdGenerator.newId());
        lead.setOrgName(orgName.trim());
        lead.setContactName(contactName.trim());
        lead.setEmail(email.trim().toLowerCase());
        lead.setPhone(phone.trim());
        lead.setSegment(seg);
        lead.setEstClasses(estClasses);
        lead.setEstStudents(estStudents);
        lead.setStatus(DemoLeadJpaEntity.STATUS_NEW);
        lead.setCreatedAt(Instant.now());
        lead.setUpdatedAt(Instant.now());
        leadRepo.save(lead);

        // Notify the sales inbox — best-effort; log on failure so leads are never silently lost.
        try {
            emailService.sendHtml(leadsEmail,
                    "New demo request: " + orgName,
                    buildLeadHtml(orgName, contactName, email, phone, seg, estClasses, estStudents));
        } catch (Exception e) {
            log.error("[Demo] Failed to email leads inbox: {}", e.getMessage());
        }

        // Confirmation to requester — best-effort; requester gets 200 either way.
        try {
            emailService.sendHtml(email,
                    "We received your Apalchi demo request",
                    buildConfirmHtml(contactName));
        } catch (Exception e) {
            log.warn("[Demo] Failed to send confirmation to {}: {}", email, e.getMessage());
        }

        return lead.getId();
    }

    private String buildLeadHtml(String orgName, String contactName, String email, String phone,
                                  String segment, Integer estClasses, Integer estStudents) {
        return "<h2 style='font-family:sans-serif'>New Apalchi Demo Request</h2>"
             + "<table style='font-family:sans-serif;border-collapse:collapse'>"
             + row("Organisation", orgName)
             + row("Contact",      contactName)
             + row("Email",        email)
             + row("Phone",        phone)
             + row("Segment",      segment)
             + (estClasses  != null ? row("Est. Classes",  String.valueOf(estClasses))  : "")
             + (estStudents != null ? row("Est. Students", String.valueOf(estStudents)) : "")
             + "</table>";
    }

    private String buildConfirmHtml(String contactName) {
        return "<h2 style='font-family:sans-serif'>We've got your request!</h2>"
             + "<p style='font-family:sans-serif'>Hi " + contactName + ",</p>"
             + "<p style='font-family:sans-serif'>Thanks for your interest in bringing Apalchi to your centre. "
             + "We'll be in touch within one business day to set up a demo.</p>"
             + "<p style='font-family:sans-serif'>— The Apalchi team</p>";
    }

    private String row(String label, String value) {
        return "<tr><td style='padding:6px 16px 6px 0;font-weight:700'>" + label + "</td>"
             + "<td style='padding:6px 0'>" + value + "</td></tr>";
    }
}
