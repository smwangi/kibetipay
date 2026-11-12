package com.samwan.kibetipay.assistant.internal;

import com.samwan.kibetipay.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AssistantController {

    private final SupportAssistant assistant;
    private final KnowledgeIngestor ingestor;

    AssistantController(SupportAssistant assistant, KnowledgeIngestor ingestor) {
        this.assistant = assistant;
        this.ingestor = ingestor;
    }

    @PostMapping("/api/assistant/ask")
    Answer ask(@Valid @RequestBody Question body) {
        return new Answer(assistant.ask(CurrentUser.id(), body.question()));
    }

    @PostMapping("/api/admin/assistant/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Reindexed reindex() {
        return new Reindexed(ingestor.ingest());
    }

    record Question(@NotBlank @Size(max = 1000) String question) {}

    record Answer(String answer) {}

    record Reindexed(int chunks) {}
}
