package org.example.wardrobe.memory.domain;

import java.util.List;

public interface FactSearch {

    /** Действующие {@code stated} факты пользователя по словам реплики, не больше {@code limit}. */
    List<Fact> search(String userId, String query, int limit);

    /** Сжать полнотекстовый индекс после «забудь» (I9). */
    void compact();
}
