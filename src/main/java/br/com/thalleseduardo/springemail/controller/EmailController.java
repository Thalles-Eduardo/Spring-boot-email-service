package br.com.thalleseduardo.springemail.controller;

import br.com.thalleseduardo.springemail.dto.EmailDto;
import br.com.thalleseduardo.springemail.producer.EmailProducer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailController {

    private final EmailProducer emailProducer;

    public EmailController(EmailProducer emailProducer) {
        this.emailProducer = emailProducer;
    }

    @PostMapping("/sending-email")
    public ResponseEntity<Void> sendingEmail(@RequestBody @Valid EmailDto emailDto) {
        emailProducer.publish(emailDto);
        return new ResponseEntity<>(HttpStatus.ACCEPTED);
    }
}

