package org.example.wardrobe.memory.web;

import org.example.wardrobe.memory.application.CloseTurnUseCase;
import org.example.wardrobe.memory.application.ForgetUseCase;
import org.example.wardrobe.memory.application.OpenTurnUseCase;
import org.example.wardrobe.memory.application.RecallUseCase;
import org.example.wardrobe.memory.application.RememberUseCase;
import org.example.wardrobe.memory.domain.FactRepository;
import org.example.wardrobe.memory.domain.RecallBlock;
import org.example.wardrobe.memory.domain.Turn;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemoryController {

    private final RecallUseCase recall;
    private final OpenTurnUseCase openTurn;
    private final CloseTurnUseCase closeTurn;
    private final RememberUseCase remember;
    private final ForgetUseCase forget;
    private final FactRepository facts;

    public MemoryController(RecallUseCase recall, OpenTurnUseCase openTurn, CloseTurnUseCase closeTurn,
            RememberUseCase remember, ForgetUseCase forget, FactRepository facts) {
        this.recall = recall;
        this.openTurn = openTurn;
        this.closeTurn = closeTurn;
        this.remember = remember;
        this.forget = forget;
        this.facts = facts;
    }

    /** Recall по телу запроса (D9): реплика не попадает в строку запроса и access-лог. */
    @PostMapping("/recall")
    public RecallResponse recall(@RequestBody RecallRequest request) {
        RecallBlock block = recall.recall(request.userId(), request.q(), request.limit());
        return new RecallResponse(block.context(), block.ids());
    }

    @PostMapping("/turns")
    @ResponseStatus(HttpStatus.CREATED)
    public OpenTurnResponse openTurn(@RequestBody OpenTurnRequest request) {
        OpenTurnUseCase.Opened opened = openTurn.open(request.userId(), request.sessionId(), request.externalId(),
                request.userText());
        return new OpenTurnResponse(opened.turn().id(), opened.turn().status().value(), opened.abandoned());
    }

    /** Возвращается сразу, экстракция — в очереди (AC-15). */
    @PostMapping("/turns/close")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CloseTurnResponse closeTurn(@RequestBody CloseTurnRequest request) {
        Turn turn = closeTurn.close(request.userId(), request.externalId(), request.assistantText());
        return new CloseTurnResponse(turn.id(), turn.status().value());
    }

    @PostMapping("/facts")
    @ResponseStatus(HttpStatus.CREATED)
    public FactView remember(@RequestBody RememberRequest request) {
        return FactView.from(remember.remember(request.userId(), request.kind(), request.scope(), request.fact(),
                request.quote(), request.supersedes()));
    }

    @PostMapping("/facts/{id}/forget")
    public ForgetResponse forget(@PathVariable String id, @RequestBody ForgetRequest request) {
        return ForgetResponse.from(forget.forget(request.userId(), id));
    }

    /** Действующие факты, {@code stated} и {@code inferred} (AC-9). */
    @GetMapping("/facts")
    public FactsResponse list(@RequestParam("user_id") String userId) {
        Turn.checkUserId(userId);
        return new FactsResponse(facts.findActiveByUser(userId).stream().map(FactView::from).toList());
    }
}
