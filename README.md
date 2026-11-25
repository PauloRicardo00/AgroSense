# AgroSense

O AgroSense acompanha a telemetria de máquinas agrícolas e organiza os atendimentos de manutenção. Pelo painel, a equipe consulta leituras e alertas e acompanha as ordens de serviço. O operador solicita o atendimento, o gestor autoriza e define a prioridade, e o técnico registra o serviço. O gestor também avalia o parecer e aprova o encerramento ou devolve a ordem para ajuste.

Para testar o sistema, o fluxo do Node-RED simula 12 máquinas. Ele publica as leituras via MQTT. O backend recebe os dados, usa um modelo ONNX na análise e grava as leituras no PostgreSQL. O painel consulta os dados pela API.

| Pasta | O que contém |
| --- | --- |
| [`frontend/`](frontend/) | Interface do AgroSense e servidor local em Node.js |
| [`backend/`](backend/) | API Spring Boot, migrações e modelo ONNX |
| [`database/`](database/) | Esquema inicial do PostgreSQL e cadastro da frota simulada |
| [`node-red/`](node-red/) | Fluxo de simulação de telemetria |

## Como rodar no Windows

Você vai precisar de Java 21, Node.js, PostgreSQL e um broker MQTT local na porta 1883, como o Mosquitto. Para gerar leituras de teste, use também o Node-RED. A instalação verificada usa PostgreSQL 18.

### Banco de dados

Em uma instalação nova, crie o banco `agrosense`, defina a conta PostgreSQL `agrosense` como proprietária e configure uma senha para ela. Execute os scripts abaixo no banco novo, na ordem indicada.

1. [`database/01_schema.sql`](database/01_schema.sql)
2. [`database/02_ordens_servico.sql`](database/02_ordens_servico.sql)
3. [`database/03_consultas.sql`](database/03_consultas.sql)
4. Os quatro scripts de [`backend/sql/`](backend/sql/), na ordem de `01` a `04`.

Se o banco já estiver em uso, aplique apenas as migrações pendentes. Para usar o simulador, execute [`database/04_frota_simulada.sql`](database/04_frota_simulada.sql) depois das migrações.

O login exige uma conta ativa. Na primeira instalação, cadastre o administrador inicial com um hash de senha Argon2id compatível com [`SenhasConfig.java`](backend/src/main/java/br/agrosense/backend/seguranca/SenhasConfig.java). Depois, use a API administrativa para cadastrar as demais contas.

### Backend e frontend

Abra o PowerShell na raiz do repositório e inicie o backend. Digite a senha da conta PostgreSQL `agrosense` quando o script pedir. A API usa a porta 8080.

```powershell
.\backend\iniciar.ps1
```

Em outro terminal, também na raiz do repositório, inicie o frontend.

```powershell
.\frontend\iniciar-front.ps1
```

Mantenha os dois terminais abertos e acesse a [tela de login do AgroSense](http://localhost:5500/login.html).

### Simular leituras

Com o broker MQTT rodando, importe [`node-red/fluxo.json`](node-red/fluxo.json) no Node-RED, faça o deploy e clique em **INICIAR 12 maquinas**. O simulador envia uma leitura por máquina a cada cinco segundos pelo tópico `planta/maquina1/dados`. Depois de reiniciar o Node-RED, clique em **INICIAR 12 maquinas** novamente. Para conferir a chegada de dados, acompanhe o horário da última leitura no Dashboard.
