# Seu bot, em código aberto

Este arquivo foi exportado pelo **Easy Telebot**. Ele tem tudo o que o bot
precisa para rodar em qualquer computador ou servidor com Python 3.8+,
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
`{bot}`.

## Administradores

Para tornar alguém administrador (classe I a X, sendo I a mais alta):

    python3 run.py newadmin 3

O comando mostra um código de uso único, válido por 10 minutos. A pessoa manda
`/newadmin CÓDIGO` para o bot em conversa privada. Os admins ficam em
`admins.json`. Um admin de classe N pode usar comandos que exigem a classe N ou
uma classe mais baixa (ex.: classe III usa os comandos de III, IV e V).
