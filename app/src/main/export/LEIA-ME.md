# Seu bot, em código aberto

Este arquivo foi exportado pelo **Easy Telebot**. Ele tem tudo o que o bot
precisa para rodar em qualquer computador ou servidor com Python 3.9+,
sem instalar nada além do Python:

- `run.py` — liga o bot (`python3 run.py`). Pare com Ctrl+C.
- `config.json` — os comandos e mensagens que você criou no app.
- `media/` — fotos, vídeos e arquivos usados nas mensagens.
- `telebot_engine/` — o motor do bot (o mesmo que roda no app). Pode ler,
  modificar e reaproveitar à vontade.

## Token

O token não vai junto, a menos que você tenha marcado essa opção ao exportar.
Informe-o de um destes jeitos:

1. Variável de ambiente: `BOT_TOKEN=123:ABC python3 run.py`
2. Arquivo `token.txt` nesta pasta, só com o token.
3. Digitando quando o `run.py` pedir.

Guarde o token como uma senha: quem tem o token controla o bot.

## Importante

O Telegram só deixa o bot ligado em **um lugar por vez**. Desligue o bot no
celular antes de ligá-lo aqui (senão aparece o erro 409 Conflict).

## Formatação das mensagens

`*negrito*`, `_itálico_`, `__sublinhado__`, `~riscado~`, `||spoiler||`,
`` `código` ``, `[texto](https://link)` e `> citação`. Variáveis: `{nome}`,
`{sobrenome}`, `{usuario}`, `{id}`, `{grupo}`, `{args}`, `{data}`, `{hora}`,
`{bot}` (ou, em inglês, `{name}`, `{last_name}`, `{username}`, `{group}`,
`{date}`, `{time}`).

## Python facilitado

Os blocos "Python facilitado" (e o "Aguardar resposta" com código) ficam no
`config.json`, no campo `code`. Eles rodam no interpretador seguro do motor
(`telebot_engine/pyfacil.py`), aqui do mesmo jeito que no celular. O `termux()`
roda o comando direto no `bash` deste computador; arquivos passados a ele
(`mensagem.foto.baixar()`) são copiados para uma pasta temporária em
`$TELEBOT_FILES_DIR`. Guia: `docs/python-facilitado/` no repositório do app,
em português, inglês, espanhol, francês e russo.

## Idioma

O campo `language` do `config.json` (`pt` ou `en`) escolhe o idioma das
mensagens automáticas do bot (loja, antispam, publicação). Vazio = o idioma
do sistema (variável `LANG`), ou português se não for inglês.

## Administradores

Para tornar alguém administrador (classe I a X, sendo I a mais alta):

    python3 run.py newadmin 3

O comando mostra um código de uso único, válido por 10 minutos. A pessoa manda
`/newadmin CÓDIGO` para o bot em conversa privada. Os admins ficam em
`admins.json`. Um admin de classe N pode usar comandos que exigem a classe N ou
uma classe mais baixa (ex.: classe III usa os comandos de III, IV e V).

## Banco de dados

`storage.json` diz onde o bot guarda usuários e histórico. Se no app era
"interno" ou "pasta do aparelho", aqui os dados vão para a pasta `dados/`.
Firebase, Firestore e AWS continuam funcionando no computador se você exportou
com o token marcado (as credenciais vão em `storage_secrets.json` — guarde como
uma senha). Sem esse arquivo, preencha as credenciais nele à mão, com os mesmos
nomes de campo.

## Publicação em massa (/post)

Admins (com a classe configurada no app) mandam `/post` em conversa privada,
enviam as mensagens, `/pronto` para ver a prévia e `/confirmar CÓDIGO` para
enviar a todos (ou `/confirmar CÓDIGO usuarios grupos canais`). Em inglês:
`/done`, `/confirm CÓDIGO users groups channels` e `/cancel`. Botões:
linhas `[Texto | link]` no fim do texto ou da legenda.

## Antispam

Quem manda mensagens demais em pouco tempo (ou repete a mesma mensagem) é
bloqueado sozinho: o bot passa a ignorar a pessoa e avisa os admins da classe
configurada, com os botões ✅ Desbloquear e 🚫 Manter bloqueado. Os bloqueios
ficam em `bans.json` (apague uma entrada para desbloquear à mão). A
sensibilidade fica em `config.json`, no campo `antispam`.

## Loja e assinaturas

Com a loja ligada no app, o bot vende pelo `/loja`: produtos digitais (entrega
automática) e assinaturas de canais/grupos (convite pessoal, lembrete e remoção
ao vencer). Pagamentos: Mercado Pago (link ou Pix), Bitcoin pela Lightning
(Blink) e Telegram Stars. O bot confere os pagamentos sozinho; não precisa de
servidor nem de webhook. Os pedidos e assinaturas ficam em `shop.db`. O token
do Mercado Pago e a chave do Blink vão em `payments.json` (só se exportado com
o token; guarde como uma senha).

## Comandos de script e Termux

Comandos configurados como "Script do Termux" rodam a linha de comando
cadastrada (aqui no computador, direto no `bash`) e respondem com o que o
programa imprimir: texto ou JSON (`text`, `photo`, `buttons`...). O programa
recebe os dados em variáveis de ambiente: `TELEBOT_ARGS`, `TELEBOT_UPDATE`,
`TELEBOT_USER_ID`, `TELEBOT_CHAT_ID`, `TELEBOT_DATA`...

Este pacote também roda **dentro do próprio Termux** no celular:
`pkg install python`, descompacte e rode `python run.py`.

A API local (`termux` no `config.json`) abre em `127.0.0.1` uma cópia da API
do Telegram para bibliotecas de bot, com a chave `termux.key` no lugar do token.
