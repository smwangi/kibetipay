package com.samwan.kibetipay.assistant.internal;

import com.samwan.kibetipay.insights.InsightsApi;
import com.samwan.kibetipay.lending.LendingQueries;
import com.samwan.kibetipay.transfer.TransferQueries;
import com.samwan.kibetipay.wallet.WalletApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Customer-support assistant = RAG (policies from pgvector) + tools (the user's own account data)
 * + conversation memory. The advisor chain runs in order: memory ─▶ retrieval ─▶ model ─▶ tools.
 */
@Service
class SupportAssistant {

    private static final String SYSTEM = """
            You are KibetiPay's support assistant for a Kenyan mobile wallet.
            - Answer policy questions (fees, limits, disputes, security) ONLY from the provided context.
              If the context does not cover it, say you don't know and suggest contacting support.
            - For questions about the user's own balance, transfers, spending or loans, call the tools.
            - You cannot send, reverse or approve payments. Explain how the user can do it themselves.
            - Never ask for or repeat PINs, passwords or full card numbers.
            - Amounts are in KES. Be concise.
            """;

    private final ChatClient chat;
    private final WalletApi wallets;
    private final TransferQueries transfers;
    private final InsightsApi insights;
    private final LendingQueries loans;

    SupportAssistant(ChatClient.Builder builder, VectorStore vectorStore, ChatMemory memory,
                     AssistantProperties props, WalletApi wallets, TransferQueries transfers, InsightsApi insights,
                     LendingQueries loans) {
        this.wallets = wallets;
        this.transfers = transfers;
        this.insights = insights;
        this.loans = loans;
        this.chat = builder
                .defaultSystem(SYSTEM)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .topK(props.topK())
                                        .similarityThreshold(props.similarityThreshold())
                                        .build())
                                .build(),
                        new SimpleLoggerAdvisor())
                .build();
    }

    String ask(String ownerId, String question) {
        return chat.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, ownerId))
                .tools(new AccountTools(ownerId, wallets, transfers, insights, loans))
                .call()
                .content();
    }
}
