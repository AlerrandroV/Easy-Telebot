# Projeto: app Android para criar e rodar bots do Telegram no próprio celular

> Documento de partida do projeto. Explica a ideia, as decisões já tomadas e
> por onde começar. Nome do app ainda a definir (por enquanto: **o app**).

## 1. A ideia

**Democratizar os bots do Telegram.** Qualquer pessoa, sem saber programar,
deve conseguir criar um bot e deixá-lo rodando **no próprio celular**, sem
servidor, sem domínio e sem mensalidade.

Exemplo de uso: a pessoa cola o token do bot, cria o comando `/horario` com a
resposta `Olá, {nome}! Funcionamos de 8h às 18h.` e pronto: o bot responde no
Telegram enquanto o celular estiver ligado e com internet.

### Princípios

1. **Sem código no uso comum.** Tudo o que for possível se faz por telas e
   formulários. Programar é opcional (modo avançado, no futuro).
2. **Erros sempre explicados.** Nada de mensagem técnica solta: todo erro diz o
   que aconteceu e como resolver, com passo a passo quando fizer sentido
   (token errado, sem internet, bot sem permissão no grupo, celular matando o
   app etc.). Mesma filosofia do projeto BTC Signal (bot em Bash do mesmo autor).
3. **Simples e leve.** Poucas dependências, pouco consumo de bateria, funcionar
   em celulares modestos.
4. **Os dados são da pessoa.** Tudo fica no aparelho; o token fica criptografado.

### Público

Pessoas que têm um grupo, canal, pequeno negócio ou comunidade no Telegram e
querem um bot simples (respostas prontas, boas-vindas, avisos agendados) sem
contratar ninguém nem aprender a programar.

## 2. Como funciona por dentro

O bot usa **long polling** (`getUpdates`): o celular pergunta ao Telegram se
há mensagens novas e fica com a conexão aberta esperando (timeout ~50 s).
Por isso não precisa de IP público, servidor nem webhook.

Limitação natural (deixar clara para o usuário): com o celular desligado ou sem
internet, o bot fica fora do ar. O Telegram guarda as mensagens por até 24 h,
então o bot responde as pendentes quando voltar.

### Arquitetura escolhida

- **Kotlin** para a parte Android: telas, serviço em primeiro plano, início no
  boot, permissões, notificação, armazenamento seguro.
- **Python** (embutido com o **Chaquopy**, plugin Gradle de código aberto) para o
  **motor do bot**: loop de `getUpdates`, interpretação dos comandos, variáveis
  e envio de respostas.
  - Motivo: o autor prefere Python, e isso abre caminho para um modo avançado
    em que usuários escrevem scripts próprios.
  - Kivy/BeeWare foram descartados: serviço contínuo e boot são o ponto fraco
    deles.
- **UI em Jetpack Compose** (sugestão; confirmar com o autor).

### Divisão de responsabilidades

```
┌─────────────── Kotlin ───────────────┐      ┌──────── Python (Chaquopy) ────────┐
│ Telas (Compose)                      │      │ engine.run(config, callbacks)     │
│ BotService (foreground, specialUse)  │─────▶│  - loop getUpdates (long polling) │
│ BootReceiver (BOOT_COMPLETED)        │      │  - casa comando/palavra-chave     │
│ Token no Android Keystore            │      │  - preenche variáveis {nome}...   │
│ Guia de bateria por fabricante       │◀─────│  - envia resposta                 │
│ Tela de registro (logs)              │ logs │  - reporta status/erros           │
└──────────────────────────────────────┘      └───────────────────────────────────┘
```

- O `BotService` inicia o motor Python numa thread própria, passando a
  configuração (JSON) e um objeto de callbacks (log, status, erro).
- Parar o bot = sinalizar uma flag que o loop Python confere a cada ciclo.
- Cliente HTTP do Telegram em Python simples (`urllib` ou `requests`), sem
  frameworks pesados: queremos controle total sobre erros e explicações.
- A configuração do bot (comandos, respostas, agendamentos) é um JSON salvo
  pelo app; o motor só lê. Mudou na tela → o serviço recarrega.

## 3. Manter o bot vivo no Android (parte crítica)

