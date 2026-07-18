<h1 align="center">
  E-MAIL API 
</h1>

<p align="center">
 <img src="https://img.shields.io/static/v1?label=License&message=MIT&color=8257E5&labelColor=000000" alt="License" />
</p>

Criei um projeto que utiliza uma API externa de email para facilitar o envio de mensagens por email, 
integrando-a com o RabbitMQ para otimizar a comunicação e o processamento das solicitações de envio.


## Tecnologias

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Spring MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)
- [Spring Data MongoDB](https://spring.io/projects/spring-data-mongodb)
- [MongoDB](https://www.mongodb.com/pt-br)
- [RabbitMQ](https://www.rabbitmq.com)
- [Swagger](https://swagger.io)

## Práticas adotadas

- SOLID, DRY, YAGNI, KISS
- API REST
- Consultas com Spring Data MongoDB
- Injeção de Dependências
- Processo de Mensageria
- Documentação

## Como Executar

- Clonar repositório git
```bash
git clone https://github.com/Thalles-Eduardo/Spring-boot-email-service
```
- Executar a aplicação:
```bash
./mvnw spring-boot:run
```

## Configuração

Copie `src/main/resources/application.yaml.example` para `src/main/resources/application.yaml` e
preencha com suas credenciais (MongoDB, RabbitMQ, SMTP). Esse arquivo é ignorado pelo git — nunca
commite credenciais reais.

## API Endpoints

Para fazer as requisições HTTP abaixo, foi utilizada a ferramenta [Postman](https://www.postman.com/downloads/) ou pelo [Swagger](http://localhost:8080/swagger-ui/index.html).

Todas as requisições exigem o header `X-API-KEY` com o valor configurado em `app.security.api-key`.
O envio agora é assíncrono: o endpoint responde `202 Accepted` imediatamente e o e-mail é processado
por um consumer RabbitMQ.

- Enviar e-mail

![Enviar e-mail](https://github.com/Thalles-Eduardo/Spring-boot-email-service/assets/69612509/c3d23d90-6d80-4f55-a309-ca0f8ffaa49a)

## Notas de Release

**2026-07-18 — Refactor de segurança, performance e correção** ([diff completo](https://github.com/Thalles-Eduardo/Spring-boot-email-service/compare/407e220...52b054c))

- 🔒 Fechado um open-relay: `emailFrom` não é mais controlado pelo cliente da API; o endereço de
  envio agora é fixo, configurado no servidor (`app.mail.from`)
- 🔒 Endpoint `/sending-email` agora exige o header `X-API-KEY` e tem rate limiting por IP
- 🐛 Corrigido `EmailModel`: o campo de id usava a anotação `@Id` do JPA, que o Spring Data MongoDB
  não reconhece — a persistência agora usa `String`/`ObjectId` nativo do Mongo
- 🐛 Removida a geração manual de id (`findAll()` a cada envio) — O(n) por requisição e sujeita a
  race condition entre requisições concorrentes
- ⚡ `EmailController` agora publica no RabbitMQ e responde `202 Accepted` imediatamente, em vez de
  bloquear a resposta HTTP esperando o envio SMTP terminar
- ⚡ Adicionada fila de dead-letter para mensagens que falham repetidamente
- 🧹 Spring Boot atualizado para 3.5.16, dependência `spring-boot-starter-data-jpa` (não usada)
  removida, logging migrado para SLF4J, injeção por construtor
- ✅ 13 novos testes automatizados

Detalhes completos: [spec de design](docs/superpowers/specs/2026-07-18-email-service-refactor-design.md)
e [plano de implementação](docs/superpowers/plans/2026-07-18-email-service-refactor.md).

# Author

Thalles Eduardo Dias da Silva

- [Linkedin](https://linkedin.com/in/thalles-eduardo-7297a6237)
