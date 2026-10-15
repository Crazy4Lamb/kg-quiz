package com.kgquiz.quiz.service;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.entity.LlmConfig;
import com.kgquiz.quiz.mapper.LlmConfigMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LlmConfigServiceTest {
    private final LlmConfigMapper mapper = mock(LlmConfigMapper.class);
    private final LlmConfigService service = new LlmConfigService(mapper);
    @AfterEach void clear() { UserContext.clear(); }

    @Test void soleForeignConfigurationIsNeverUsed() {
        login(1L); when(mapper.selectList(any())).thenReturn(List.of(config(2L, "foreign")));
        assertNull(service.resolveSettings());
    }
    @Test void noMatchingRowFallsBackToEnvironment() {
        login(1L); when(mapper.selectList(any())).thenReturn(List.of(config(2L, "other"), config(3L, "third")));
        assertNull(service.resolveSettings());
    }
    @Test void ownConfigurationWinsOverPlatform() {
        login(1L); when(mapper.selectList(any())).thenReturn(List.of(config(null, "platform"), config(1L, "own")));
        assertEquals("own", service.resolveSettings().model());
    }
    @Test void backgroundCallsOnlyUsePlatformConfiguration() {
        when(mapper.selectList(any())).thenReturn(List.of(config(1L, "foreign"), config(null, "platform")));
        assertEquals("platform", service.resolveSettings().model());
    }
    private void login(Long org) {
        UserContext.set(new CurrentUser(1L, "test", UserRole.TEACHER, "Test", null, org, null, List.of()));
    }
    private LlmConfig config(Long org, String model) {
        var c = new LlmConfig(); c.setOrgId(org); c.setModel(model); c.setEnabled(true);
        c.setProtocol("OPENAI"); c.setAuth("NONE"); c.setBaseUrl("http://localhost"); return c;
    }
}
