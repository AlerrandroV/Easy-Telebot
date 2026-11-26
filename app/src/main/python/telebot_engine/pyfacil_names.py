"""Nomes e mensagens do Python facilitado nos idiomas do público (pt-br, en-us, es-mx, fr, ru).

Todos os nomes valem ao mesmo tempo: dá para escrever ``mensagem.tipo`` ou
``message.type`` (ou misturar). Acentos são opcionais (``botao`` = ``botão``).
As palavras do próprio Python (``if``, ``else``, ``for``, ``while``, ``and``,
``or``, ``not``, ``True``, ``False``, ``None``) e as funções nativas (``len``,
``str``, ``int``...) continuam em inglês.

Para adicionar um idioma: acrescente a sigla em ``LANGS`` e uma entrada em cada
tabela abaixo (o primeiro nome da lista é o "oficial", usado nas sugestões).
"""

import unicodedata

LANGS = ("pt", "en", "es", "fr", "ru")


def fold(name):
    """Compara nomes sem acento e sem maiúsculas (``Botão`` = ``botao``)."""
    text = unicodedata.normalize("NFKD", str(name))
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


def _t(pt, en, es, fr, ru):
    return {"pt": pt, "en": en, "es": es, "fr": fr, "ru": ru}


# -- nomes globais (objetos e funções) --------------------------------------------------------

GLOBALS = {
    # objetos
    "message": _t(["mensagem"], ["message", "msg"], ["mensaje"], ["message"], ["сообщение"]),
    "user": _t(["usuario"], ["user"], ["usuario"], ["utilisateur"], ["пользователь"]),
    "chat": _t(["chat", "conversa"], ["chat"], ["chat"], ["chat", "discussion"], ["чат"]),
    "variables": _t(["variaveis"], ["variables", "vars"], ["variables"], ["variables"], ["переменные"]),
    "args": _t(["argumentos"], ["args", "arguments"], ["argumentos"], ["arguments"], ["аргументы"]),
    # funções
    "send_text": _t(["enviar_texto", "enviar"], ["send_text", "send"], ["enviar_texto", "enviar"],
                    ["envoyer_texte", "envoyer"], ["отправить_текст", "отправить"]),
    "reply": _t(["responder"], ["reply"], ["responder"], ["repondre"], ["ответить"]),
    "send_photo": _t(["enviar_foto"], ["send_photo"], ["enviar_foto"], ["envoyer_photo"], ["отправить_фото"]),
    "send_file": _t(["enviar_arquivo"], ["send_file"], ["enviar_archivo"], ["envoyer_fichier"],
                    ["отправить_файл"]),
    "button": _t(["botao"], ["button"], ["boton"], ["bouton"], ["кнопка"]),
    "termux": _t(["termux"], ["termux"], ["termux"], ["termux"], ["termux", "термукс"]),
    "stop": _t(["parar", "sair"], ["stop", "exit"], ["detener", "salir"], ["arreter", "sortir"],
               ["стоп", "выход"]),
    "log": _t(["registrar"], ["log", "print"], ["registrar"], ["journal"], ["журнал"]),
    "pause": _t(["pausa"], ["pause", "sleep"], ["pausa"], ["pause"], ["пауза"]),
    "base64": _t(["base64"], ["base64"], ["base64"], ["base64"], ["base64"]),
    "random": _t(["aleatorio"], ["random"], ["aleatorio"], ["aleatoire"], ["случайное"]),
    "choice": _t(["escolher"], ["choice"], ["elegir"], ["choisir"], ["выбрать"]),
    "now": _t(["agora"], ["now"], ["ahora"], ["maintenant"], ["сейчас"]),
    "remind": _t(["lembrar"], ["remind"], ["recordar"], ["rappeler"], ["напомнить"]),
    "cancel_reminders": _t(["cancelar_lembretes"], ["cancel_reminders"], ["cancelar_recordatorios"],
                           ["annuler_rappels"], ["отменить_напоминания"]),
    # constantes
    "true": _t(["verdadeiro"], ["true"], ["verdadero"], ["vrai"], ["истина"]),
    "false": _t(["falso"], ["false"], ["falso"], ["faux"], ["ложь"]),
}

