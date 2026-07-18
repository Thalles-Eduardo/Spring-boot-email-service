package br.com.thalleseduardo.springemail.service;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.repository.EmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    private static final String CONFIGURED_FROM = "no-reply@example.com";

    @Mock
    private EmailRepository emailRepository;

    @Mock
    private JavaMailSender emailSender;

    @Test
    void sendEmail_success_setsStatusSentAndForcesConfiguredFromAddress() {
        EmailService emailService = new EmailService(emailRepository, emailSender, CONFIGURED_FROM);
        EmailModel emailModel = new EmailModel();
        emailModel.setEmailFrom("attacker@evil.com");
        emailModel.setEmailTo("dest@example.com");
        emailModel.setSubject("Hi");
        emailModel.setText("Body");
        when(emailRepository.save(any(EmailModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailModel result = emailService.sendEmail(emailModel);

        assertThat(result.getStatusEmail()).isEqualTo(StatusEmail.SENT);
        assertThat(result.getEmailFrom()).isEqualTo(CONFIGURED_FROM);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo(CONFIGURED_FROM);
    }

    @Test
    void sendEmail_mailException_setsStatusErrorAndStillSaves() {
        EmailService emailService = new EmailService(emailRepository, emailSender, CONFIGURED_FROM);
        EmailModel emailModel = new EmailModel();
        emailModel.setEmailTo("dest@example.com");
        emailModel.setSubject("Hi");
        emailModel.setText("Body");
        doThrow(new MailSendException("smtp down")).when(emailSender).send(any(SimpleMailMessage.class));
        when(emailRepository.save(any(EmailModel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmailModel result = emailService.sendEmail(emailModel);

        assertThat(result.getStatusEmail()).isEqualTo(StatusEmail.ERROR);
        verify(emailRepository).save(emailModel);
    }
}
