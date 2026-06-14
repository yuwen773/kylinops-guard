package com.kylinops.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ActionConfirmController.class)
@WithMockUser
@DisplayName("ActionConfirmController - strict confirmation API")
class ActionConfirmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ActionConfirmService actionConfirmService;

    @Test
    void confirmationReturnsExecutionResult() throws Exception {
        PendingAction action = new PendingAction();
        action.setActionId("test-action-id");
        action.setStatus(PendingActionStatus.SUCCESS);
        action.setExecutionResult("{\"summary\":\"restart complete\"}");
        when(actionConfirmService.confirmAction("test-action-id", true)).thenReturn(action);

        mockMvc.perform(post("/api/actions/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionId\":\"test-action-id\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.executionResult").value("{\"summary\":\"restart complete\"}"));

        verify(actionConfirmService).confirmAction("test-action-id", true);
    }

    @Test
    void cancellationUsesSameAtomicServiceEntryPoint() throws Exception {
        PendingAction action = new PendingAction();
        action.setActionId("test-action-id");
        action.setStatus(PendingActionStatus.CANCELLED);
        when(actionConfirmService.confirmAction("test-action-id", false)).thenReturn(action);

        mockMvc.perform(post("/api/actions/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionId\":\"test-action-id\",\"confirm\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(actionConfirmService).confirmAction("test-action-id", false);
    }

    @Test
    void rejectsUnknownCommandToolAndParamsFields() throws Exception {
        mockMvc.perform(post("/api/actions/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "actionId":"test-id",
                                  "confirm":true,
                                  "command":"rm -rf /",
                                  "toolName":"evil_tool",
                                  "actionType":"unsafe_action",
                                  "params":{"serviceName":"sshd"}
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingActionIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/actions/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingConfirmReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/actions/confirm")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionId\":\"test-id\"}"))
                .andExpect(status().isBadRequest());
    }
}
