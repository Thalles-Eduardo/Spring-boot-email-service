package br.com.thalleseduardo.springemail.repository;

import br.com.thalleseduardo.springemail.model.EmailModel;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EmailRepository extends MongoRepository<EmailModel, String> {
}
