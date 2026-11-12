package com.samwan.kibetipay.agent.internal;

import com.samwan.kibetipay.agent.AgentApi;
import com.samwan.kibetipay.agent.AgentApi.AgentView;
import com.samwan.kibetipay.agent.AgentApi.NewAgent;
import com.samwan.kibetipay.shared.money.Money;
import com.samwan.kibetipay.shared.security.CurrentUser;
import com.samwan.kibetipay.wallet.WalletApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AgentController {

    private final AgentApi agents;
    private final AgentDirectory directory;
    private final WalletApi wallets;

    AgentController(AgentApi agents, AgentDirectory directory, WalletApi wallets) {
        this.agents = agents;
        this.directory = directory;
        this.wallets = wallets;
    }

    /** Platform admin onboards a top-level agent. */
    @PostMapping("/api/admin/agents")
    @ResponseStatus(HttpStatus.CREATED)
    AgentView registerTopAgent(@Valid @RequestBody AgentRequest body) {
        return agents.registerTopAgent(body.toNewAgent(wallets.defaultCurrency()));
    }

    /** An agent recruits a sub-agent under itself. */
    @PostMapping("/api/agents/me/sub-agents")
    @ResponseStatus(HttpStatus.CREATED)
    AgentView recruit(@Valid @RequestBody AgentRequest body) {
        return agents.registerSubAgent(CurrentUser.id(), body.toNewAgent(wallets.defaultCurrency()));
    }

    @GetMapping("/api/agents/me")
    AgentView me() {
        return agents.requireAgent(CurrentUser.id());
    }

    @GetMapping("/api/agents/me/network")
    List<AgentView> network() {
        return directory.descendants(agents.requireAgent(CurrentUser.id()).id());
    }

    record AgentRequest(
            @NotBlank @Size(max = 64) String userId,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank @Size(max = 64) String region,
            @NotNull @DecimalMin("0.00") BigDecimal lendingLimit) {

        NewAgent toNewAgent(String currency) {
            return new NewAgent(userId, displayName, region, Money.ofMajor(lendingLimit, currency));
        }
    }
}
