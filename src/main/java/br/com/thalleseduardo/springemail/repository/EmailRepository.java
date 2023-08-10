package br.com.thalleseduardo.springemail.repository;

import br.com.thalleseduardo.springemail.model.EmailModel;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;

public interface EmailRepository extends MongoRepository<EmailModel, Long> {
}
