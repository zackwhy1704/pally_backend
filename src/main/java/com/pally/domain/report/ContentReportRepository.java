package com.pally.domain.report;

/** Port for persisting content reports. The JPA adapter lives in infrastructure/persistence. */
public interface ContentReportRepository {

    ContentReport save(ContentReport report);
}
