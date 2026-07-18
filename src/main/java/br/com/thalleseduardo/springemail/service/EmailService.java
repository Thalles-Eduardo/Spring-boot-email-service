package br.com.thalleseduardo.springemail.service;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.repository.EmailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final EmailRepository emailRepository;
    private final JavaMailSender emailSender;
    private final String mailFrom;

    public EmailService(EmailRepository emailRepository,
                         JavaMailSender emailSender,
                         @Value("${app.mail.from}") String mailFrom) {
        this.emailRepository = emailRepository;
        this.emailSender = emailSender;
        this.mailFrom = mailFrom;
    }

    public EmailModel sendEmail(EmailModel emailModel) {
        emailModel.setSendDateEmail(LocalDateTime.now());
        emailModel.setEmailFrom(mailFrom);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(emailModel.getEmailTo());
            message.setSubject(emailModel.getSubject());
            message.setText(emailModel.getText());
            emailSender.send(message);

            emailModel.setStatusEmail(StatusEmail.SENT);
        } catch (MailException e) {
            log.error("Failed to send email to {}", emailModel.getEmailTo(), e);
            emailModel.setStatusEmail(StatusEmail.ERROR);
        }
        return emailRepository.save(emailModel);
    }
}
