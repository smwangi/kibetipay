package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.wallet.WalletApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Agent-facing endpoints: originate loans, collect cash, watch the book. */
@RestController
class AgentLendingController {

    private final LoanOrigination origination;
    private final RepaymentService repayments;
    private final LoanQueryService queries;
    private final AgentApi agents;
    private final WalletApi wallets;

    AgentLendingController(LoanOrigination origination, RepaymentService repayments, LoanQueryService queries,
                           AgentApi agents, WalletApi wallets) {
        this.origination = origination;
        this.repayments = repayments;
        this.queries = queries;
        this.agents = agents;
        this.wallets = wallets;
    }

    @PostMapping("/api/agents/me/loans")
    ResponseEntity<LoanOrigination.Accepted> originate(@RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody OriginateRequest body) {
        var accepted = origination.originate(new LoanOrigination.Originate(CurrentUser.id(), body.borrower(),
                body.productCode(), Money.ofMajor(body.principal(), wallets.defaultCurrency()), key));
        return ResponseEntity.accepted().location(URI.create("/api/loans/" + accepted.loanId())).body(accepted);
    }

    /** Cash handed to the agent: the agent's float wallet is debited, the loan is credited. */
    @PostMapping("/api/agents/me/collections")
    RepaymentService.Receipt collect(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CollectionRequest body) {
        String me = CurrentUser.id();
        return repayments.repay(body.loanId(), me, RepaymentService.Channel.AGENT_CASH,
                Money.ofMajor(body.amount(), wallets.defaultCurrency()), "cash:" + me + ":" + key);
    }

    /** Roll-up of the agent's own loans plus every sub-agent below it. */
    @GetMapping("/api/agents/me/portfolio")
    LoanQueryService.Portfolio portfolio() {
        var me = agents.requireAgent(CurrentUser.id());
        return queries.portfolio(agents.subtree(me.id()), me.userId());
    }

    record OriginateRequest(
            @NotBlank @Size(max = 64) String borrower,
            @NotBlank String productCode,
            @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal principal) {}

    record CollectionRequest(@NotNull UUID loanId,
                             @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount) {}
}
