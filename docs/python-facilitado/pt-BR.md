# Python facilitado

**Idiomas:** Português (Brasil) · [English (US)](en-US.md) · [Español (México)](es-MX.md) · [Français](fr.md) · [Русский](ru.md)

O Python facilitado é um pedaço do Python feito para quem não programa. Você escreve
algumas linhas e o bot decide o que responder: "se a pessoa mandou uma foto, faça isto;
se não, faça aquilo". O código roda no próprio celular, dentro do app, com limites de
segurança: ele não mexe nos arquivos do celular nem acessa a internet sozinho. Para isso
existe o `termux()`.

Os nomes funcionam em **português, inglês, espanhol, francês e russo** ao mesmo tempo, e
os acentos são opcionais (`botao` = `botão`). Só as palavras do próprio Python ficam em
inglês: `if`, `elif`, `else`, `for`, `in`, `while`, `break`, `continue`, `and`, `or`,
`not`, `True`, `False` e `None`.

## Onde usar

- **Bloco "Python"** num comando: toque em *Adicionar* e escolha *Python*.
  O código roda quando alguém usa o comando. `parar()` encerra os blocos que viriam depois.
- **Bloco "Aguardar resposta"**: nos três pontinhos, escolha *Usar Python*.
  O código roda **a cada mensagem** que a pessoa mandar (e a cada botão sem resposta
  própria que ela tocar), até o código chamar `parar()`. Se a pessoa mandar um comando,
  a espera é cancelada.

O editor confere o código enquanto você digita e explica cada erro com o número da linha.
O botão *Recuo* põe 4 espaços, e o Enter repete o recuo da linha de cima (com mais 4
espaços depois de uma linha que termina em `:`).

## Exemplo: trocar a foto de perfil pelo Termux

Comando `/foto` com dois blocos: uma mensagem "Me envie a nova foto" e um *Aguardar
resposta* com este código:

```python
if mensagem.tipo == foto:
    arquivo = mensagem.foto.baixar()
    r = termux("node atualizar_foto_perfil.js", arquivo, tempo=60)
    if r.ok:
        enviar_texto("Foto atualizada com sucesso!")
    else:
        enviar_texto("Não deu certo: " + r.erro)
    parar()  # acabou a espera
else:
    enviar_texto("Isso não é uma imagem. Por favor, envie uma foto válida!")
```

Enquanto a pessoa mandar outra coisa, o bot pede a foto de novo. Quando chega a foto,
o app baixa o arquivo, entrega para o script no Termux e encerra a espera.

## O básico da escrita

- O que fica **dentro** de um `if`, `else`, `for` ou `while` vai com 4 espaços a mais no começo da linha.
- Textos ficam entre aspas: `"Olá"`. Juntar textos: `"Olá, " + usuario.nome`.
- Para pôr valores dentro do texto, use f-string: `f"Olá, {usuario.nome}!"`.
- Comparar: `==` (igual), `!=` (diferente), `<`, `>`, `<=`, `>=`, `in` (está dentro).
- Variáveis: `total = 3`, depois `total = total + 1` (ou `total += 1`).
- Listas `["a", "b"]`, dicionários `{"chave": "valor"}`, `for item in lista:`.
- Comentários começam com `#`.

Não existem no Python facilitado: `import`, `def`, `class`, `try`, `with`, `lambda`,
`[x for x in ...]` e nomes que começam com `_`. Funções nativas liberadas: `len`, `str`,
`int`, `float`, `bool`, `round`, `abs`, `min`, `max`, `sum`, `range`, `list`, `dict`,
`tuple`, `set`, `sorted`, `reversed`, `enumerate`, `zip`, `any` e `all`, além dos
métodos comuns de texto (`.lower()`, `.split()`, `.replace()`…), lista (`.append()`…) e
dicionário (`.get()`…).

## O que o código enxerga

