# Simple Python

**Languages:** [Português (Brasil)](pt-BR.md) · English (US) · [Español (México)](es-MX.md) · [Français](fr.md) · [Русский](ru.md)

Simple Python is a slice of Python made for people who don't program. You write a few
lines and the bot decides what to reply: "if the person sent a photo, do this; otherwise,
do that". The code runs on the phone itself, inside the app, with safety limits: it can't
touch the phone's files or reach the internet by itself. That's what `termux()` is for.

Names work in **English, Portuguese, Spanish, French and Russian** at the same time, and
accents are optional. Only Python's own words stay in English everywhere: `if`, `elif`,
`else`, `for`, `in`, `while`, `break`, `continue`, `and`, `or`, `not`, `True`, `False`
and `None`.

## Where to use it

- **"Simple Python" block** in a command: tap *Add* and choose *Simple Python*. The code
  runs when someone uses the command. `stop()` ends the blocks that would come after it.
- **"Wait for reply" block**: in the three-dot menu, choose *Use Simple Python*. The code
  runs **for every message** the person sends (and for every button without its own
  reply that they tap), until the code calls `stop()`. If the person sends a command,
  the wait is cancelled.

The editor checks the code while you type and explains each error with its line number.
The *Indent* button adds 4 spaces, and Enter repeats the indentation of the line above
(plus 4 spaces after a line ending in `:`).

## Example: change the profile photo through Termux

Command `/photo` with two blocks: a message "Send me the new photo" and a *Wait for
reply* with this code:

```python
if message.type == photo:
    file = message.photo.download()
    r = termux("node update_profile_photo.js", file, timeout=60)
    if r.ok:
        send_text("Photo updated successfully!")
    else:
        send_text("It didn't work: " + r.error)
    stop()  # the wait is over
else:
    send_text("That's not an image. Please send a valid photo!")
```

While the person sends anything else, the bot asks for the photo again. When the photo
arrives, the app downloads the file, hands it to the script in Termux and ends the wait.

## Writing basics

- Whatever is **inside** an `if`, `else`, `for` or `while` gets 4 more spaces at the start of the line.
- Text goes between quotes: `"Hello"`. Joining text: `"Hello, " + user.name`.
- To put values inside text, use an f-string: `f"Hello, {user.name}!"`.
- Comparing: `==` (equal), `!=` (different), `<`, `>`, `<=`, `>=`, `in` (is inside).
- Variables: `total = 3`, then `total = total + 1` (or `total += 1`).
- Lists `["a", "b"]`, dictionaries `{"key": "value"}`, `for item in items:`.
- Comments start with `#`.

Not available in Simple Python: `import`, `def`, `class`, `try`, `with`, `lambda`,
`[x for x in ...]` and names starting with `_`. Built-in functions allowed: `len`, `str`,
`int`, `float`, `bool`, `round`, `abs`, `min`, `max`, `sum`, `range`, `list`, `dict`,
`tuple`, `set`, `sorted`, `reversed`, `enumerate`, `zip`, `any` and `all`, plus the
usual text methods (`.lower()`, `.split()`, `.replace()`…), list methods (`.append()`…)
and dictionary methods (`.get()`…).

## What the code can see

| Name | What it is |
|---|---|
| `message.type` | Message type: `text`, `photo`, `video`, `gif`, `audio`, `voice`, `document`, `sticker`, `location`, `contact`, `poll`, `button` or `other`. Compare with or without quotes: `message.type == photo` or `== "photo"` |
| `message.text` | The text (or the media caption) |
| `message.photo` | The photo (at the highest resolution), or nothing if it isn't a photo |
| `message.file` | Any media: photo, video, audio, document, sticker… |
| `message.data` | The data of the tapped button (when `message.type == button`) |
| `message.contact` | The phone number of a shared contact |
| `message.latitude`, `message.longitude` | Shared location |
| `file.download()` | Downloads the file to the phone; returns the same file, now with a `path` |
| `file.name`, `file.size`, `file.mime`, `file.id` | File details |
| `user.name`, `user.last_name`, `user.username`, `user.id`, `user.language` | Who sent it |
| `user.admin` | `True` if the person is an administrator of the bot |
| `chat.id`, `chat.type`, `chat.title`, `chat.private` | Where the message arrived |
| `args` | The text after the command (`/say hello` → `"hello"`) |
| `variables` | A dictionary kept per person: `variables["city"] = message.text`. It also works as `{city}` in regular messages |

## Bot functions

| Function | What it does |
|---|---|
| `send_text(text, buttons=[...])` | Sends a message. Accepts *bold*, _italic_, [link](site.com)… |
| `reply(text)` | The same, but as a reply to the person's message |
| `send_photo(photo, caption="")` | A photo by link, by `file` or by path |
| `send_file(path, caption="")` | Sends a document |
| `button("Text", link=..., data=..., copy=...)` | Creates a button: link, data (callback) or copy text |
| `termux(command, arg1, arg2, ..., timeout=30, wait=True, env={})` | Runs a command in Termux (see below) |
| `stop()` | Ends the code. In *Wait for reply*, ends the wait |
| `pause(seconds)` | Waits a little (up to 60 s in total per run) |
| `log(...)` | Writes to the app's log |
| `base64(file_or_text)` | Converts to base64 |
| `random(1, 6)` | A random number from 1 to 6 |
| `choice(items)` | A random item from the list |
| `now()` | The phone's date and time: `"25/11/2026 14:30"` (day/month/year) |

### Buttons

```python
send_text("Did you like it?", buttons=[
    [button("Yes", data="yes"), button("No", data="no")],
    button("Website", link="https://example.com"),
    button("Copy coupon", copy="PROMO10"),
])
```

A list of buttons puts one per row; a list inside the list is a row with several.
If the button has no reply set up in the app, the tap reaches the *Wait for reply* with
code:

```python
if message.type == button:
    send_text("You chose: " + message.data)
    stop()
```

### `termux()`

```python
r = termux("python3 ~/bot/weather.py", args, timeout=20)
send_text(r.output if r.ok else "Failed: " + r.error)
```

- Each argument is safely quoted for the shell. A **file** (`message.photo`, `file`…) is
  downloaded and copied to Termux; the script gets its path (up to 512 KB per file;
  requires Termux 0.118 or newer).
- `timeout`: seconds to wait for the result (1 to 300; default 30).
- `wait=False`: runs in the background without waiting; `termux()` returns nothing.
- `env={"CITY": "Austin"}`: extra environment variables. The script also gets the usual
  ones: `TELEBOT_ARGS`, `TELEBOT_UPDATE`, `TELEBOT_CHAT_ID`, `TELEBOT_USER_ID`…
- The result has `r.ok` (it worked), `r.output` (what the script printed), `r.error`
  (stderr or the reason it failed), `r.code` (exit code) and `r.timed_out`.

Termux has to be ready: see *Termux and scripts*, on the bot screen.

## Limits (so the bot never freezes)

- 200,000 steps and 10 seconds of processing per run (time spent waiting for Termux,
  Telegram or a pause doesn't count). A `while True:` without `break` stops here.
- 20 messages per run; text up to 100,000 characters; lists up to 10,000 items.
- Code up to 20,000 characters; `variables` up to 64 KB per person.

When something goes wrong, the error shows up in the app's log with the line and an
explanation, in the language the code was written in.

## Names in every language

### Objects and functions

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
| `verdadeiro` | `true` | `verdadero` | `vrai` | `истина` |
| `falso` | `false` | `falso` | `faux` | `ложь` |

### Attributes

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

### Function options

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

### Message types

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
