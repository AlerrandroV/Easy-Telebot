# Easy Telebot

Crie um bot do Telegram e deixe ele rodando **no seu próprio celular Android**,
sem servidor, sem domínio, sem mensalidade e sem programar.

Exemplo: você cola o token do bot, cria o comando `/horario` com a resposta
`Olá, {nome}! Funcionamos de 8h às 18h.` e pronto — o bot responde no Telegram
enquanto o celular estiver ligado e com internet.

> Projeto em desenvolvimento (fase 1). Veja o [CLAUDE.md](CLAUDE.md) para a
> ideia completa, as decisões e o roteiro.

## Instalar

1. Abra a aba **Actions** deste repositório, entre no build mais recente que
   passou e baixe o artefato **easy-telebot-debug** (precisa estar logado
   no GitHub). Quando houver versões publicadas, elas ficam em **Releases**.
2. Descompacte o `.zip` e abra o arquivo `.apk` no celular.
3. Se o Android pedir, permita “instalar apps desta fonte”.

## Criar o bot no Telegram

1. No Telegram, abra o **@BotFather** e mande `/newbot`.
2. Escolha um nome e um usuário terminado em `bot`.
3. Copie o token que ele mandar (algo como `123456789:AAH...`).
4. No Easy Telebot, cole o token e toque em **Verificar token**.
5. Siga o guia de **bateria**: sem ele o Android pode desligar o bot com a tela
   apagada.
6. Toque em **Ligar o bot** e mande `/start` para ele no Telegram.

## Variáveis nas respostas

| Variável | Valor |
|---|---|
| `{nome}` | Primeiro nome de quem enviou |
| `{sobrenome}` | Sobrenome (vazio se não houver) |
| `{usuario}` | @usuário (ou o nome, se não tiver) |
| `{id}` | ID de quem enviou |
| `{grupo}` | Nome do grupo (vazio em conversa privada) |
| `{args}` | Texto depois do comando (`/diga olá` → `olá`) |
| `{data}` | Data atual (dd/mm/aaaa) |
| `{hora}` | Hora atual (hh:mm) |
| `{bot}` | @usuário do bot |

## Vendas e assinaturas

Em **Vendas**, na tela do bot:

- **Catálogo**: produtos digitais (a entrega — texto, link, arquivo — sai sozinha
  depois do pagamento) e **assinaturas de canais/grupos** (convite pessoal,
  lembrete antes de vencer e remoção automática de quem não renovou).
- **Pagamentos**: Mercado Pago (link com Pix, cartão e boleto, ou Pix com QR
  code no chat), Bitcoin pela Lightning (Blink) e Telegram Stars. O bot confere
  os pagamentos sozinho, sem servidor nem webhook.
- **Vitrine (mini app)**: o botão *Abrir loja* abre uma vitrine com carrinho
  dentro do Telegram. A página fica em [`docs/loja`](docs/loja/index.html) e
  recebe o catálogo pelo próprio link. Para usar o endereço padrão, ative o
  GitHub Pages do repositório (Settings → Pages → branch `main`, pasta `/docs`)
  ou hospede a página em qualquer endereço https e informe no app.

## Termux e scripts

Em **Termux e scripts**, na tela do bot:

- **Comando de script**: um comando roda um programa seu no
  [Termux](https://termux.dev) (Python, Node, Bash, Ruby...) e responde com o que
  ele imprimir — texto ou JSON com foto, botões e edição de mensagem. Os dados da
  mensagem chegam em variáveis de ambiente (`TELEBOT_ARGS`, `TELEBOT_UPDATE`...).
- **API local do bot**: um servidor em `127.0.0.1` que imita a Bot API oficial.
  Bibliotecas como python-telegram-bot, aiogram e Telegraf rodam no Termux
  apontando para ele, com uma chave local no lugar do token. Recebem o que o app
  não respondeu (ou tudo) e mandam mensagens como o bot.

## Limitações

- Com o celular desligado ou sem internet, o bot fica fora do ar. O Telegram
  guarda as mensagens por até 24 h e o bot responde as pendentes quando voltar.
- O mesmo bot não pode estar ligado em dois lugares ao mesmo tempo.

## Para desenvolvedores

- Kotlin + Jetpack Compose para o app; o motor do bot é Python
  (`app/src/main/python/telebot_engine`), embutido com o
  [Chaquopy](https://chaquo.com/chaquopy/), usando só a biblioteca padrão.
- Build: `./gradlew assembleDebug` (precisa do Android SDK e de um Python 3.12
  no PATH).
- Testes do motor (rodam em qualquer PC, sem Android):
  `python -m unittest discover -s app/src/test/python -t app/src/test/python`

### Release assinado

Crie um keystore (uma vez, e **nunca** o commite):

```sh
keytool -genkeypair -v -keystore release.jks -alias easytelebot \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # copie a saída
```

Em **Settings › Secrets and variables › Actions**, crie `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Depois, ao criar uma tag
`v*` (ex.: `git tag v0.1.0 && git push origin v0.1.0`), o Actions gera o APK
assinado e o anexa a uma Release.