# Tipos de mensagem: valem como nomes (``foto``) e como texto (``"foto"``).
KINDS = {
    "text": _t(["texto"], ["text"], ["texto"], ["texte"], ["текст"]),
    "photo": _t(["foto", "imagem"], ["photo", "image"], ["foto", "imagen"], ["photo", "image"], ["фото"]),
    "video": _t(["video"], ["video"], ["video"], ["video"], ["видео"]),
    "animation": _t(["gif", "animacao"], ["gif", "animation"], ["gif", "animacion"], ["gif", "animation"],
                    ["гиф", "анимация"]),
    "video_note": _t(["video_redondo"], ["video_note"], ["video_redondo"], ["video_rond"], ["кружок"]),
    "audio": _t(["audio", "musica"], ["audio", "music"], ["audio", "musica"], ["audio", "musique"],
                ["аудио", "музыка"]),
    "voice": _t(["voz", "audio_de_voz"], ["voice"], ["voz", "nota_de_voz"], ["vocal", "message_vocal"],
                ["голосовое"]),
    "document": _t(["documento"], ["document"], ["documento"], ["document"], ["документ"]),
    "sticker": _t(["figurinha", "sticker"], ["sticker"], ["sticker", "pegatina"], ["autocollant", "sticker"],
                  ["стикер"]),
    "location": _t(["localizacao", "local"], ["location"], ["ubicacion"], ["position"], ["геопозиция"]),
    "contact": _t(["contato"], ["contact"], ["contacto"], ["contact"], ["контакт"]),
    "poll": _t(["enquete"], ["poll"], ["encuesta"], ["sondage"], ["опрос"]),
    "button": _t(["botao"], ["button"], ["boton"], ["bouton"], ["кнопка"]),
    "other": _t(["outro"], ["other"], ["otro"], ["autre"], ["другое"]),
}

# -- atributos de cada objeto -----------------------------------------------------------------

