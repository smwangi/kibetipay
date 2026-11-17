package com.samwan.kibetipay.mobilemoney.internal;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.samwan.kibetipay.wallet.WalletApi;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
class CollectionControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CollectionService collections;
    @MockitoBean WalletApi wallets;

    @ParameterizedTest
    @ValueSource(strings = {"short", "invalid/key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidIdempotencyKeyReturnsBadRequest(String key) throws Exception {
        mvc.perform(post("/api/mobile-money/collections")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"254712345678","amount":100.00}
                                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(collections, wallets);
    }
}