| Item | Detalhe |
|---|---|
| Foreground Service | Única forma permitida de rodar continuamente. Notificação fixa obrigatória ("Bot ativo — 3 mensagens hoje") |
| `foregroundServiceType="specialUse"` | Obrigatório declarar tipo desde o Android 14. **Não usar `dataSync`**: no Android 15 ele tem limite de 6 h/dia e não pode iniciar a partir do boot |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Permissões do serviço |
| `RECEIVE_BOOT_COMPLETED` | Iniciar o bot quando o celular liga (se o usuário ativou essa opção) |
| `POST_NOTIFICATIONS` | Pedir em tempo de execução (Android 13+) |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Rede; detectar quando a internet volta |
| Isenção de otimização de bateria | Sem ela o Doze corta a rede com a tela apagada. Abrir a tela de configuração e explicar por quê |
| Wake lock parcial | Só se os testes mostrarem necessidade; medir consumo |

**Fabricantes** (Xiaomi, Samsung, Huawei, Oppo, Vivo, OnePlus...) têm
economizadores próprios que matam apps mesmo com tudo acima. O app deve
detectar a marca (`Build.MANUFACTURER`) e mostrar um guia passo a passo para
liberar (autostart, "sem restrições" etc.). Referência: https://dontkillmyapp.com

O app também deve **detectar quando foi morto** (ex.: o serviço ficou parado
sem o usuário pedir) e avisar na próxima abertura, com o guia da fabricante.

## 4. Funcionalidades

### Fase 1 — base (MVP)

- [ ] **Configuração guiada do token:** colar o token → validar com `getMe` →
      mostrar nome e foto do bot. Se falhar, explicar e mostrar o passo a passo
      do @BotFather.
- [ ] **Comandos com resposta pronta:** lista de `/comando` → texto.
- [ ] **Variáveis no texto** (ver seção 5).
- [ ] Registrar os comandos no menu "/" do Telegram (`setMyCommands`)
      automaticamente.
- [ ] Ligar/desligar o bot; iniciar com o celular (opção).
- [ ] Notificação fixa com status.
- [ ] Tela de registro: mensagens recebidas, respostas enviadas, erros
      explicados.
- [ ] Assistente de bateria/fabricante na primeira execução.

### Fase 2

- [ ] Mensagem de boas-vindas para quem entra no grupo.
- [ ] Respostas automáticas por palavra-chave (contém / começa com / exata).
- [ ] Botões embaixo da mensagem (inline keyboard), incluindo links.
- [ ] Comandos só para administradores (lista de IDs ou admins do grupo).
- [ ] Mensagens agendadas (diário, semanal, a cada X minutos) para um chat/canal.
- [ ] Exportar/importar a configuração (JSON) para backup ou compartilhar.

### Fase 3 — ideias

- [ ] Variáveis que buscam dados na internet (ex.: `{preço:BTC}`, `{clima:cidade}`)
      — permitiria recriar o BTC Signal sem código.
- [ ] Modo avançado: script Python do usuário para comandos específicos.
- [ ] Vários bots no mesmo app.
- [ ] Modelos prontos ("bot de loja", "bot de grupo", "bot de avisos").

## 5. Variáveis no texto de resposta

Sintaxe: `{nome_da_variavel}`. Variável desconhecida fica como está e gera aviso
na tela de edição.

| Variável | Valor |
|---|---|
| `{nome}` | Primeiro nome de quem enviou |
| `{sobrenome}` | Sobrenome (vazio se não houver) |
| `{usuario}` | @usuário (ou o nome, se não tiver) |
| `{id}` | ID de quem enviou |
| `{grupo}` | Nome do grupo/canal (vazio em conversa privada) |
| `{args}` | Texto depois do comando (`/diga olá` → `olá`) |
| `{data}` | Data atual (dd/mm/aaaa) |
| `{hora}` | Hora atual (hh:mm) |
| `{bot}` | @usuário do bot |

**Importante:** enviar com `parse_mode=HTML` e **escapar** (`&`, `<`, `>`) os
valores das variáveis antes de inserir. Sem isso, um nome com `<` quebra a
mensagem e o Telegram recusa o envio. A formatação do usuário (negrito, itálico,
link) deve vir de botões no editor, não de ele digitar HTML.

