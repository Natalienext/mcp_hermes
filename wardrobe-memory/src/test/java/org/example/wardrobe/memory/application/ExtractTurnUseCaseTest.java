package org.example.wardrobe.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.wardrobe.memory.fake.Fixtures.add;

import java.util.List;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.Fact;
import org.example.wardrobe.memory.domain.FactStatus;
import org.example.wardrobe.memory.domain.Turn;
import org.example.wardrobe.memory.domain.TurnStatus;
import org.example.wardrobe.memory.fake.FakeExtractor;
import org.example.wardrobe.memory.fake.FakeFactRepository;
import org.example.wardrobe.memory.fake.FakeTurnRepository;
import org.example.wardrobe.memory.fake.Fixtures;
import org.junit.jupiter.api.Test;

class ExtractTurnUseCaseTest {

    private static final String USER_TEXT = "Я не ношу юбки и шорты, люблю синий";
    private static final String ASSISTANT_TEXT = "Понял: без юбок, ты сказала, что любишь оверсайз.";

    private final FakeTurnRepository turns = new FakeTurnRepository();
    private final FakeFactRepository facts = new FakeFactRepository();
    private final FakeExtractor extractor = new FakeExtractor();
    private final FactWriter writer = new FactWriter(facts, Fixtures.tx(), Fixtures.CLOCK);
    private ExtractTurnUseCase useCase = new ExtractTurnUseCase(turns, facts, extractor, writer);

    private Turn pending(String externalId, String userText) {
        Turn turn = turns.open(externalId, "me", userText).close(ASSISTANT_TEXT);
        turns.update(turn);
        return turn;
    }

    private Turn status(Turn turn) {
        return turns.findById(turn.id()).orElseThrow();
    }

    @Test
    void rejectsOneKeepsOthers() {
        Turn turn = pending("ext-1", USER_TEXT);
        extractor.decisions.addAll(List.of(
                add("skirts", "Не носит юбки", "не ношу юбки", "stated"),
                add("shorts", "Не носит шорты", "не носит шорты", "stated"),
                add("Color Blue", "Любит синий", "люблю синий", "stated"),
                add("color-blue", "Любит синий", "люблю синий", "stated")));

        useCase.process(turn.id());

        assertThat(facts.facts.values()).extracting(Fact::scope).containsExactly("skirts", "color-blue");
        assertThat(status(turn).status()).isEqualTo(TurnStatus.DONE);
        assertThat(status(turn).assistantText()).isEmpty();
    }

    @Test
    void quoteFromAssistantIsRejected() {
        Turn turn = pending("ext-1", USER_TEXT);
        extractor.decisions.add(add("oversize", "Любит оверсайз", "любишь оверсайз", "inferred"));

        useCase.process(turn.id());

        assertThat(facts.facts).isEmpty();
        assertThat(status(turn).status()).isEqualTo(TurnStatus.DONE);
    }

    @Test
    void candidatesAreAllActiveFacts() {
        Turn first = pending("ext-0", "я не ношу юбки, наверное люблю синий");
        writer.apply(first, add("skirts", "Не носит юбки", "я не ношу юбки", "stated"));
        writer.apply(first, add("color-blue", "Любит синий", "люблю синий", "inferred"));
        Turn turn = pending("ext-1", USER_TEXT);

        useCase.process(turn.id());

        ExtractionInput input = extractor.inputs.get(0);
        assertThat(input.userText()).isEqualTo(USER_TEXT);
        assertThat(input.assistantText()).isEqualTo(ASSISTANT_TEXT);
        assertThat(input.candidates()).extracting(ExtractionInput.Candidate::id, ExtractionInput.Candidate::standing)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("f-001", "stated"),
                        org.assertj.core.groups.Tuple.tuple("f-002", "inferred"));
    }

    @Test
    void rememberedFactIsSkippedOrRejected() {
        Turn turn = turns.open("ext-1", "me", "Запомни: я не ношу юбки");
        Fact remembered = new RememberUseCase(turns, writer)
                .remember("me", "constraint", "skirts", "Не носит юбки", "я не ношу юбки", null);
        turns.update(turn.close(ASSISTANT_TEXT));
        extractor.decisions.add(new ExtractorDecision("SKIP", remembered.id(), "", "", "", "", ""));
        extractor.decisions.add(add("skirts", "Не носит юбки", "я не ношу юбки", "stated"));

        useCase.process(turn.id());

        assertThat(facts.facts).containsOnlyKeys(remembered.id());
        assertThat(status(turn).status()).isEqualTo(TurnStatus.DONE);
    }

    @Test
    void extractorFailureMarksFailed() {
        Turn turn = pending("ext-1", USER_TEXT);
        extractor.failure = new ExtractionFailedException(ErrorCode.EXT_001, "extractor unavailable: timeout");

        useCase.process(turn.id());

        assertThat(status(turn).status()).isEqualTo(TurnStatus.FAILED);
        assertThat(status(turn).failReason()).isEqualTo("MEM-EXT-001: extractor unavailable: timeout");
        assertThat(facts.facts).isEmpty();
    }

    @Test
    void throwableMarksFailedInt001() {
        FakeFactRepository broken = new FakeFactRepository() {
            @Override
            public List<Fact> findActiveByUser(String userId) {
                throw new Error("disk I/O error");
            }
        };
        useCase = new ExtractTurnUseCase(turns, broken, extractor, writer);
        Turn turn = pending("ext-1", USER_TEXT);

        useCase.process(turn.id());

        assertThat(status(turn).status()).isEqualTo(TurnStatus.FAILED);
        assertThat(status(turn).failReason()).isEqualTo("MEM-INT-001: Error");
        assertThat(extractor.inputs).isEmpty();
    }

    @Test
    void forgetRequestedTurnIsNotExtracted() {
        Turn turn = turns.open("ext-1", "me", "Забудь, что я не ношу юбки").requestForget().close(ASSISTANT_TEXT);
        turns.update(turn);
        extractor.decisions.add(add("skirts", "Не носит юбки", "я не ношу юбки", "stated"));

        useCase.process(turn.id());

        assertThat(extractor.inputs).isEmpty();
        assertThat(facts.facts).isEmpty();
        assertThat(status(turn).status()).isEqualTo(TurnStatus.DONE);
    }

    @Test
    void notPendingTurnIsIgnored() {
        Turn open = turns.open("ext-1", "me", USER_TEXT);

        useCase.process(open.id());
        useCase.process("t-404");

        assertThat(extractor.inputs).isEmpty();
        assertThat(status(open).status()).isEqualTo(TurnStatus.OPEN);
    }

    @Test
    void supersedeFromExtractor() {
        Turn first = pending("ext-0", "я не ношу юбки");
        Fact old = writer.apply(first, add("skirts", "Не носит юбки", "я не ношу юбки", "stated")).orElseThrow();
        Turn turn = pending("ext-1", "теперь ношу длинные юбки");
        extractor.decisions.add(Fixtures.supersede(old.id(), "skirts", "Носит длинные юбки", "ношу длинные юбки"));

        useCase.process(turn.id());

        assertThat(facts.facts.get(old.id()).status()).isEqualTo(FactStatus.SUPERSEDED);
        assertThat(facts.findActive("me", "skirts").orElseThrow().supersedes()).isEqualTo(old.id());
    }
}