ATTRS = {
    "message": {
        "type": _t(["tipo"], ["type", "kind"], ["tipo"], ["type"], ["тип"]),
        "text": _t(["texto"], ["text"], ["texto"], ["texte"], ["текст"]),
        "photo": _t(["foto"], ["photo"], ["foto"], ["photo"], ["фото"]),
        "file": _t(["arquivo"], ["file"], ["archivo"], ["fichier"], ["файл"]),
        "data": _t(["dado"], ["data"], ["dato"], ["donnee"], ["данные"]),
        "contact": _t(["contato", "telefone"], ["contact", "phone"], ["contacto", "telefono"],
                      ["contact", "telephone"], ["контакт", "телефон"]),
        "latitude": _t(["latitude"], ["latitude"], ["latitud"], ["latitude"], ["широта"]),
        "longitude": _t(["longitude"], ["longitude"], ["longitud"], ["longitude"], ["долгота"]),
        "id": _t(["id"], ["id"], ["id"], ["id"], ["id"]),
        "user": _t(["usuario", "remetente"], ["user", "sender"], ["usuario", "remitente"],
                   ["utilisateur", "expediteur"], ["пользователь", "отправитель"]),
        "chat": _t(["chat"], ["chat"], ["chat"], ["chat"], ["чат"]),
    },
    "file": {
        "download": _t(["baixar"], ["download"], ["descargar"], ["telecharger"], ["скачать"]),
        "id": _t(["id"], ["id"], ["id"], ["id"], ["id"]),
        "name": _t(["nome"], ["name"], ["nombre"], ["nom"], ["имя"]),
        "size": _t(["tamanho"], ["size"], ["tamano"], ["taille"], ["размер"]),
        "mime": _t(["formato", "mime"], ["mime", "format"], ["formato", "mime"], ["format", "mime"],
                   ["формат", "mime"]),
        "path": _t(["caminho"], ["path"], ["ruta"], ["chemin"], ["путь"]),
    },
    "user": {
        "id": _t(["id"], ["id"], ["id"], ["id"], ["id"]),
        "name": _t(["nome"], ["name", "first_name"], ["nombre"], ["prenom"], ["имя"]),
        "last_name": _t(["sobrenome"], ["last_name", "surname"], ["apellido"], ["nom_de_famille"], ["фамилия"]),
        "username": _t(["arroba", "username"], ["username"], ["username", "arroba"], ["pseudo", "username"],
                       ["логин", "username"]),
        "language": _t(["idioma"], ["language"], ["idioma"], ["langue"], ["язык"]),
        "admin": _t(["admin", "e_admin"], ["admin", "is_admin"], ["admin", "es_admin"], ["admin", "est_admin"],
                    ["админ"]),
    },
    "chat": {
        "id": _t(["id"], ["id"], ["id"], ["id"], ["id"]),
        "type": _t(["tipo"], ["type"], ["tipo"], ["type"], ["тип"]),
        "title": _t(["titulo", "nome"], ["title", "name"], ["titulo", "nombre"], ["titre", "nom"],
                    ["название"]),
        "private": _t(["privado"], ["private", "is_private"], ["privado"], ["prive"], ["личный"]),
    },
    "result": {
        "output": _t(["saida"], ["output", "stdout"], ["salida"], ["sortie"], ["вывод"]),
        "error": _t(["erro"], ["error", "stderr"], ["error"], ["erreur"], ["ошибка"]),
        "code": _t(["codigo"], ["code", "exit_code"], ["codigo"], ["code"], ["код"]),
        "ok": _t(["ok", "sucesso"], ["ok", "success"], ["ok", "exito"], ["ok", "succes"], ["ок", "успех"]),
        "timed_out": _t(["tempo_esgotado"], ["timed_out"], ["tiempo_agotado"], ["delai_depasse"],
                        ["время_вышло"]),
    },
}

# -- argumentos nomeados ----------------------------------------------------------------------

KWARGS = {
    "buttons": _t(["botoes"], ["buttons"], ["botones"], ["boutons"], ["кнопки"]),
    "caption": _t(["legenda"], ["caption"], ["leyenda"], ["legende"], ["подпись"]),
    "timeout": _t(["tempo", "limite"], ["timeout"], ["tiempo", "limite"], ["delai"], ["время", "таймаут"]),
    "wait": _t(["esperar"], ["wait"], ["esperar"], ["attendre"], ["ждать"]),
    "env": _t(["ambiente"], ["env", "environment"], ["entorno"], ["environnement"], ["окружение"]),
    "link": _t(["link"], ["link", "url"], ["enlace", "link"], ["lien"], ["ссылка"]),
    "data": _t(["dado"], ["data"], ["dato"], ["donnee"], ["данные"]),
    "copy": _t(["copiar"], ["copy"], ["copiar"], ["copier"], ["копировать"]),
    "to": _t(["para"], ["to", "chat_id"], ["para"], ["vers", "destinataire"], ["кому"]),
}

# -- mensagens de erro ------------------------------------------------------------------------

