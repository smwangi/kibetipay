package com.samwan.kibetipay.transfer.internal.web;

import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.transfer.TransferQueries.TransferView;
import com.samwan.kibetipay.transfer.internal.command.TransferReviewHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Analyst review queue. Secured to ROLE_ADMIN by the security filter chain (/api/admin/**). */
@RestController
@RequestMapping("/api/admin/transfers")
class TransferAdminController {

    private final TransferQueries queries;
    private final TransferReviewHandler review;

    TransferAdminController(TransferQueries queries, TransferReviewHandler review) {
        this.queries = queries;
        this.review = review;
    }

    @GetMapping("/review-queue")
    List<TransferView> queue() {
        return queries.inReview(50);
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(@PathVariable UUID id) {
        review.approve(id);
    }

    @PostMapping("/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@PathVariable UUID id, @Valid @RequestBody DeclineRequest body) {
        review.decline(id, body.reason());
    }

    record DeclineRequest(@NotBlank String reason) {}
}
