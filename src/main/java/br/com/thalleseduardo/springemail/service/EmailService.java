package br.com.thalleseduardo.springemail.service;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.repository.EmailRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmailService {

    @Autowired
    private EmailRepository emailRepository;

    @Autowired
    private JavaMailSender emailSender;

    public EmailModel sendEmail(EmailModel emailModel) {
        emailModel.setSendDateEmail(LocalDateTime.now());

        Long lastEmailId = getLastEmailId();
        Long emailId = lastEmailId + 1;
        emailModel.setEmailId(emailId);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailModel.getEmailFrom());
            message.setTo(emailModel.getEmailTo());
            message.setSubject(emailModel.getSubject());
            message.setText(emailModel.getText());
            emailSender.send(message);

            emailModel.setStatusEmail(StatusEmail.SENT);
        } catch (MailException e){
            emailModel.setStatusEmail(StatusEmail.ERROR);
        }
        return emailRepository.save(emailModel);
    }

    private Long getLastEmailId(){
        List<EmailModel> allEmails = emailRepository.findAll();
        if(!allEmails.isEmpty()){
            EmailModel lastEmail = allEmails.get(allEmails.size() - 1); // pega o último e-mail
            return lastEmail.getEmailId();
        }
        return 0L;
    }
}