ERRORS = {
    "syntax": _t(
        "Linha {line}: o código não está bem escrito ({detail}). Confira parênteses, aspas, dois-pontos e a indentação.",
        "Line {line}: the code is not well formed ({detail}). Check parentheses, quotes, colons and indentation.",
        "Línea {line}: el código no está bien escrito ({detail}). Revisa paréntesis, comillas, dos puntos y la sangría.",
        "Ligne {line} : le code est mal écrit ({detail}). Vérifiez les parenthèses, les guillemets, les deux-points et l'indentation.",
        "Строка {line}: код написан с ошибкой ({detail}). Проверьте скобки, кавычки, двоеточия и отступы."),
    "not_allowed": _t(
        "Linha {line}: \"{what}\" não existe no Python facilitado. Use só if/else, for, while, variáveis e as funções do bot.",
        "Line {line}: \"{what}\" is not available in Simple Python. Use only if/else, for, while, variables and the bot functions.",
        "Línea {line}: \"{what}\" no existe en Python fácil. Usa solo if/else, for, while, variables y las funciones del bot.",
        "Ligne {line} : « {what} » n'existe pas en Python simplifié. Utilisez seulement if/else, for, while, des variables et les fonctions du bot.",
        "Строка {line}: «{what}» нет в упрощённом Python. Используйте только if/else, for, while, переменные и функции бота."),
    "unknown_name": _t(
        "Linha {line}: \"{name}\" não existe.{hint} Confira se está escrito certo ou se a variável recebeu um valor antes.",
        "Line {line}: \"{name}\" does not exist.{hint} Check the spelling, or give the variable a value first.",
        "Línea {line}: \"{name}\" no existe.{hint} Revisa cómo está escrito o si la variable recibió un valor antes.",
        "Ligne {line} : « {name} » n'existe pas.{hint} Vérifiez l'orthographe ou donnez d'abord une valeur à la variable.",
        "Строка {line}: «{name}» не существует.{hint} Проверьте написание или сначала задайте переменной значение."),
    "unknown_attr": _t(
        "Linha {line}: {obj} não tem \"{attr}\". Pode usar: {options}.",
        "Line {line}: {obj} has no \"{attr}\". You can use: {options}.",
        "Línea {line}: {obj} no tiene \"{attr}\". Puedes usar: {options}.",
        "Ligne {line} : {obj} n'a pas « {attr} ». Vous pouvez utiliser : {options}.",
        "Строка {line}: у {obj} нет «{attr}». Можно использовать: {options}."),
    "unknown_kwarg": _t(
        "Linha {line}: {func}() não tem a opção \"{name}\". Opções: {options}.",
        "Line {line}: {func}() has no option \"{name}\". Options: {options}.",
        "Línea {line}: {func}() no tiene la opción \"{name}\". Opciones: {options}.",
        "Ligne {line} : {func}() n'a pas l'option « {name} ». Options : {options}.",
        "Строка {line}: у {func}() нет параметра «{name}». Параметры: {options}."),
    "bad_call": _t(
        "Linha {line}: {func}() recebeu valores errados: {detail}",
        "Line {line}: {func}() got wrong values: {detail}",
        "Línea {line}: {func}() recibió valores incorrectos: {detail}",
        "Ligne {line} : {func}() a reçu de mauvaises valeurs : {detail}",
        "Строка {line}: {func}() получила неверные значения: {detail}"),
    "not_callable": _t(
        "Linha {line}: \"{name}\" não é uma função (não dá para usar parênteses depois dele).",
        "Line {line}: \"{name}\" is not a function (you can't put parentheses after it).",
        "Línea {line}: \"{name}\" no es una función (no se pueden poner paréntesis después).",
        "Ligne {line} : « {name} » n'est pas une fonction (pas de parenthèses après).",
        "Строка {line}: «{name}» — не функция (после него нельзя ставить скобки)."),
    "read_only": _t(
        "Linha {line}: não dá para mudar \"{name}\". Guarde o valor numa variável sua.",
        "Line {line}: \"{name}\" can't be changed. Store the value in a variable of your own.",
        "Línea {line}: no se puede cambiar \"{name}\". Guarda el valor en una variable tuya.",
        "Ligne {line} : impossible de modifier « {name} ». Mettez la valeur dans votre propre variable.",
        "Строка {line}: «{name}» нельзя изменить. Сохраните значение в своей переменной."),
    "type": _t(
        "Linha {line}: operação com tipos que não combinam ({detail}). Dica: use str() para juntar número com texto e int() para fazer contas com texto.",
        "Line {line}: operation with types that don't match ({detail}). Tip: use str() to join a number with text and int() to do math with text.",
        "Línea {line}: operación con tipos que no combinan ({detail}). Consejo: usa str() para unir número y texto, e int() para hacer cuentas con texto.",
        "Ligne {line} : opération entre types incompatibles ({detail}). Astuce : str() pour coller un nombre à du texte, int() pour calculer avec du texte.",
        "Строка {line}: несовместимые типы ({detail}). Совет: str() — чтобы соединить число с текстом, int() — чтобы считать с текстом."),
    "value": _t(
        "Linha {line}: valor inválido ({detail}).",
        "Line {line}: invalid value ({detail}).",
        "Línea {line}: valor inválido ({detail}).",
        "Ligne {line} : valeur invalide ({detail}).",
        "Строка {line}: недопустимое значение ({detail})."),
    "zero": _t(
        "Linha {line}: divisão por zero.",
        "Line {line}: division by zero.",
        "Línea {line}: división entre cero.",
        "Ligne {line} : division par zéro.",
        "Строка {line}: деление на ноль."),
    "index": _t(
        "Linha {line}: posição {detail} não existe (a lista ou o texto é menor).",
        "Line {line}: position {detail} does not exist (the list or text is shorter).",
        "Línea {line}: la posición {detail} no existe (la lista o el texto es más corto).",
        "Ligne {line} : la position {detail} n'existe pas (la liste ou le texte est plus court).",
        "Строка {line}: позиции {detail} нет (список или текст короче)."),
    "key": _t(
        "Linha {line}: a chave {detail} não existe. Dica: use .get({detail}) para ter um valor vazio em vez de erro.",
        "Line {line}: key {detail} does not exist. Tip: use .get({detail}) to get an empty value instead of an error.",
        "Línea {line}: la clave {detail} no existe. Consejo: usa .get({detail}) para obtener un valor vacío en vez de un error.",
        "Ligne {line} : la clé {detail} n'existe pas. Astuce : .get({detail}) donne une valeur vide au lieu d'une erreur.",
        "Строка {line}: ключа {detail} нет. Совет: .get({detail}) вернёт пустое значение вместо ошибки."),
    "steps": _t(
        "O código passou do limite de {n} passos. Provavelmente um laço (while/for) nunca termina.",
        "The code went over the limit of {n} steps. Probably a loop (while/for) never ends.",
        "El código pasó el límite de {n} pasos. Probablemente un bucle (while/for) nunca termina.",
        "Le code a dépassé la limite de {n} étapes. Une boucle (while/for) ne se termine sans doute jamais.",
        "Код превысил лимит в {n} шагов. Вероятно, цикл (while/for) никогда не заканчивается."),
    "slow": _t(
        "O código passou do limite de {n} segundos de processamento.",
        "The code went over the limit of {n} seconds of processing.",
        "El código pasó el límite de {n} segundos de procesamiento.",
        "Le code a dépassé la limite de {n} secondes de traitement.",
        "Код превысил лимит в {n} секунд обработки."),
    "too_big": _t(
        "Linha {line}: texto ou lista grande demais (limite de {n} itens/caracteres).",
        "Line {line}: text or list too big (limit of {n} items/characters).",
        "Línea {line}: texto o lista demasiado grande (límite de {n} elementos/caracteres).",
        "Ligne {line} : texte ou liste trop grand (limite de {n} éléments/caractères).",
        "Строка {line}: слишком большой текст или список (лимит {n} элементов/символов)."),
    "too_long": _t(
        "O código é longo demais (limite de {n} caracteres).",
        "The code is too long (limit of {n} characters).",
        "El código es demasiado largo (límite de {n} caracteres).",
        "Le code est trop long (limite de {n} caractères).",
        "Код слишком длинный (лимит {n} символов)."),
    "too_deep": _t(
        "Linha {line}: expressão com camadas demais (parênteses ou if dentro de if). Divida em partes.",
        "Line {line}: expression nested too deep (parentheses or if inside if). Split it into parts.",
        "Línea {line}: expresión con demasiados niveles (paréntesis o if dentro de if). Divídela en partes.",
        "Ligne {line} : expression trop imbriquée (parenthèses ou if dans un if). Découpez-la.",
        "Строка {line}: слишком глубокая вложенность (скобки или if внутри if). Разбейте на части."),
    "sends": _t(
        "Linha {line}: limite de {n} mensagens por execução (proteção contra flood).",
        "Line {line}: limit of {n} messages per run (flood protection).",
        "Línea {line}: límite de {n} mensajes por ejecución (protección contra flood).",
        "Ligne {line} : limite de {n} messages par exécution (protection anti-flood).",
        "Строка {line}: лимит {n} сообщений за запуск (защита от флуда)."),
    "no_file": _t(
        "Linha {line}: esta mensagem não tem arquivo para baixar. Confira o tipo antes (if mensagem.tipo == foto:).",
        "Line {line}: this message has no file to download. Check the type first (if message.type == photo:).",
        "Línea {line}: este mensaje no tiene archivo para descargar. Revisa el tipo antes (if mensaje.tipo == foto:).",
        "Ligne {line} : ce message n'a pas de fichier à télécharger. Vérifiez le type avant (if message.type == photo:).",
        "Строка {line}: в этом сообщении нет файла. Сначала проверьте тип (if сообщение.тип == фото:)."),
    "download": _t(
        "Linha {line}: não deu para baixar o arquivo do Telegram ({detail}).",
        "Line {line}: could not download the file from Telegram ({detail}).",
        "Línea {line}: no se pudo descargar el archivo de Telegram ({detail}).",
        "Ligne {line} : impossible de télécharger le fichier depuis Telegram ({detail}).",
        "Строка {line}: не удалось скачать файл из Telegram ({detail})."),
    "file_big": _t(
        "Linha {line}: arquivo grande demais para passar ao Termux ({size} KB; limite {limit} KB). Para arquivos grandes, baixe pelo próprio script com a API local.",
        "Line {line}: file too big to pass to Termux ({size} KB; limit {limit} KB). For big files, download them in the script itself through the local API.",
        "Línea {line}: archivo demasiado grande para pasar a Termux ({size} KB; límite {limit} KB). Para archivos grandes, descárgalos en el propio script con la API local.",
        "Ligne {line} : fichier trop gros pour Termux ({size} Ko ; limite {limit} Ko). Pour les gros fichiers, téléchargez-les dans le script via l'API locale.",
        "Строка {line}: файл слишком большой для Termux ({size} КБ; лимит {limit} КБ). Большие файлы скачивайте в самом скрипте через локальный API."),
    "env_name": _t(
        "Linha {line}: o nome \"{name}\" não serve para variável de ambiente (use LETRAS_MAIUSCULAS, números e _).",
        "Line {line}: \"{name}\" is not a valid environment variable name (use UPPERCASE_LETTERS, digits and _).",
        "Línea {line}: \"{name}\" no sirve como variable de entorno (usa LETRAS_MAYÚSCULAS, números y _).",
        "Ligne {line} : « {name} » n'est pas un nom de variable d'environnement valide (MAJUSCULES, chiffres et _).",
        "Строка {line}: «{name}» не подходит как имя переменной окружения (ЗАГЛАВНЫЕ_БУКВЫ, цифры и _)."),
    "variables": _t(
        "As variáveis guardadas passaram do limite de {n} KB ou têm um valor que não dá para guardar (só texto, número, lista e dicionário).",
        "The stored variables went over the {n} KB limit or hold a value that can't be stored (only text, numbers, lists and dictionaries).",
        "Las variables guardadas pasaron el límite de {n} KB o tienen un valor que no se puede guardar (solo texto, números, listas y diccionarios).",
        "Les variables enregistrées dépassent {n} Ko ou contiennent une valeur impossible à enregistrer (texte, nombres, listes et dictionnaires seulement).",
        "Сохранённые переменные превысили {n} КБ или содержат значение, которое нельзя сохранить (только текст, числа, списки и словари)."),
    "no_chat": _t(
        "Linha {line}: aqui não há conversa para responder (agendamento sem destino). Diga para onde: para=ID_DO_CHAT.",
        "Line {line}: there is no chat to reply to here (schedule without a target). Say where: to=CHAT_ID.",
        "Línea {line}: aquí no hay conversación a la que responder (programación sin destino). Indica a dónde: para=ID_DEL_CHAT.",
        "Ligne {line} : pas de conversation à laquelle répondre ici (programmation sans destination). Indiquez où : vers=ID_DU_CHAT.",
        "Строка {line}: здесь нет чата для ответа (расписание без получателя). Укажите куда: кому=ID_ЧАТА."),
    "internal": _t(
        "Linha {line}: erro inesperado ({detail}).",
        "Line {line}: unexpected error ({detail}).",
        "Línea {line}: error inesperado ({detail}).",
        "Ligne {line} : erreur inattendue ({detail}).",
        "Строка {line}: непредвиденная ошибка ({detail})."),
}

