package com.pally.api.admin;

import com.pally.domain.cost.AiCallType;
import com.pally.domain.cost.AiUsageRepository;
import com.pally.domain.cost.AiUsageRepository.CostRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCostControllerTest {

    @Mock AiUsageRepository repository;

    @Test
    @SuppressWarnings("unchecked")
    void rollup_groupsPerUser_ordersByCostDesc_sumsCompileCount() {
        when(repository.summarize(any(), any())).thenReturn(List.of(
                new CostRow("cheap", AiCallType.CHAT, 100, 3),
                new CostRow("pricey", AiCallType.COMPILE, 90_000, 2),
                new CostRow("pricey", AiCallType.WEAKNESS_REBUILD, 10_000, 1)));

        var body = new AiCostController(repository).costs(null, null).getBody();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.data();

        // Ordered by total cost desc: pricey (100k) before cheap (100).
        assertThat(rows.get(0).get("userId")).isEqualTo("pricey");
        assertThat(rows.get(0).get("totalCostMicros")).isEqualTo(100_000L);
        // compileCount = COMPILE + WEAKNESS_REBUILD calls = 2 + 1.
        assertThat(rows.get(0).get("compileCount")).isEqualTo(3L);
        assertThat(rows.get(1).get("userId")).isEqualTo("cheap");
    }
}
