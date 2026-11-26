# Python fácil

**Idiomas:** [Português (Brasil)](pt-BR.md) · [English (US)](en-US.md) · Español (México) · [Français](fr.md) · [Русский](ru.md)

Python fácil es una parte de Python hecha para quien no programa. Escribes unas cuantas
líneas y el bot decide qué responder: "si la persona mandó una foto, haz esto; si no, haz
aquello". El código corre en el propio celular, dentro de la app, con límites de
seguridad: no toca los archivos del celular ni entra a internet por su cuenta. Para eso
existe `termux()`.

Los nombres funcionan en **español, portugués, inglés, francés y ruso** al mismo tiempo,
y los acentos son opcionales (`boton` = `botón`). Solo las palabras propias de Python se
quedan en inglés: `if`, `elif`, `else`, `for`, `in`, `while`, `break`, `continue`, `and`,
`or`, `not`, `True`, `False` y `None`.

> La app por ahora está en portugués e inglés. Los nombres de Python fácil en español
> funcionan igual en cualquiera de los dos idiomas de la app.

## Dónde usarlo

- **Bloque "Python fácil"** (en la app: *Python*) en un
  comando: toca *Agregar* y elige el bloque. El código corre cuando alguien usa el
  comando. `detener()` termina los bloques que vendrían después.
- **Bloque "Esperar respuesta"** (*Wait for reply* / *Aguardar resposta*): en el menú de
  tres puntos, elige usar Python. El código corre **con cada mensaje** que mande la
  persona (y con cada botón sin respuesta propia que toque), hasta que el código llame a
  `detener()`. Si la persona manda un comando, la espera se cancela.

El editor revisa el código mientras escribes y explica cada error con el número de línea.
El botón de sangría pone 4 espacios, y Enter repite la sangría de la línea de arriba (más
4 espacios después de una línea que termina en `:`).

## Ejemplo: cambiar la foto de perfil con Termux

Comando `/foto` con dos bloques: un mensaje "Mándame la foto nueva" y un *Esperar
respuesta* con este código:

```python
if mensaje.tipo == foto:
    archivo = mensaje.foto.descargar()
    r = termux("node actualizar_foto_perfil.js", archivo, tiempo=60)
    if r.ok:
        enviar_texto("¡Foto actualizada con éxito!")
    else:
        enviar_texto("No funcionó: " + r.error)
    detener()  # se acabó la espera
else:
    enviar_texto("Eso no es una imagen. ¡Por favor, manda una foto válida!")
```

Mientras la persona mande otra cosa, el bot vuelve a pedir la foto. Cuando llega la foto,
la app descarga el archivo, se lo pasa al script en Termux y termina la espera.

## Lo básico para escribir

- Lo que va **dentro** de un `if`, `else`, `for` o `while` lleva 4 espacios más al inicio de la línea.
- Los textos van entre comillas: `"Hola"`. Unir textos: `"Hola, " + usuario.nombre`.
- Para meter valores dentro del texto, usa f-string: `f"Hola, {usuario.nombre}!"`.
- Comparar: `==` (igual), `!=` (diferente), `<`, `>`, `<=`, `>=`, `in` (está dentro).
- Variables: `total = 3`, luego `total = total + 1` (o `total += 1`).
- Listas `["a", "b"]`, diccionarios `{"clave": "valor"}`, `for elemento in lista:`.
- Los comentarios empiezan con `#`.

No existen en Python fácil: `import`, `def`, `class`, `try`, `with`, `lambda`,
`[x for x in ...]` ni nombres que empiecen con `_`. Funciones nativas permitidas: `len`,
`str`, `int`, `float`, `bool`, `round`, `abs`, `min`, `max`, `sum`, `range`, `list`,
`dict`, `tuple`, `set`, `sorted`, `reversed`, `enumerate`, `zip`, `any` y `all`, además
de los métodos comunes de texto (`.lower()`, `.split()`, `.replace()`…), lista
(`.append()`…) y diccionario (`.get()`…).

## Lo que ve el código

| Nombre | Qué es |
|---|---|
| `mensaje.tipo` | Tipo del mensaje: `texto`, `foto`, `video`, `gif`, `audio`, `voz`, `documento`, `sticker`, `ubicacion`, `contacto`, `encuesta`, `boton` u `otro`. Compara con o sin comillas: `mensaje.tipo == foto` o `== "foto"` |
| `mensaje.texto` | El texto (o el pie de la foto o video) |
| `mensaje.foto` | La foto (en la mayor resolución), o nada si no es foto |
| `mensaje.archivo` | Cualquier archivo: foto, video, audio, documento, sticker… |
| `mensaje.dato` | El dato del botón tocado (cuando `mensaje.tipo == boton`) |
| `mensaje.contacto` | El teléfono de un contacto compartido |
| `mensaje.latitud`, `mensaje.longitud` | Ubicación compartida |
| `archivo.descargar()` | Descarga el archivo al celular; devuelve el mismo archivo, ahora con `ruta` |
| `archivo.nombre`, `archivo.tamano`, `archivo.formato`, `archivo.id` | Datos del archivo |
| `usuario.nombre`, `usuario.apellido`, `usuario.username`, `usuario.id`, `usuario.idioma` | Quién lo mandó |
| `usuario.admin` | `True` si la persona es administradora del bot |
| `chat.id`, `chat.tipo`, `chat.titulo`, `chat.privado` | Dónde llegó el mensaje |
| `argumentos` | El texto después del comando (`/di hola` → `"hola"`) |
| `variables` | Diccionario guardado por persona: `variables["ciudad"] = mensaje.texto`. También funciona como `{ciudad}` en los mensajes normales |

