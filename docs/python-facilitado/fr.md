# Python simplifié

**Langues :** [Português (Brasil)](pt-BR.md) · [English (US)](en-US.md) · [Español (México)](es-MX.md) · Français · [Русский](ru.md)

Le Python simplifié est un morceau de Python pensé pour celles et ceux qui ne programment
pas. Vous écrivez quelques lignes et le bot décide quoi répondre : « si la personne a
envoyé une photo, fais ceci ; sinon, fais cela ». Le code tourne sur le téléphone, dans
l'application, avec des limites de sécurité : il ne touche pas aux fichiers du téléphone
et n'accède pas à Internet tout seul. C'est le rôle de `termux()`.

Les noms fonctionnent en **français, portugais, anglais, espagnol et russe** en même
temps, et les accents sont facultatifs (`arreter` = `arrêter`). Seuls les mots propres à
Python restent en anglais : `if`, `elif`, `else`, `for`, `in`, `while`, `break`,
`continue`, `and`, `or`, `not`, `True`, `False` et `None`.

> Pour l'instant, l'application est en portugais et en anglais. Les noms français du
> Python simplifié fonctionnent quelle que soit la langue de l'application.

## Où l'utiliser

- **Bloc « Python simplifié »** (dans l'application : *Python*) dans une
  commande : touchez *Ajouter* et choisissez le bloc. Le code tourne
  quand quelqu'un utilise la commande. `arreter()` termine les blocs qui suivent.
- **Bloc « Attendre la réponse »** (*Wait for reply* / *Aguardar resposta*) : dans le menu
  à trois points, choisissez d'utiliser Python. Le code tourne **à chaque message** envoyé
  par la personne (et à chaque bouton sans réponse propre qu'elle touche), jusqu'à ce que
  le code appelle `arreter()`. Si la personne envoie une commande, l'attente est annulée.

L'éditeur vérifie le code pendant la saisie et explique chaque erreur avec le numéro de
ligne. Le bouton d'indentation ajoute 4 espaces, et Entrée reprend l'indentation de la
ligne du dessus (plus 4 espaces après une ligne qui finit par `:`).

## Exemple : changer la photo de profil avec Termux

Commande `/photo` avec deux blocs : un message « Envoie-moi la nouvelle photo » et un
*Attendre la réponse* avec ce code :

```python
if message.type == photo:
    fichier = message.photo.telecharger()
    r = termux("node maj_photo_profil.js", fichier, delai=60)
    if r.ok:
        envoyer_texte("Photo mise à jour avec succès !")
    else:
        envoyer_texte("Ça n'a pas marché : " + r.erreur)
    arreter()  # fin de l'attente
else:
    envoyer_texte("Ce n'est pas une image. Merci d'envoyer une photo valide !")
```

Tant que la personne envoie autre chose, le bot redemande la photo. Quand la photo
arrive, l'application télécharge le fichier, le passe au script dans Termux et termine
l'attente.

## Les bases de l'écriture

- Ce qui est **à l'intérieur** d'un `if`, `else`, `for` ou `while` prend 4 espaces de plus en début de ligne.
- Les textes vont entre guillemets : `"Bonjour"`. Assembler des textes : `"Bonjour, " + utilisateur.prenom`.
- Pour mettre des valeurs dans un texte, utilisez une f-string : `f"Bonjour, {utilisateur.prenom} !"`.
- Comparer : `==` (égal), `!=` (différent), `<`, `>`, `<=`, `>=`, `in` (est dedans).
- Variables : `total = 3`, puis `total = total + 1` (ou `total += 1`).
- Listes `["a", "b"]`, dictionnaires `{"cle": "valeur"}`, `for element in liste:`.
- Les commentaires commencent par `#`.

N'existent pas en Python simplifié : `import`, `def`, `class`, `try`, `with`, `lambda`,
`[x for x in ...]` ni les noms qui commencent par `_`. Fonctions natives autorisées :
`len`, `str`, `int`, `float`, `bool`, `round`, `abs`, `min`, `max`, `sum`, `range`,
`list`, `dict`, `tuple`, `set`, `sorted`, `reversed`, `enumerate`, `zip`, `any` et `all`,
plus les méthodes courantes de texte (`.lower()`, `.split()`, `.replace()`…), de liste
(`.append()`…) et de dictionnaire (`.get()`…).

## Ce que le code voit

| Nom | Ce que c'est |
|---|---|
| `message.type` | Type du message : `texte`, `photo`, `video`, `gif`, `audio`, `vocal`, `document`, `autocollant`, `position`, `contact`, `sondage`, `bouton` ou `autre`. Comparez avec ou sans guillemets : `message.type == photo` ou `== "photo"` |
| `message.texte` | Le texte (ou la légende du média) |
| `message.photo` | La photo (dans la plus grande résolution), ou rien si ce n'est pas une photo |
| `message.fichier` | N'importe quel média : photo, vidéo, audio, document, autocollant… |
| `message.donnee` | La donnée du bouton touché (quand `message.type == bouton`) |
| `message.contact` | Le téléphone d'un contact partagé |
| `message.latitude`, `message.longitude` | Position partagée |
| `fichier.telecharger()` | Télécharge le fichier sur le téléphone ; renvoie le même fichier, avec un `chemin` |
| `fichier.nom`, `fichier.taille`, `fichier.format`, `fichier.id` | Infos du fichier |
| `utilisateur.prenom`, `utilisateur.nom_de_famille`, `utilisateur.pseudo`, `utilisateur.id`, `utilisateur.langue` | Qui a envoyé |
| `utilisateur.admin` | `True` si la personne est administratrice du bot |
| `chat.id`, `chat.type`, `chat.titre`, `chat.prive` | Où le message est arrivé |
| `arguments` | Le texte après la commande (`/dis bonjour` → `"bonjour"`) |
| `variables` | Dictionnaire gardé par personne : `variables["ville"] = message.texte`. Marche aussi comme `{ville}` dans les messages normaux |

## Fonctions du bot

| Fonction | Ce qu'elle fait |
|---|---|
| `envoyer_texte(texte, boutons=[...])` | Envoie un message. Accepte *gras*, _italique_, [lien](site.com)… |
| `repondre(texte)` | Pareil, mais en réponse au message de la personne |
| `envoyer_photo(photo, legende="")` | Une photo par lien, par `fichier` ou par chemin |
| `envoyer_fichier(chemin, legende="")` | Envoie un document |
| `bouton("Texte", lien=..., donnee=..., copier=...)` | Crée un bouton : lien, donnée (callback) ou copier un texte |
| `termux(commande, arg1, arg2, ..., delai=30, attendre=True, environnement={})` | Lance une commande dans Termux (voir plus bas) |
| `arreter()` | Termine le code. Dans *Attendre la réponse*, termine l'attente |
| `pause(secondes)` | Attend un peu (au total, jusqu'à 60 s par exécution) |
| `journal(...)` | Écrit dans le journal (logs) de l'application |
| `base64(fichier_ou_texte)` | Convertit en base64 |
| `aleatoire(1, 6)` | Un nombre au hasard entre 1 et 6 |
| `choisir(liste)` | Un élément au hasard de la liste |
| `maintenant()` | Date et heure du téléphone : `"25/11/2026 14:30"` |
| `rappeler(quand_et_texte)` | Programme un rappel pour l'expéditeur : `rappeler("25/12 10:00 appeler mamie")` ou `rappeler(30, "sortir le gâteau")` (minutes). Les dates en toutes lettres sont comprises en portugais et en anglais ("tomorrow 9am…"). Renvoie quand le rappel aura lieu, ou vide s'il n'a pas compris |
| `annuler_rappels()` | Supprime les rappels en attente de l'expéditeur et dit combien il y en avait |

Toutes les fonctions d'envoi acceptent `vers=ID_DU_CHAT` (ou `vers="@canal"`) pour envoyer dans une autre conversation, par exemple dans une programmation sans destination.

### Boutons

```python
envoyer_texte("Ça vous a plu ?", boutons=[
    [bouton("Oui", donnee="oui"), bouton("Non", donnee="non")],
    bouton("Site", lien="https://exemple.fr"),
    bouton("Copier le code promo", copier="PROMO10"),
])
```

Une liste de boutons en met un par ligne ; une liste dans la liste forme une ligne de
plusieurs boutons. Si le bouton n'a pas de réponse configurée dans l'application, le
toucher arrive à *Attendre la réponse* avec du code :

```python
if message.type == bouton:
    envoyer_texte("Vous avez choisi : " + message.donnee)
    arreter()
```

### `termux()`

```python
r = termux("python3 ~/bot/meteo.py", arguments, delai=20)
envoyer_texte(r.sortie if r.ok else "Échec : " + r.erreur)
```

- Chaque argument est protégé (entre guillemets) pour le shell. Un **fichier**
  (`message.photo`, `fichier`…) est téléchargé et copié dans Termux ; le script reçoit son
  chemin (jusqu'à 512 Ko par fichier ; Termux 0.118 ou plus récent).
- `delai` : secondes d'attente du résultat (1 à 300 ; 30 par défaut).
- `attendre=False` : tourne en arrière-plan sans attendre ; `termux()` ne renvoie rien.
- `environnement={"VILLE": "Lyon"}` : variables d'environnement en plus. Le script reçoit
  aussi les habituelles : `TELEBOT_ARGS`, `TELEBOT_UPDATE`, `TELEBOT_CHAT_ID`, `TELEBOT_USER_ID`…
- Le résultat a `r.ok` (ça a marché), `r.sortie` (ce que le script a affiché), `r.erreur`
  (stderr ou la raison de l'échec), `r.code` (code de sortie) et `r.delai_depasse`.

Termux doit être prêt : voir *Termux et scripts* sur l'écran du bot.

## Limites (pour que le bot ne bloque jamais)

- 200 000 étapes et 10 secondes de traitement par exécution (le temps passé à attendre
  Termux, Telegram ou une pause ne compte pas). Un `while True:` sans `break` s'arrête ici.
- 20 messages par exécution ; textes jusqu'à 100 000 caractères ; listes jusqu'à 10 000 éléments.
- Code jusqu'à 20 000 caractères ; `variables` jusqu'à 64 Ko par personne.

Quand quelque chose ne va pas, l'erreur apparaît dans le journal de l'application avec la
ligne et une explication, dans la langue du code.

## Les noms dans toutes les langues

### Objets et fonctions

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

### Attributs

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

### Options des fonctions

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

### Types de message

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