| Nome | O que é |
|---|---|
| `mensagem.tipo` | Tipo da mensagem: `texto`, `foto`, `video`, `gif`, `audio`, `voz`, `documento`, `figurinha`, `localizacao`, `contato`, `enquete`, `botao` ou `outro`. Compare com ou sem aspas: `mensagem.tipo == foto` ou `== "foto"` |
| `mensagem.texto` | O texto (ou a legenda da mídia) |
| `mensagem.foto` | A foto (na maior resolução), ou nada se não for foto |
| `mensagem.arquivo` | Qualquer mídia: foto, vídeo, áudio, documento, figurinha… |
| `mensagem.dado` | O dado do botão tocado (quando `mensagem.tipo == botao`) |
| `mensagem.contato` | O telefone de um contato compartilhado |
| `mensagem.latitude`, `mensagem.longitude` | Localização compartilhada |
| `arquivo.baixar()` | Baixa o arquivo para o celular; devolve o mesmo arquivo, agora com `caminho` |
| `arquivo.nome`, `arquivo.tamanho`, `arquivo.formato`, `arquivo.id` | Dados do arquivo |
| `usuario.nome`, `usuario.sobrenome`, `usuario.arroba`, `usuario.id`, `usuario.idioma` | Quem mandou |
| `usuario.admin` | `True` se a pessoa é administradora do bot |
| `chat.id`, `chat.tipo`, `chat.titulo`, `chat.privado` | Onde a mensagem chegou |
| `argumentos` | O texto depois do comando (`/diga olá` → `"olá"`) |
| `variaveis` | Dicionário guardado por pessoa: `variaveis["cidade"] = mensagem.texto`. Vale também como `{cidade}` nas mensagens comuns |

## Funções do bot

| Função | O que faz |
|---|---|
| `enviar_texto(texto, botoes=[...])` | Manda uma mensagem. Aceita *negrito*, _itálico_, [link](site.com)… |
| `responder(texto)` | Igual, mas como resposta à mensagem da pessoa |
| `enviar_foto(foto, legenda="")` | Foto por link, por `arquivo` ou por caminho |
| `enviar_arquivo(caminho, legenda="")` | Manda um documento |
| `botao("Texto", link=..., dado=..., copiar=...)` | Cria um botão: link, dado (callback) ou copiar texto |
| `termux(comando, arg1, arg2, ..., tempo=30, esperar=True, ambiente={})` | Roda um comando no Termux (veja abaixo) |
| `parar()` | Encerra o código. No *Aguardar resposta*, encerra a espera |
| `pausa(segundos)` | Espera um pouco (no total, até 60 s por execução) |
| `registrar(...)` | Escreve no registro (logs) do app |
| `base64(arquivo_ou_texto)` | Converte para base64 |
| `aleatorio(1, 6)` | Número sorteado entre 1 e 6 |
| `escolher(lista)` | Um item sorteado da lista |
| `agora()` | Data e hora do celular: `"25/11/2026 14:30"` |
| `lembrar(quando_e_texto)` | Agenda um lembrete para quem mandou: `lembrar("amanhã 9h comprar pão")`, `lembrar("em 30 minutos tirar o bolo")` ou `lembrar(30, "tirar o bolo")` (minutos). Devolve quando vai lembrar (`"27/11 às 09:00"`) ou vazio se não entendeu |
| `cancelar_lembretes()` | Apaga os lembretes pendentes de quem mandou e diz quantos eram |

Todas as funções de envio aceitam `para=ID_DO_CHAT` (ou `para="@canal"`) para mandar em outra conversa, por exemplo num agendamento sem destino.

### Botões

```python
enviar_texto("Gostou?", botoes=[
    [botao("Sim", dado="sim"), botao("Não", dado="nao")],
    botao("Site", link="https://exemplo.com"),
    botao("Copiar cupom", copiar="PROMO10"),
])
```

Uma lista de botões põe um por linha; uma lista dentro da lista é uma linha com vários.
Se o botão não tiver resposta configurada no app, o toque chega ao *Aguardar resposta*
com código:

```python
if mensagem.tipo == botao:
    enviar_texto("Você escolheu: " + mensagem.dado)
    parar()
```

### `termux()`

```python
r = termux("python3 ~/bot/clima.py", argumentos, tempo=20)
enviar_texto(r.saida if r.ok else "Falhou: " + r.erro)
```

- Cada argumento vai protegido (com aspas) para o shell. Um **arquivo** (`mensagem.foto`,
  `arquivo`…) é baixado e copiado para o Termux; o script recebe o caminho dele
  (até 512 KB por arquivo; exige Termux 0.118 ou mais novo).