## Funciones del bot

| Función | Qué hace |
|---|---|
| `enviar_texto(texto, botones=[...])` | Manda un mensaje. Acepta *negritas*, _cursivas_, [enlace](sitio.com)… |
| `responder(texto)` | Igual, pero como respuesta al mensaje de la persona |
| `enviar_foto(foto, leyenda="")` | Foto por enlace, por `archivo` o por ruta |
| `enviar_archivo(ruta, leyenda="")` | Manda un documento |
| `boton("Texto", enlace=..., dato=..., copiar=...)` | Crea un botón: enlace, dato (callback) o copiar texto |
| `termux(comando, arg1, arg2, ..., tiempo=30, esperar=True, entorno={})` | Corre un comando en Termux (ver abajo) |
| `detener()` | Termina el código. En *Esperar respuesta*, termina la espera |
| `pausa(segundos)` | Espera un poco (en total, hasta 60 s por ejecución) |
| `registrar(...)` | Escribe en el registro (logs) de la app |
| `base64(archivo_o_texto)` | Convierte a base64 |
| `aleatorio(1, 6)` | Número al azar entre 1 y 6 |
| `elegir(lista)` | Un elemento al azar de la lista |
| `ahora()` | Fecha y hora del celular: `"25/11/2026 14:30"` |
| `recordar(cuando_y_texto)` | Programa un recordatorio para quien escribió: `recordar("25/12 10:00 llamar a la abuela")` o `recordar(30, "sacar el pastel")` (minutos). Las fechas en palabras se entienden en portugués e inglés ("tomorrow 9am…"). Devuelve cuándo va a recordar o vacío si no entendió |
| `cancelar_recordatorios()` | Borra los recordatorios pendientes de quien escribió y dice cuántos eran |

Todas las funciones de envío aceptan `para=ID_DEL_CHAT` (o `para="@canal"`) para mandar a otra conversación, por ejemplo en una programación sin destino.

### Botones

```python
enviar_texto("¿Te gustó?", botones=[
    [boton("Sí", dato="si"), boton("No", dato="no")],
    boton("Sitio", enlace="https://ejemplo.com"),
    boton("Copiar cupón", copiar="PROMO10"),
])
```

Una lista de botones pone uno por fila; una lista dentro de la lista es una fila con
varios. Si el botón no tiene respuesta configurada en la app, el toque llega al
*Esperar respuesta* con código:

```python
if mensaje.tipo == boton:
    enviar_texto("Elegiste: " + mensaje.dato)
    detener()
```

### `termux()`

```python
r = termux("python3 ~/bot/clima.py", argumentos, tiempo=20)
enviar_texto(r.salida if r.ok else "Falló: " + r.error)
```

- Cada argumento va protegido (entre comillas) para el shell. Un **archivo**
  (`mensaje.foto`, `archivo`…) se descarga y se copia a Termux; el script recibe su ruta
  (hasta 512 KB por archivo; requiere Termux 0.118 o más nuevo).
- `tiempo`: segundos de espera por el resultado (1 a 300; por defecto 30).
- `esperar=False`: corre en segundo plano sin esperar; `termux()` no devuelve nada.
- `entorno={"CIUDAD": "Monterrey"}`: variables de entorno extra. El script también recibe
  las de siempre: `TELEBOT_ARGS`, `TELEBOT_UPDATE`, `TELEBOT_CHAT_ID`, `TELEBOT_USER_ID`…
- El resultado tiene `r.ok` (funcionó), `r.salida` (lo que imprimió el script), `r.error`
  (stderr o el motivo de la falla), `r.codigo` (código de salida) y `r.tiempo_agotado`.

Termux tiene que estar listo: ve *Termux y scripts* en la pantalla del bot.

## Límites (para que el bot nunca se trabe)

- 200 mil pasos y 10 segundos de procesamiento por ejecución (el tiempo esperando a
  Termux, a Telegram o una pausa no cuenta). Un `while True:` sin `break` se detiene aquí.
- 20 mensajes por ejecución; textos de hasta 100 mil caracteres; listas de hasta 10 mil elementos.
- Código de hasta 20 mil caracteres; `variables` de hasta 64 KB por persona.

Cuando algo sale mal, el error aparece en el registro de la app con la línea y una
explicación, en el idioma en que se escribió el código.

## Los nombres en todos los idiomas

### Objetos y funciones

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

### Opciones de las funciones

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

### Tipos de mensaje

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
