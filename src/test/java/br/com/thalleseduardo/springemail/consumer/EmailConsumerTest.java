package br.com.thalleseduardo.springemail.consumer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailConsumerTest {

    @Mock
    private EmailService emailService;

    @Test
    void listen_copiesDtoFieldsAndDelegatesToService() {
        EmailConsumer consumer = new EmailConsumer(emailService);
        EmailDto dto = new EmailDto();
        dto.setOwnerRef("owner-1");
        dto.setEmailTo("dest@example.com");
        dto.setSubject("Hi");
        dto.setText("Body");

        EmailModel saved = new EmailModel();
        saved.setStatusEmail(StatusEmail.SENT);
        when(emailService.sendEmail(any(EmailModel.class))).thenReturn(saved);

        consumer.listen(dto);

        ArgumentCaptor<EmailModel> captor = ArgumentCaptor.forClass(EmailModel.class);
        verify(emailService).sendEmail(captor.capture());
        assertThat(captor.getValue().getOwnerRef()).isEqualTo("owner-1");
        assertThat(captor.getValue().getEmailTo()).isEqualTo("dest@example.com");
        assertThat(captor.getValue().getSubject()).isEqualTo("Hi");
        assertThat(captor.getValue().getText()).isEqualTo("Body");
    }
}
