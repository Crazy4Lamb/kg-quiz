package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.controller.LlmConfigController;
import com.kgquiz.quiz.llm.LlmAuth;
import com.kgquiz.quiz.llm.LlmProtocol;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmDraftConnectionTest {
    @AfterEach void clear() { UserContext.clear(); }

    @Test void connectionTestActuallySendsDraftEndpointModelAndKey() throws Exception {
        var body = new AtomicReference<String>(); var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/draft/chat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"draft-ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client = spy(new LlmClient(new KgProperties(), mock(ObjectProvider.class)));
            var saved = new LlmClient.LlmSettings(false, LlmProtocol.OPENAI, LlmAuth.BEARER,
                    "http://127.0.0.1:1", "/saved/chat", "saved-test-key", null, "saved-model",
                    5, 2400, 0.3, null, "test");
            doReturn(saved).when(client).settings();
            var controller = new LlmConfigController(client, mock(LlmConfigService.class));
            UserContext.set(new CurrentUser(1L, "test", UserRole.ADMIN, "Test", null, null, null, List.of()));
            var result = controller.test(Map.of("protocol", "OPENAI", "auth", "BEARER",
                    "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(), "chatPath", "/draft/chat",
                    "model", "draft-model", "apiKey", "draft-test-key")).getData();
            assertEquals(true, result.get("ok")); assertEquals("draft-ok", result.get("reply"));
            assertTrue(body.get().contains("draft-model")); assertFalse(body.get().contains("saved-model"));
            assertEquals("Bearer draft-test-key", authorization.get());
        } finally { server.stop(0); }
    }

    @Test void teacherCannotRedirectSavedKeyThroughDraft() {
        UserContext.set(new CurrentUser(1L, "test", UserRole.TEACHER, "Test", null, 1L, 2L, List.of()));
        var client = mock(LlmClient.class);
        var controller = new LlmConfigController(client, mock(LlmConfigService.class));
        assertThrows(ForbiddenException.class, () -> controller.test(Map.of("baseUrl", "http://localhost")));
        verifyNoInteractions(client);
    }

    @Test void errorsNeverEchoQueryCredentials() {
        String result = LlmClient.redact("GET https://test.local?key=secret&token=another failed: secret", "secret");
        assertFalse(result.contains("secret")); assertFalse(result.contains("another"));
        assertTrue(result.contains("key=****"));
    }
}
