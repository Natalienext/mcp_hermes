package org.example.wardrobe.memory.application;

import java.time.Clock;
import java.util.List;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactSearch;
import org.example.wardrobe.memory.domain.MemoryException;
import org.example.wardrobe.memory.domain.RecallBlock;
import org.example.wardrobe.memory.domain.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Recall по реплике: лимит {@code min(limit, MEMORY_RECALL_LIMIT)} уходит в {@code LIMIT} (I10), ветки для 0 нет. */
@Service
public class RecallUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecallUseCase.class);

    private final FactSearch search;
    private final Clock clock;
    private final int configuredLimit;

    public RecallUseCase(FactSearch search, Clock clock, @Value("${memory.recall-limit:5}") int configuredLimit) {
        this.search = search;
        this.clock = clock;
        this.configuredLimit = configuredLimit;
    }

    /** {@code limit == null} — по умолчанию {@code MEMORY_RECALL_LIMIT}. */
    public RecallBlock recall(String userId, String query, Integer limit) {
        Turn.checkUserId(userId);
        if (limit != null && limit < 0) {
            throw new MemoryException(ErrorCode.VAL_010, "limit must be >= 0");
        }
        int effective = limit == null ? configuredLimit : Math.min(limit, configuredLimit);
        List<Fact> facts = search.search(userId, query == null ? "" : query, effective).stream()
                .filter(Fact::recallable)
                .toList();
        RecallBlock block = RecallBlock.render(facts, clock.instant());
        log.info("recall user_id={} ids={}", userId, block.ids());
        return block;
    }
}
