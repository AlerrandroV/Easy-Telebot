# Easy Telebot

**Idiomas do app:** português (Brasil) e inglês (EUA). · *English summary below.*

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

As mesmas variáveis também aceitam nomes em inglês: `{name}`, `{last_name}`,
`{username}`, `{group}`, `{date}` e `{time}`.

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
  Para a vitrine mostrar as **fotos**, informe um token do GitHub (só com a
  permissão `gist`): o app publica o catálogo num Gist secreto e a vitrine lê
  de lá. As fotos vão como miniaturas no próprio catálogo ou, com uma chave grátis
  do [imgbb](https://api.imgbb.com), hospedadas lá.

## Fluxos, menus e botões

- **Fluxos e menus**: etapas ligadas entre si, como um fluxograma. Botões *Ir para
  etapa* montam menus que se editam no lugar; *Seguir para* manda mensagens em
  sequência (com pausa de "digitando…"); *Esperar resposta* faz uma pergunta,
  valida (texto, número, e-mail, telefone) e guarda a resposta numa variável,
  como `{cidade}`, para usar nos textos seguintes. Um comando inicia o fluxo.
- **Botões**: link, copiar texto, ir para etapa, mini app e personalizado
  (callback entregue aos scripts do Termux), em comandos, fluxos e entregas.
- **Logs**: guardados no celular (3.000 por bot), com filtros, busca, exportação
  e uma visão de todos os bots juntos.

## Gatilhos automáticos

Além dos comandos, o bot reage sozinho (menu **Adicionar › Gatilhos**):

- **Palavra-chave**: a mensagem contém, tem a palavra inteira, começa com, é igual
  ou bate com uma expressão regular. Acentos não importam. Em grupos, desligue o
  modo de privacidade no @BotFather (ou deixe o bot admin) para ele ver as mensagens.
- **Tipo de mensagem**: foto, vídeo, áudio, voz, documento, figurinha, localização,
  contato, enquete…
- **Eventos do grupo**: alguém entrou (boas-vindas), saiu, pediu para entrar (aprovar
  ou recusar sozinho e responder no privado), reagiu a uma mensagem; bot adicionado
  a um grupo ou promovido a admin.
- **Link de convite**: `t.me/seubot?start=promo` com resposta própria; com "começa
  com", `ref_` atende `ref_123`, `ref_ana`… (indicações). O parâmetro chega em `{args}`.
- **Modo inline**: `@seubot texto` em qualquer conversa mostra resultados prontos
  (ative com `/setinline` no @BotFather).
- **Teclado fixo**: nos três pontinhos de uma mensagem, teclas que ficam no lugar do
  teclado do celular (texto, pedir contato, pedir localização) — combine com uma
  palavra-chave "é igual a" para responder cada tecla.

## Agendamentos

No menu **Adicionar › Agendamentos**:

- **Mensagem agendada**: todo dia, em dias da semana, todo mês, a cada X minutos ou
  numa data, para usuários, grupos, canais, chats escolhidos ou ninguém (só roda os
  blocos — por exemplo um script do Termux que gera um relatório). Usa o relógio do
  celular; se o bot estiver desligado na hora, ainda envia se voltar em até 1 hora.
- **Sequência depois do /start**: cada pessoa nova recebe mensagens no tempo escolhido
  (logo, 1 dia depois, 3 dias depois…). Só vale para quem começar depois de o item
  existir.
- **Lembretes (/lembrar)**: comando pronto em Python. A pessoa escreve
  `/lembrar amanhã 9h comprar pão`, `/lembrar em 30 minutos tirar o bolo` ou
  `/lembrar 25/12 10:00 ligar para a vó` e o bot avisa na hora (`/lembrar cancelar`
  apaga os pendentes). No Python: `lembrar(...)`, `cancelar_lembretes()` e
  `enviar_texto(..., para=ID)` para mandar em outra conversa.

## Internet sem Termux

- **Bloco "Requisição HTTP"** (na resposta de qualquer comando ou gatilho): GET, POST,
  PUT, PATCH ou DELETE para uma API ou site, com cabeçalhos e corpo (JSON, formulário
  ou texto). O resultado vira uma variável — `{cotacao}` e `{cotacao_status}` — e dá para
  pegar só um pedaço do JSON (`data.price`, `items.0.title`). O botão **Testar** mostra a
  resposta na hora.
- **Variáveis da internet**, direto no texto de qualquer mensagem, sem configurar nada:
  `{preço:BTC}` (cripto, CoinGecko), `{câmbio:USD}` (moedas, Frankfurter/BCE) e
  `{clima:São Paulo}` (Open-Meteo). Em inglês: `{price:ETH:USD}`, `{rate:EUR}`,
  `{weather:London}`. Os valores ficam guardados por alguns minutos.
- **Notícias automáticas** (Adicionar › Internet): posts novos de um site (RSS/Atom) ou de
  um canal do YouTube viram mensagens no seu canal, grupo ou para os usuários, com
  `{titulo}`, `{link}`, `{resumo}`, `{autor}` e `{imagem}`.
- **No Python**: `buscar(url, json=..., cabecalhos=...)`, `preco("BTC")`,
  `cambio("USD")` e `clima("Recife")`.

## Python facilitado

Para quem quer um pouco mais que blocos, sem virar programador: um pedaço de Python
seguro, que roda dentro do app. Dá para usar como bloco de um comando ou dentro do
**Aguardar resposta** (o código roda a cada mensagem até chamar `parar()`):

```python
if mensagem.tipo == foto:
    arquivo = mensagem.foto.baixar()
    r = termux("node atualizar_foto_perfil.js", arquivo, tempo=60)
    enviar_texto("Foto atualizada!" if r.ok else "Deu erro: " + r.erro)
    parar()
else:
    enviar_texto("Isso não é uma imagem, envie uma foto válida!")
```

Os nomes valem em português, inglês, espanhol, francês e russo ao mesmo tempo
(`mensagem.tipo` = `message.type` = `mensaje.tipo`…). O editor confere o código
enquanto você digita, e os erros vêm explicados com a linha. Há limite de passos,
de tempo e de mensagens, para o bot nunca travar.

Guia completo: [Português](docs/python-facilitado/pt-BR.md) ·
[English](docs/python-facilitado/en-US.md) · [Español](docs/python-facilitado/es-MX.md) ·
[Français](docs/python-facilitado/fr.md) · [Русский](docs/python-facilitado/ru.md)

## Idiomas

- O app está em **português (Brasil)** e **inglês (EUA)**. Ele segue o idioma do
  celular (outros idiomas caem no inglês) e dá para trocar no ícone de tradução da
  tela inicial. No Android 13 ou mais novo, a escolha também aparece em
  Configurações › Apps › Easy Telebot › Idioma.
- Cada bot tem o seu **idioma das mensagens automáticas** (loja, antispam,
  publicação…), nas configurações do bot. A vitrine segue o idioma do Telegram de
  quem a abre.
- Todo texto do app fica em `app/src/main/res/values*/strings.xml` (inglês em
  `values/`, português em `values-pt/`) e as mensagens do motor em
  `telebot_engine/i18n.py`. Para adicionar um idioma: copie esses textos, traduza e
  inclua a sigla em `res/xml/locales_config.xml`, em `AppLanguages.kt` e em
  `i18n.SUPPORTED`.

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

## English summary

Easy Telebot lets anyone create a Telegram bot and run it **on their own Android
phone**: no server, no domain, no monthly fee and no coding. Paste the token from
@BotFather, create commands with ready replies (text, media, buttons, menus,
"wait for reply" rules), and the bot answers while the phone is on and online.
It also has a shop (Mercado Pago, Bitcoin Lightning, Telegram Stars, paid channel
subscriptions), antispam, broadcasts, Termux scripts and **Simple Python**, a safe
slice of Python for small decisions
([guide](docs/python-facilitado/en-US.md)). The app is available in English and
Brazilian Portuguese.

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
