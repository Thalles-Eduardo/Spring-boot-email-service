package br.com.thalleseduardo.springemail.producer;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailProducerTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publish_sendsDtoToConfiguredQueue() {
        EmailProducer producer = new EmailProducer(rabbitTemplate, "email-queue");
        EmailDto dto = new EmailDto();
        dto.setOwnerRef("owner-1");
        dto.setEmailTo("dest@example.com");
        dto.setSubject("Hi");
        dto.setText("Body");

        producer.publish(dto);

        verify(rabbitTemplate).convertAndSend("email-queue", dto);
    }
}
