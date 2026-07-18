package br.com.thalleseduardo.springemail.model;

import java.time.LocalDateTime;

import br.com.thalleseduardo.springemail.enums.StatusEmail;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "emails")
public class EmailModel {

    @Id
    private String emailId;

    private String ownerRef;

    private String emailFrom;

    private String emailTo;

    private String subject;

    private String text;

    private LocalDateTime sendDateEmail;

    private StatusEmail statusEmail;

}