- `tempo`: segundos de espera pelo resultado (1 a 300; padrão 30).
- `esperar=False`: roda em segundo plano e não espera; `termux()` devolve nada.
- `ambiente={"CIDADE": "Rio"}`: variáveis de ambiente extras. O script também recebe as
  de sempre: `TELEBOT_ARGS`, `TELEBOT_UPDATE`, `TELEBOT_CHAT_ID`, `TELEBOT_USER_ID`…
- O resultado tem `r.ok` (deu certo), `r.saida` (o que o script imprimiu), `r.erro`
  (stderr ou o motivo da falha), `r.codigo` (código de saída) e `r.tempo_esgotado`.

O Termux precisa estar pronto: veja *Termux e scripts*, na tela do bot.

## Limites (para o bot nunca travar)

- 200 mil passos e 10 segundos de processamento por execução (o tempo esperando o Termux,
  o Telegram ou uma pausa não conta). Um `while True:` sem `break` para aqui.
- 20 mensagens por execução; textos de até 100 mil caracteres; listas de até 10 mil itens.
- Código de até 20 mil caracteres; `variaveis` de até 64 KB por pessoa.

Quando algo dá errado, o erro aparece no registro do app com a linha e uma explicação, no
idioma em que o código foi escrito.

## Os nomes em todos os idiomas

### Objetos e funções

| pt-BR | en-US | es-MX | fr | ru |
|---|---|---|---|---|
| `mensagem` | `message` / `msg` | `mensaje` | `message` | `сообщение` |
| `usuario` | `user` | `usuario` | `utilisateur` | `пользователь` |
| `chat` / `conversa` | `chat` | `chat` | `chat` / `discussion` | `чат` |
| `variaveis` | `variables` / `vars` | `variables` | `variables` | `переменные` |
| `argumentos` | `args` / `arguments` | `argumentos` | `arguments` | `аргументы` |
| `enviar_texto` / `enviar` | `send_text` / `send` | `enviar_texto` / `enviar` | `envoyer_texte` / `envoyer` | `отправить_текст` / `отправить` |
| `responder` | `reply` | `responder` | `repondre` | `ответить` |
| `enviar_foto` | `send_photo` | `enviar_foto` | `envoyer_photo` | `отправить_фото` |
| `enviar_arquivo` | `send_file` | `enviar_archivo` | `envoyer_fichier` | `отправить_файл` |
| `botao` | `button` | `boton` | `bouton` | `кнопка` |
| `termux` | `termux` | `termux` | `termux` | `termux` / `термукс` |
| `parar` / `sair` | `stop` / `exit` | `detener` / `salir` | `arreter` / `sortir` | `стоп` / `выход` |
| `registrar` | `log` / `print` | `registrar` | `journal` | `журнал` |
| `pausa` | `pause` / `sleep` | `pausa` | `pause` | `пауза` |
| `base64` | `base64` | `base64` | `base64` | `base64` |
| `aleatorio` | `random` | `aleatorio` | `aleatoire` | `случайное` |
| `escolher` | `choice` | `elegir` | `choisir` | `выбрать` |
| `agora` | `now` | `ahora` | `maintenant` | `сейчас` |
| `lembrar` | `remind` | `recordar` | `rappeler` | `напомнить` |
| `cancelar_lembretes` | `cancel_reminders` | `cancelar_recordatorios` | `annuler_rappels` | `отменить_напоминания` |
| `verdadeiro` | `true` | `verdadero` | `vrai` | `истина` |
| `falso` | `false` | `falso` | `faux` | `ложь` |

### Atributos

