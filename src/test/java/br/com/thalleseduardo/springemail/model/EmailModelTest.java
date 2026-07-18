package br.com.thalleseduardo.springemail.model;

import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class EmailModelTest {

    @Test
    void emailId_isAnnotatedWithSpringDataIdAndIsAString() throws NoSuchFieldException {
        Field field = EmailModel.class.getDeclaredField("emailId");
        assertThat(field.isAnnotationPresent(Id.class)).isTrue();
        assertThat(field.getType()).isEqualTo(String.class);
    }
}
