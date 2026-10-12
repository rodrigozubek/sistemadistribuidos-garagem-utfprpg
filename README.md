# Sistema de Garagem

Projeto Java com comunicação cliente/servidor por TCP e persistência em SQLite. Atualmente, o sistema permite cadastrar usuários, fazer login e logout, consultar os próprios dados, alterar nome e senha e excluir a conta.

## Tecnologias

- Java 26
- Maven
- Gson
- SQLite JDBC
- JUnit 5

## Executar

Com o JDK 26 e o Maven configurados no PATH, compile o projeto:

```powershell
mvn package
```

Inicie o servidor:

```powershell
java --enable-native-access=ALL-UNNAMED -cp 'target/classes;target/lib/*' server.Server 5000
```

Em outro terminal, inicie o cliente:

```powershell
java --enable-native-access=ALL-UNNAMED -cp 'target/classes;target/lib/*' client.Client 127.0.0.1 5000
```

A porta pode ser alterada, desde que seja a mesma no cliente e no servidor. Para conectar de outra máquina, informe o endereço IP do servidor.

No NetBeans, abra o projeto Maven e execute `server.Server` e depois `client.Client`. Sem argumentos, as classes solicitam a porta e o endereço pelo console. A classe principal `SistemaGaragem` inicia o cliente por padrão e também aceita os argumentos `server 5000` ou `client 127.0.0.1 5000`.

## Comunicação

As mensagens são enviadas em JSON, com codificação UTF-8 e uma mensagem por linha. Cada operação abre uma conexão TCP, que é encerrada após a resposta.

Formato da requisição:

```json
{"method":"login","data":{"username":"usuario","password":"Senha123!"}}
```

Formato da resposta:

```json
{"statusCode":200,"message":"Sucesso no Login","data":{"token":"uuid"}}
```

Toda resposta contém `statusCode`, `message` e `data`. Quando não há dados de retorno, `data` recebe `null`.

| Operação | Campos em `data` | Retorno de sucesso |
| --- | --- | --- |
| `register` | name, username, password | 201 |
| `login` | username, password | 200, token |
| `logout` | token | 200 |
| `getuser` | token, username | 200, name e username |
| `updateusername` | token, username, name | 200 |
| `updateuserpassword` | token, username, oldPassword, newPassword | 200 |
| `deleteuser` | token, username | 200 |

`updateusername` altera o nome (`name`), mantendo o identificador (`username`). As operações autenticadas permitem consultar e modificar somente a própria conta.

Os códigos de erro utilizados são 400 para requisição inválida, 401 para falha de autenticação ou autorização, 404 para usuário não encontrado, 409 para username já cadastrado e 500 para erro interno.

## Validação

- **Nome:** de 1 a 60 caracteres, apenas letras e espaços. Nomes compostos somente por espaços são rejeitados.
- **Username:** de 3 a 20 caracteres, com letras minúsculas sem acentos, números, ponto e sublinhado.
- **Senha:** de 8 a 20 caracteres, com pelo menos uma letra maiúscula, uma minúscula, um número e um símbolo. Os símbolos permitidos são `# . * & % $ @ ! ( ) - _ = +`.

## Banco de dados

O banco é criado automaticamente em `data/garage.db`. A tabela `users` armazena `username`, `name` e `password`, com `username` como chave primária. As gravações são confirmadas antes da atualização dos dados em memória.

Se houver um arquivo `data/users.json`, seus usuários são importados na primeira inicialização do banco. A migração é feita em uma transação, preserva o arquivo original e ocorre uma única vez. Cadastros antigos continuam aceitando suas senhas atuais; novos cadastros e mudanças de senha seguem as regras de validação.

O caminho do banco pode ser configurado com `-Dgarage.databaseFile=caminho/garage.db`. A origem da migração pode ser definida com `-Dgarage.legacyUsersFile=caminho/users.json`; a propriedade antiga `garage.usersFile` também é aceita como origem JSON. Essas opções devem ser passadas antes de `-cp`.

Use uma única instância do servidor por banco. As sessões ficam em memória e são encerradas ao reiniciar o servidor. As senhas são armazenadas em texto simples e o transporte TCP não utiliza TLS. Os logs ocultam senhas e tokens.

Os diretórios `data/` e `target/` estão no `.gitignore`.

## Testes

```powershell
mvn test
```

Os testes verificam o ciclo de cadastro e autenticação, validações, autorização, persistência, migração, falhas de gravação e comunicação TCP. Cada teste de persistência utiliza um banco temporário.

## Escopo atual

O sistema implementa o gerenciamento da própria conta. Funcionalidades de administrador, veículos e vagas ainda não estão implementadas.
