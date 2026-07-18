package br.com.thalleseduardo.springemail.producer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String queue;

    public EmailProducer(RabbitTemplate rabbitTemplate,
                          @Value("${spring.rabbitmq.queue}") String queue) {
        this.rabbitTemplate = rabbitTemplate;
        this.queue = queue;
    }

    public void publish(EmailDto emailDto) {
        rabbitTemplate.convertAndSend(queue, emailDto);
    }
}