HINT = _t(" Você quis dizer \"{name}\"?", " Did you mean \"{name}\"?", " ¿Quisiste decir \"{name}\"?",
          " Vouliez-vous dire « {name} » ?", " Возможно, вы имели в виду «{name}»?")

# Nome amigável dos objetos nas mensagens de erro.
OBJECT_LABELS = {
    "message": GLOBALS["message"], "user": GLOBALS["user"], "chat": GLOBALS["chat"],
    "file": ATTRS["message"]["file"],
    "result": _t(["o resultado do termux()"], ["the termux() result"], ["el resultado de termux()"],
                 ["le résultat de termux()"], ["результат termux()"]),
}

# Comandos "proibidos" com nome amigável na mensagem de erro.
FORBIDDEN_LABELS = {
    "Import": "import", "ImportFrom": "import", "FunctionDef": "def", "AsyncFunctionDef": "async def",
    "ClassDef": "class", "Lambda": "lambda", "With": "with", "AsyncWith": "async with", "Try": "try",
    "TryStar": "try", "Raise": "raise", "Global": "global", "Nonlocal": "nonlocal", "Delete": "del",
    "Yield": "yield", "YieldFrom": "yield", "Await": "await", "Return": "return", "Assert": "assert",
    "ListComp": "[... for ...]", "SetComp": "{... for ...}", "DictComp": "{... for ...}",
    "GeneratorExp": "(... for ...)", "NamedExpr": ":=", "Starred": "*", "Match": "match",
}


def _reverse(table):
    out = {}
    for canonical, langs in table.items():
        for names in langs.values():
            for n in names:
                out.setdefault(fold(n), canonical)
    return out


GLOBAL_INDEX = _reverse(GLOBALS)
KIND_INDEX = _reverse(KINDS)
ATTR_INDEX = {obj: _reverse(table) for obj, table in ATTRS.items()}
KWARG_INDEX = _reverse(KWARGS)


def name_in(table, canonical, lang):
    """Nome "oficial" de ``canonical`` no idioma ``lang``."""
    names = table.get(canonical, {}).get(lang) or table.get(canonical, {}).get("en") or [canonical]
    return names[0]


def languages_of(table, folded):
    """Idiomas em que ``folded`` é um nome da tabela (para adivinhar o idioma do código)."""
    out = set()
    for langs in table.values():
        for lang, names in langs.items():
            if any(fold(n) == folded for n in names):
                out.add(lang)
    return out