## 6. Modelo de dados (rascunho)

```json
{
  "version": 1,
  "commands": [
    {
      "command": "horario",
      "description": "Horário de funcionamento",
      "response": "Olá, {nome}! Funcionamos de 8h às 18h.",
      "admin_only": false,
      "buttons": []
    }
  ],
  "keywords": [],
  "welcome": { "enabled": false, "text": "" },
  "schedules": [],
  "admins": []
}
```

O token **não** fica neste JSON: fica no Android Keystore
(EncryptedSharedPreferences ou DataStore + Keystore).

## 7. Erros que o app deve explicar (lista inicial)

| Situação | Explicação/ação para o usuário |
|---|---|
| Token inválido / 401 | Token errado ou trocado no @BotFather → como pegar de novo (`/token`) |
| Sem internet | Avisar, mostrar que tenta de novo sozinho |
| 409 Conflict | O mesmo bot está rodando em outro lugar (outro celular, PC) |
| Webhook ativo | Oferecer botão para remover (`deleteWebhook`) |
| `chat not found` | ID errado ou bot não está no grupo/canal |
| `bot can't initiate conversation` | A pessoa precisa mandar /start para o bot antes |
| `not enough rights` | Dar permissão de administrador/publicar ao bot |
| 429 Too Many Requests | Esperar o tempo pedido (`retry_after`) automaticamente |
| `can't parse entities` | Não deve acontecer (escapamos); se acontecer, logar o texto |
| Serviço morto pelo sistema | Guia da fabricante + isenção de bateria |

## 8. Build e distribuição

- **GitHub Actions** gera o APK a cada push (desde o primeiro commit):
  `actions/checkout` → `actions/setup-java` (Temurin 17) →
  `gradle/actions/setup-gradle` → `./gradlew assembleDebug` →
  `actions/upload-artifact`.
- **Release assinado:** keystore em base64 nos Secrets do repositório
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); o
  workflow recria o arquivo e roda `assembleRelease`. Ao criar uma tag `v*`,
  anexar o APK a uma GitHub Release. **Nunca** commitar o keystore.
- Distribuição inicial: **GitHub Releases** e depois **F-Droid**. Play Store
  fica para depois (exige justificar `specialUse` e restringe o pedido direto de
  isenção de bateria).
- Commitar o Gradle Wrapper (`gradlew`, `gradle/wrapper/`). Não commitar
  `local.properties`, `build/`, `.gradle/`.

## 9. Decisões técnicas sugeridas (confirmar no início)

- `minSdk` 26 (Android 8.0) — `startForegroundService` e boa cobertura.
- `targetSdk` = o mais recente exigido pela Play Store.
- Kotlin + Jetpack Compose + Material 3.
- Chaquopy com Python 3.x; o mínimo de pacotes pip possível.
- Interface do app em **português** primeiro, com strings em `strings.xml`
  para traduzir depois.
- Licença: a definir (sugestão: GPL-3.0 ou MIT; F-Droid aceita ambas).

## 10. Por onde começar (primeira sessão)

1. Criar o projeto Android (Kotlin, Compose, Gradle Kotlin DSL) com o Gradle
   Wrapper e um `.gitignore` adequado.
2. Adicionar o workflow do GitHub Actions gerando o APK de debug, e confirmar
   que o build passa no Actions.
3. Integrar o Chaquopy com um `engine.py` mínimo que chama `getMe` e devolve o
   nome do bot para o Kotlin.
4. Tela de configuração do token, com validação e erros explicados.
5. `BotService` (foreground, `specialUse`) rodando o loop de `getUpdates` em
   Python e respondendo **um** comando fixo com variáveis.
6. `BootReceiver` + opção "iniciar com o celular".
7. README em português, simples, explicando instalar o APK e criar o bot.

Testar no celular real a cada etapa (APK baixado do Actions), principalmente
o comportamento com a tela apagada por mais de 30 minutos.

## 11. Perguntas em aberto para o autor

- Nome do app e do pacote (ex.: `com.<autor>.<app>`).
- Ícone/identidade visual.
- Licença.
- Compose ou Views tradicionais (sugestão: Compose).
- Suportar vários bots já na primeira versão ou só um?