| pt-BR | en-US | es-MX | fr | ru |
|---|---|---|---|---|
| `tipo` | `type` / `kind` | `tipo` | `type` | `тип` |
| `texto` | `text` | `texto` | `texte` | `текст` |
| `foto` | `photo` | `foto` | `photo` | `фото` |
| `arquivo` | `file` | `archivo` | `fichier` | `файл` |
| `dado` | `data` | `dato` | `donnee` | `данные` |
| `contato` / `telefone` | `contact` / `phone` | `contacto` / `telefono` | `contact` / `telephone` | `контакт` / `телефон` |
| `latitude` | `latitude` | `latitud` | `latitude` | `широта` |
| `longitude` | `longitude` | `longitud` | `longitude` | `долгота` |
| `id` | `id` | `id` | `id` | `id` |
| `usuario` / `remetente` | `user` / `sender` | `usuario` / `remitente` | `utilisateur` / `expediteur` | `пользователь` / `отправитель` |
| `chat` | `chat` | `chat` | `chat` | `чат` |
| `baixar` | `download` | `descargar` | `telecharger` | `скачать` |
| `nome` | `name` | `nombre` | `nom` | `имя` |
| `tamanho` | `size` | `tamano` | `taille` | `размер` |
| `formato` / `mime` | `mime` / `format` | `formato` / `mime` | `format` / `mime` | `формат` / `mime` |
| `caminho` | `path` | `ruta` | `chemin` | `путь` |
| `nome` | `name` / `first_name` | `nombre` | `prenom` | `имя` |
| `sobrenome` | `last_name` / `surname` | `apellido` | `nom_de_famille` | `фамилия` |
| `arroba` / `username` | `username` | `username` / `arroba` | `pseudo` / `username` | `логин` / `username` |
| `idioma` | `language` | `idioma` | `langue` | `язык` |
| `admin` / `e_admin` | `admin` / `is_admin` | `admin` / `es_admin` | `admin` / `est_admin` | `админ` |
| `tipo` | `type` | `tipo` | `type` | `тип` |
| `titulo` / `nome` | `title` / `name` | `titulo` / `nombre` | `titre` / `nom` | `название` |
| `privado` | `private` / `is_private` | `privado` | `prive` | `личный` |
| `saida` | `output` / `stdout` | `salida` | `sortie` | `вывод` |
| `erro` | `error` / `stderr` | `error` | `erreur` | `ошибка` |
| `codigo` | `code` / `exit_code` | `codigo` | `code` | `код` |
| `ok` / `sucesso` | `ok` / `success` | `ok` / `exito` | `ok` / `succes` | `ок` / `успех` |
| `tempo_esgotado` | `timed_out` | `tiempo_agotado` | `delai_depasse` | `время_вышло` |

### Opções das funções

| pt-BR | en-US | es-MX | fr | ru |
|---|---|---|---|---|
| `botoes` | `buttons` | `botones` | `boutons` | `кнопки` |
| `legenda` | `caption` | `leyenda` | `legende` | `подпись` |
| `tempo` / `limite` | `timeout` | `tiempo` / `limite` | `delai` | `время` / `таймаут` |
| `esperar` | `wait` | `esperar` | `attendre` | `ждать` |
| `ambiente` | `env` / `environment` | `entorno` | `environnement` | `окружение` |
| `link` | `link` / `url` | `enlace` / `link` | `lien` | `ссылка` |
| `dado` | `data` | `dato` | `donnee` | `данные` |
| `copiar` | `copy` | `copiar` | `copier` | `копировать` |
| `para` | `to` / `chat_id` | `para` | `vers` / `destinataire` | `кому` |

### Tipos de mensagem

| pt-BR | en-US | es-MX | fr | ru |
|---|---|---|---|---|
| `texto` | `text` | `texto` | `texte` | `текст` |
| `foto` / `imagem` | `photo` / `image` | `foto` / `imagen` | `photo` / `image` | `фото` |
| `video` | `video` | `video` | `video` | `видео` |
| `gif` / `animacao` | `gif` / `animation` | `gif` / `animacion` | `gif` / `animation` | `гиф` / `анимация` |
| `video_redondo` | `video_note` | `video_redondo` | `video_rond` | `кружок` |
| `audio` / `musica` | `audio` / `music` | `audio` / `musica` | `audio` / `musique` | `аудио` / `музыка` |
| `voz` / `audio_de_voz` | `voice` | `voz` / `nota_de_voz` | `vocal` / `message_vocal` | `голосовое` |
| `documento` | `document` | `documento` | `document` | `документ` |
| `figurinha` / `sticker` | `sticker` | `sticker` / `pegatina` | `autocollant` / `sticker` | `стикер` |
| `localizacao` / `local` | `location` | `ubicacion` | `position` | `геопозиция` |
| `contato` | `contact` | `contacto` | `contact` | `контакт` |
| `enquete` | `poll` | `encuesta` | `sondage` | `опрос` |
| `botao` | `button` | `boton` | `bouton` | `кнопка` |
| `outro` | `other` | `otro` | `autre` | `другое` |
