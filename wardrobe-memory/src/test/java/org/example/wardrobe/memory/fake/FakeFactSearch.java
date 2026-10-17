package org.example.wardrobe.memory.fake;

import java.util.ArrayList;
import java.util.List;

import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactSearch;

/** Отдаёт заданные факты пользователя, не больше {@code limit}; запоминает лимит и вызовы {@code compact}. */
public class FakeFactSearch implements FactSearch {

    public final List<Fact> results = new ArrayList<>();
    public Integer lastLimit;
    public int compactions;

    @Override
    public List<Fact> search(String userId, String query, int limit) {
        lastLimit = limit;
        return results.stream().filter(fact -> fact.userId().equals(userId)).limit(limit).toList();
    }

    @Override
    public void compact() {
        compactions++;
    }
}
