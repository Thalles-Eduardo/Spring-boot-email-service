package br.com.thalleseduardo.springemail.consumer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import br.com.thalleseduardo.springemail.model.EmailModel;
import br.com.thalleseduardo.springemail.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.BeanUtils;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class EmailConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailConsumer.class);

    private final EmailService emailService;

    public EmailConsumer(EmailService emailService) {
        this.emailService = emailService;
    }

    @RabbitListener(queues = "${spring.rabbitmq.queue}")
    public void listen(@Payload EmailDto emailDto) {
        EmailModel emailModel = new EmailModel();
        BeanUtils.copyProperties(emailDto, emailModel);
        emailModel = emailService.sendEmail(emailModel);
        log.info("Email status: {}", emailModel.getStatusEmail());
    }
}
