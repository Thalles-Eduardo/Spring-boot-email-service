package br.com.thalleseduardo.springemail.controller;

import br.com.thalleseduardo.springemail.producer.EmailProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EmailControllerTest {

    @Mock
    private EmailProducer emailProducer;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailController(emailProducer)).build();
    }

    private record TestPayload(String ownerRef, String emailTo, String subject, String text) {
    }

    @Test
    void sendingEmail_validPayload_returns202AndPublishes() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(
                new TestPayload("owner-1", "dest@example.com", "Hi", "Body"));

        mockMvc.perform(post("/sending-email")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isAccepted());

        verify(emailProducer).publish(any());
    }

    @Test
    void sendingEmail_missingRequiredField_returns400AndDoesNotPublish() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(
                new TestPayload("", "dest@example.com", "Hi", "Body"));

        mockMvc.perform(post("/sending-email")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailProducer);
    }
}
