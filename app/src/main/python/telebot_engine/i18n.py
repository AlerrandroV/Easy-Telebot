"""Textos do motor em vários idiomas (por enquanto pt-br e en-us).

Dois tipos de texto:
- o que o **bot manda no Telegram** (loja, antispam, publicação...): idioma do bot
  (``config["language"]``; vazio = idioma do app);
- detalhes de erro que aparecem **no app**: idioma do app (``set_app_language``,
  chamado pelo Kotlin ao iniciar).

Para adicionar um idioma: inclua a sigla em ``SUPPORTED`` e a tradução em cada
entrada de ``TEXTS`` (o que faltar cai no português).
"""

SUPPORTED = ("pt", "en")
FALLBACK = "pt"
_app = FALLBACK


def normalize(code):
    """``"pt-BR"`` → ``"pt"``; idioma sem suporte → ``""``."""
    c = str(code or "").strip().lower().replace("_", "-").split("-")[0]
    return c if c in SUPPORTED else ""


def set_app_language(code):
    global _app
    _app = normalize(code) or FALLBACK
    return _app


def app_language():
    return _app


def bot_language(bot_dir):
    """Idioma do bot salvo no ``config.json`` da pasta dele (para funções chamadas pelo app)."""
    import json
    import os
    try:
        with open(os.path.join(bot_dir, "config.json"), "r", encoding="utf-8") as fh:
            return pick(json.load(fh).get("language"))
    except (OSError, ValueError, AttributeError):
        return _app


def pick(code=None):
    return normalize(code) or _app


def t(key, lang=None, *args):
    """Texto ``key`` no idioma ``lang`` (``None`` = idioma do app), com ``%`` se houver ``args``."""
    table = TEXTS.get(key)
    if table is None:
        return key
    text = table.get(pick(lang)) or table[FALLBACK]
    return text % args if args else text


def _t(pt, en):
    return {"pt": pt, "en": en}


TEXTS = {
    # -- publicação em massa pelo Telegram (/post) --
    "post.intro": _t(
        "📢 <b>Modo publicação</b>\n\n"
        "Envie agora as mensagens da publicação, do jeito que quiser: texto, fotos, vídeos, "
        "documentos, áudios, figurinhas, álbuns… Elas serão enviadas <b>sem mostrar quem escreveu</b>.\n\n"
        "🔘 <b>Botões</b>: no fim do texto ou da legenda, escreva linhas assim:\n"
        "<code>[Site | https://exemplo.com] [Canal | @meucanal]</code>\n"
        "<code>[Grupo | t.me/meugrupo]</code>\n"
        "Cada linha vira uma fileira de botões. Uma mensagem só com essas linhas coloca os botões "
        "na mensagem anterior (útil para foto sem legenda).\n\n"
        "Quando terminar, mande /pronto para ver a prévia. Para desistir: /cancelar.",
        "📢 <b>Broadcast mode</b>\n\n"
        "Now send the broadcast messages however you like: text, photos, videos, documents, "
        "audio, stickers, albums… They will be sent <b>without showing who wrote them</b>.\n\n"
        "🔘 <b>Buttons</b>: at the end of the text or caption, write lines like these:\n"
        "<code>[Site | https://example.com] [Channel | @mychannel]</code>\n"
        "<code>[Group | t.me/mygroup]</code>\n"
        "Each line becomes a row of buttons. A message with only these lines puts the buttons "
        "on the previous message (useful for a photo without a caption).\n\n"
        "When you're done, send /done to see the preview. To give up: /cancel."),
    "post.album_buttons": _t(
        "⚠️ Álbuns (várias fotos juntas) não aceitam botões: as linhas de botão dentro de um álbum ficarão como texto.",
        "⚠️ Albums (several photos together) don't accept buttons: button lines inside an album will stay as text."),
    "post.orphan_buttons": _t(
        "⚠️ Uma mensagem só com botões não tinha uma mensagem antes para recebê-los (ou veio depois de um álbum) e foi ignorada.",
        "⚠️ A message with only buttons had no message before it to receive them (or came after an album) and was ignored."),
    "post.already": _t(
        "Você já está montando uma publicação. Continue enviando, mande /pronto para ver a prévia ou /cancelar.",
        "You're already building a broadcast. Keep sending, send /done to see the preview or /cancel."),
    "post.stopped": _t("⏹️ Envio interrompido. O resumo chega em instantes.",
                       "⏹️ Sending stopped. The summary arrives in a moment."),
    "post.received": _t("📥 Recebido! Continue enviando ou mande /pronto.",
                        "📥 Got it! Keep sending or send /done."),
    "post.cancelled": _t("🗑️ Publicação cancelada. Nada foi enviado.", "🗑️ Broadcast cancelled. Nothing was sent."),
    "post.nothing_yet": _t(
        "Você ainda não enviou nenhuma mensagem para a publicação. Envie as mensagens e depois mande /pronto.",
        "You haven't sent any message for the broadcast yet. Send the messages and then send /done."),
    "post.nothing_left": _t("Não sobrou nada para enviar. Envie as mensagens de novo ou /cancelar.",
                            "Nothing left to send. Send the messages again or /cancel."),
    "post.preview": _t("👀 <b>Prévia</b> — é assim que a publicação vai chegar:",
                       "👀 <b>Preview</b> — this is how the broadcast will arrive:"),
    "post.preview_failed": _t(
        "❌ O Telegram recusou a prévia (%s). Confira os botões e as mensagens, mande /cancelar e comece de novo.",
        "❌ Telegram rejected the preview (%s). Check the buttons and messages, send /cancel and start again."),
    "post.summary": _t(
        "✅ Prévia acima (%d mensagem(ns)).\n"
        "Destinos: 👤 %d usuário(s) · 👥 %d grupo(s) · 📢 %d canal(is)\n"
        "Para enviar para <b>todos</b>, mande:\n<code>/confirmar %s</code>\n"
        "Para escolher: <code>/confirmar %s usuarios</code> (ou <code>grupos</code>, <code>canais</code>; pode combinar).\n"
        "Quer mudar algo? Envie mais mensagens e mande /pronto de novo, ou /cancelar.",
        "✅ Preview above (%d message(s)).\n"
        "Recipients: 👤 %d user(s) · 👥 %d group(s) · 📢 %d channel(s)\n"
        "To send to <b>everyone</b>, send:\n<code>/confirm %s</code>\n"
        "To choose: <code>/confirm %s users</code> (or <code>groups</code>, <code>channels</code>; they can be combined).\n"
        "Want to change something? Send more messages and /done again, or /cancel."),
    "post.bad_button": _t("⚠️ Linha de botão com link inválido (ficou como texto): <code>%s</code>",
                          "⚠️ Button line with an invalid link (kept as text): <code>%s</code>"),
    "post.need_preview": _t("Mande /pronto primeiro para ver a prévia e receber o código.",
                            "Send /done first to see the preview and get the code."),
    "post.wrong_code": _t("❌ Código incorreto. Use o código da última prévia (mande /pronto para ver de novo).",
                          "❌ Wrong code. Use the code from the latest preview (send /done to see it again)."),
    "post.no_audience": _t(
        "Não há destinos desse tipo ainda. O bot só conhece quem já falou com ele e os grupos/canais onde ele foi adicionado.",
        "There are no recipients of this type yet. The bot only knows people who talked to it and the groups/channels it was added to."),
    "post.progress": _t("🚀 Enviando… %d de %d (%d falha(s))", "🚀 Sending… %d of %d (%d failure(s))"),
    "post.done_title": _t("✅ <b>Publicação concluída!</b>", "✅ <b>Broadcast finished!</b>"),
    "post.cancelled_title": _t("⏹️ <b>Envio interrompido.</b>", "⏹️ <b>Sending stopped.</b>"),
    "post.finished_title": _t("Publicação finalizada.", "Broadcast ended."),
    "post.delivered": _t("Entregues: %d de %d", "Delivered: %d of %d"),
    "post.failed": _t("Falharam: %d", "Failed: %d"),
    "post.unreachable": _t("(%d bloquearam o bot ou o removeram; não receberão as próximas)",
                           "(%d blocked or removed the bot; they won't get the next ones)"),
    "post.busy": _t("⏳ Já existe uma publicação sendo enviada por este bot. Espere terminar.",
                    "⏳ This bot is already sending a broadcast. Wait for it to finish."),
    "post.sending": _t("🚀 Enviando para %d chat(s)… Você pode continuar usando o Telegram. Para interromper: /cancelar.",
                       "🚀 Sending to %d chat(s)… You can keep using Telegram. To stop: /cancel."),
    "post.menu_description": _t("Publicação em massa", "Broadcast"),

    # -- formatos --
    "fmt.date": _t("%d/%m/%Y", "%m/%d/%Y"),

    # -- rótulos internos que aparecem no registro do app --
    "label.button": _t("botão", "button"),
    "label.answer": _t("resposta", "reply"),
    "label.flow": _t("fluxo", "flow"),
    "token.bad_format": _t("formato inválido", "invalid format"),
    "admin.welcome": _t("Parabéns! Agora você é um administrador!", "Congratulations! You are now an administrator!"),

    # -- perguntas dos fluxos antigos --
    "flow.invalid_text": _t("Mande a resposta em texto, por favor.", "Please send the answer as text."),
    "flow.invalid_number": _t("Mande só números, por favor.", "Please send numbers only."),
    "flow.invalid_email": _t("Esse e-mail não parece válido. Tente de novo.", "That e-mail doesn't look valid. Try again."),
    "flow.invalid_phone": _t("Esse telefone não parece válido. Tente de novo.", "That phone number doesn't look valid. Try again."),

    # -- tipos de mensagem (amostras do antispam) --
    "kind.photo": _t("[foto]", "[photo]"),
    "kind.video": _t("[vídeo]", "[video]"),
    "kind.animation": _t("[GIF]", "[GIF]"),
    "kind.document": _t("[documento]", "[document]"),
    "kind.audio": _t("[áudio]", "[audio]"),
    "kind.voice": _t("[mensagem de voz]", "[voice message]"),
    "kind.video_note": _t("[vídeo redondo]", "[round video]"),
    "kind.contact": _t("[contato]", "[contact]"),
    "kind.location": _t("[localização]", "[location]"),
    "kind.poll": _t("[enquete]", "[poll]"),
    "kind.sticker": _t("[figurinha %s]", "[sticker %s]"),
    "kind.message": _t("[mensagem]", "[message]"),

    # -- antispam --
    "antispam.blocked_notice": _t(
        "⛔ Você foi bloqueado automaticamente por enviar mensagens demais em pouco tempo.\n\n"
        "Um administrador vai analisar o caso. Até lá, o bot não vai responder.",
        "⛔ You were blocked automatically for sending too many messages in a short time.\n\n"
        "An administrator will review the case. Until then, the bot won't answer."),
    "antispam.released_notice": _t(
        "✅ Um administrador analisou o caso e desbloqueou você. Pode voltar a usar o bot.",
        "✅ An administrator reviewed the case and unblocked you. You can use the bot again."),
    "antispam.reason_flood": _t("%d mensagens em %d segundos", "%d messages in %d seconds"),
    "antispam.reason_repeat": _t("a mesma mensagem %d vezes em %s", "the same message %d times in %s"),
    "antispam.reason_manual": _t("bloqueado manualmente", "blocked manually"),
    "antispam.one_minute": _t("1 minuto", "1 minute"),
    "antispam.seconds": _t("%d segundos", "%d seconds"),
    "antispam.no_name": _t("Sem nome", "No name"),
    "antispam.private": _t("Conversa privada", "Private chat"),
    "antispam.group": _t("Grupo %s", "Group %s"),
    "antispam.notice_title": _t("🚨 <b>Bloqueio automático (antispam)</b>", "🚨 <b>Automatic block (antispam)</b>"),
    "antispam.samples": _t("<b>Últimas mensagens:</b>", "<b>Latest messages:</b>"),
    "antispam.notice_footer": _t(
        "O bot está ignorando esta pessoa até um admin decidir. Se foi engano, desbloqueie.",
        "The bot is ignoring this person until an admin decides. If it was a mistake, unblock them."),
    "antispam.by_app": _t("pelo app", "from the app"),
    "antispam.by_owner": _t("o dono do bot (pelo app)", "the bot owner (from the app)"),
    "antispam.released_by": _t("✅ <b>Desbloqueado</b> por %s.", "✅ <b>Unblocked</b> by %s."),
    "antispam.kept_by": _t("🚫 <b>Mantido bloqueado</b> por %s. Ainda dá para desbloquear depois.",
                           "🚫 <b>Kept blocked</b> by %s. It can still be unblocked later."),
    "antispam.btn_release": _t("✅ Desbloquear", "✅ Unblock"),
    "antispam.btn_keep": _t("🚫 Manter bloqueado", "🚫 Keep blocked"),
    "antispam.only_admins": _t("Só admins podem decidir bloqueios.", "Only admins can decide blocks."),
    "antispam.already_decided": _t("Esse caso já foi resolvido por outro admin.", "Another admin already settled this case."),
    "antispam.toast_released": _t("Desbloqueado.", "Unblocked."),
    "antispam.toast_kept": _t("Mantido bloqueado.", "Kept blocked."),

    # -- loja --
    "shop.default_title": _t("Loja", "Shop"),
    "shop.intro": _t("Confira nossos produtos:", "Check out our products:"),
    "shop.gw_pix": _t("Pix (QR code aqui no chat)", "Pix (QR code here in the chat)"),
    "shop.gw_mp": _t("Mercado Pago (Pix, cartão, boleto)", "Mercado Pago (Pix, card, boleto)"),
    "shop.gw_btc": _t("Bitcoin (Lightning)", "Bitcoin (Lightning)"),
    "shop.gw_xtr": _t("Telegram Stars", "Telegram Stars"),
    "shop.gw_xtr_n": _t("Telegram Stars (%d estrelas)", "Telegram Stars (%d stars)"),
    "shop.order_not_found": _t("Pedido não encontrado.", "Order not found."),
    "shop.order_cancelled": _t("Pedido cancelado.", "Order cancelled."),
    "shop.order_paid": _t("Este pedido já está pago.", "This order is already paid."),
    "shop.order_expired": _t("Este pedido expirou. Faça um novo pela loja.", "This order expired. Place a new one in the shop."),
    "shop.order_unavailable": _t("Este pedido não está mais disponível. Faça um novo pela loja.",
                                 "This order is no longer available. Place a new one in the shop."),
    "shop.private_only": _t("A loja abre na conversa privada com o bot.", "The shop opens in the private chat with the bot."),
    "shop.open": _t("Abrir loja", "Open shop"),
    "shop.empty": _t("A loja está sem produtos no momento. Volte em breve!", "The shop has no products right now. Come back soon!"),
    "shop.storefront_hint": _t("Prefere a vitrine completa, com carrinho? Toque em Abrir loja, aqui embaixo.",
                               "Prefer the full storefront, with a cart? Tap Open shop, below."),
    "shop.product_gone": _t("Este produto não está mais à venda.", "This product is no longer for sale."),
    "shop.no_subscriptions": _t("Você ainda não tem assinaturas. Veja as opções em /%s.",
                                "You don't have any subscriptions yet. See the options in /%s."),
    "shop.your_subscriptions": _t("<b>Suas assinaturas</b>", "<b>Your subscriptions</b>"),
    "shop.channel": _t("Canal", "Channel"),
    "shop.active_until": _t("ativa até", "active until"),
    "shop.expired_on": _t("venceu em", "expired on"),
    "shop.renew_named": _t("Renovar %s", "Renew %s"),
    "shop.renew_price": _t("Renovar · %s", "Renew · %s"),
    "shop.cart_empty": _t("O carrinho chegou vazio. Tente de novo pela loja.", "The cart arrived empty. Try again from the shop."),
    "shop.products_gone": _t("Esses produtos não estão mais disponíveis.", "These products are no longer available."),
    "shop.order_title": _t("<b>Pedido %s</b>", "<b>Order %s</b>"),
    "shop.order_plain": _t("Pedido %s", "Order %s"),
    "shop.total_bold": _t("<b>Total: %s</b>", "<b>Total: %s</b>"),
    "shop.no_payments": _t("Os pagamentos ainda não foram configurados. Um administrador foi avisado.",
                           "Payments are not set up yet. An administrator was notified."),
    "shop.cancel_order": _t("Cancelar pedido", "Cancel order"),
    "shop.how_to_pay": _t("Como você prefere pagar?", "How would you like to pay?"),
    "shop.gateway_unavailable": _t("Essa forma de pagamento não está disponível agora.",
                                   "This payment method is not available right now."),
    "shop.already_paid": _t("Já paguei", "I've paid"),
    "shop.pay_mp": _t("Pague pelo Mercado Pago: Pix, cartão ou boleto. Assim que o pagamento for aprovado, a entrega chega aqui.",
                      "Pay through Mercado Pago: Pix, card or boleto. As soon as the payment is approved, the delivery arrives here."),
    "shop.pay_amount": _t("Pagar %s", "Pay %s"),
    "shop.pix_code": _t("Pix copia e cola (toque para copiar):\n\n<code>%s</code>\n\n"
                        "O código vale 30 minutos. A confirmação chega aqui sozinha.",
                        "Pix copy-and-paste code (tap to copy):\n\n<code>%s</code>\n\n"
                        "The code is valid for 30 minutes. The confirmation arrives here by itself."),
    "shop.invoice_memo": _t("%s - pedido %s", "%s - order %s"),
    "shop.pay_btc": _t("Pague <b>%s sats</b> pela Lightning com a fatura abaixo (toque para copiar). Ela vale 1 hora.\n\n<code>%s</code>",
                       "Pay <b>%s sats</b> over Lightning with the invoice below (tap to copy). It's valid for 1 hour.\n\n<code>%s</code>"),
    "shop.thousands_sep": _t(".", ","),
    "shop.charge_failed": _t("Não foi possível gerar a cobrança agora. Tente outra forma de pagamento ou daqui a pouco.",
                             "Couldn't create the charge right now. Try another payment method or a little later."),
    "shop.pix_caption": _t("Pix · %s · pedido %s", "Pix · %s · order %s"),
    "shop.payment_confirmed": _t("Pagamento confirmado!", "Payment confirmed!"),
    "shop.payment_not_yet": _t("Ainda não identificamos o pagamento. Pode levar alguns segundos; a confirmação chega aqui sozinha.",
                               "We haven't detected the payment yet. It may take a few seconds; the confirmation arrives here by itself."),
    "shop.thanks": _t("Pagamento confirmado! Obrigado pela compra.", "Payment confirmed! Thank you for your purchase."),
    "shop.problem_delivery": _t("entrega de %s falhou", "delivery of %s failed"),
    "shop.delivery_problem": _t("Tivemos um problema na entrega. Um administrador já foi avisado e vai resolver. Seu pedido é o %s.",
                                "We had a problem with the delivery. An administrator was notified and will sort it out. Your order is %s."),
    "shop.problem_no_chat": _t("%s sem canal/grupo configurado", "%s has no channel/group set up"),
    "shop.sub_renewed": _t("Assinatura de <b>%s</b> renovada até <b>%s</b>.", "Subscription to <b>%s</b> renewed until <b>%s</b>."),
    "shop.invite_name": _t("Assinatura %s", "Subscription %s"),
    "shop.problem_invite": _t("sem permissão para criar convite em %s (o bot precisa ser admin com 'convidar usuários')",
                              "no permission to create an invite in %s (the bot must be an admin allowed to 'invite users')"),
    "shop.sub_active": _t("Assinatura de <b>%s</b> ativa até <b>%s</b>.\n\nEntre pelo botão abaixo. O convite é pessoal e vale 24 horas.",
                          "Subscription to <b>%s</b> active until <b>%s</b>.\n\nJoin with the button below. The invite is personal and valid for 24 hours."),
    "shop.join": _t("Entrar em %s", "Join %s"),
    "shop.admin_new_sale": _t("Nova venda: pedido %s", "New sale: order %s"),
    "shop.admin_buyer": _t("Comprador: %s · ID %s", "Buyer: %s · ID %s"),
    "shop.admin_total_stars": _t("Total: %d estrelas (Telegram Stars)", "Total: %d stars (Telegram Stars)"),
    "shop.admin_total": _t("Total: %s (%s)", "Total: %s (%s)"),
    "shop.admin_problem": _t("ATENÇÃO, entrega com problema:", "WARNING, delivery problem:"),
    "shop.sub_ended": _t("Sua assinatura de <b>%s</b> terminou e o acesso foi encerrado.",
                         "Your subscription to <b>%s</b> ended and access was removed."),
    "shop.sub_reminder": _t("Sua assinatura de <b>%s</b> vence em <b>%s</b>. Renove para não perder o acesso.",
                            "Your subscription to <b>%s</b> expires on <b>%s</b>. Renew so you don't lose access."),
    "shop.sub_removed": _t("Seu acesso a <b>%s</b> foi encerrado por um administrador.",
                           "Your access to <b>%s</b> was removed by an administrator."),

    # -- detalhes técnicos de erros (aparecem no app, no idioma do app) --
    "detail.mp_no_link": _t("o Mercado Pago não devolveu o link de pagamento", "Mercado Pago didn't return the payment link"),
    "detail.mp_no_pix": _t("o Mercado Pago não devolveu o código Pix", "Mercado Pago didn't return the Pix code"),
    "detail.blink_no_wallet": _t("a conta Blink não tem carteira em BTC", "the Blink account has no BTC wallet"),
    "detail.blink_convert": _t("não foi possível converter %s para satoshis", "couldn't convert %s to satoshis"),
    "detail.blink_no_invoice": _t("o Blink não devolveu a fatura", "Blink didn't return the invoice"),
    "detail.github_no_url": _t("o GitHub não devolveu o endereço do arquivo", "GitHub didn't return the file address"),
    "detail.storage_readback": _t("gravou, mas não conseguiu ler de volta", "it wrote, but couldn't read it back"),
    "detail.storage_no_backup": _t("nenhum backup encontrado neste banco para este bot", "no backup found in this database for this bot"),
    "detail.storage_no_folder": _t("pasta não informada", "no folder given"),
    "detail.rtdb_https": _t("a URL do Realtime Database deve começar com https://", "the Realtime Database URL must start with https://"),
    "detail.rtdb_not_found": _t("banco não encontrado nesta URL", "database not found at this URL"),
    "detail.firestore_forbidden": _t("sem permissão no Firestore (papel da conta de serviço ou API desativada)",
                                     "no permission on Firestore (service account role or API disabled)"),
    "detail.aws_missing": _t("falta a %s da AWS", "the AWS %s is missing"),
    "detail.aws_access": _t("chave de acesso", "access key"),
    "detail.aws_secret": _t("chave secreta", "secret key"),
    "detail.aws_region": _t("região", "region"),
    "detail.aws_table": _t("tabela", "table"),
    "detail.aws_no_table": _t("tabela '%s' não existe na região %s", "table '%s' doesn't exist in region %s"),
    "detail.sa_bad_key": _t("chave privada inválida (%s)", "invalid private key (%s)"),
    "detail.sa_not_json": _t("o arquivo da conta de serviço não é um JSON válido", "the service account file is not valid JSON"),
    "detail.sa_wrong_type": _t("isso não é o JSON de uma conta de serviço (type=service_account)",
                               "this is not a service account JSON (type=service_account)"),
    "detail.sa_missing": _t("o JSON da conta de serviço não tem '%s'", "the service account JSON has no '%s'"),
    "detail.sa_refused": _t("o Google recusou a conta de serviço: %s", "Google refused the service account: %s"),
    "detail.https_required": _t("endereço precisa começar com https://", "the address must start with https://"),
    "detail.bad_response": _t("resposta inválida (HTTP %s)", "invalid response (HTTP %s)"),
    "detail.exit_code": _t("código %d. %s", "exit code %d. %s"),

    # -- agendamentos e lembretes --
    "fmt.date_short": _t("%d/%m", "%m/%d"),
    "reminder.when": _t("%s às %s", "%s at %s"),
    "reminder.fire": _t("⏰ Lembrete: %s", "⏰ Reminder: %s"),
    "reminder.fire_empty": _t("⏰ Lembrete!", "⏰ Reminder!"),
    "label.schedule": _t("agendamento", "schedule"),
    "label.drip": _t("sequência", "sequence"),
    "label.reminder": _t("lembrete", "reminder"),

    # -- internet --
    "label.feed": _t("notícias", "news"),
    "http.sample_args": _t("teste", "test"),

    # -- moderação de grupos --
    "mod.no_rules": _t("Ainda não há regras definidas.", "No rules have been set yet."),
    "mod.need_reply": _t("Responda à mensagem da pessoa com o comando.", "Reply to the person's message with the command."),
    "mod.target_admin": _t("Não dá para fazer isso com um administrador do grupo.", "That can't be done to a group administrator."),
    "mod.banned": _t("🚫 %s foi banido(a) do grupo.", "🚫 %s was banned from the group."),
    "mod.unbanned": _t("✅ %s pode voltar ao grupo.", "✅ %s can come back to the group."),
    "mod.kicked": _t("👢 %s foi removido(a) do grupo (pode voltar por um convite).", "👢 %s was removed from the group (can come back with an invite)."),
    "mod.muted": _t("🔇 %s está silenciado(a) por %s.", "🔇 %s is muted for %s."),
    "mod.unmuted": _t("🔊 %s pode falar de novo.", "🔊 %s can talk again."),
    "mod.unwarned": _t("🧹 As advertências de %s foram zeradas.", "🧹 %s's warnings were cleared."),
    "mod.warn_count": _t("%s tem %d de %d advertência(s).", "%s has %d of %d warning(s)."),
    "mod.warned": _t("⚠️ %s, advertência %d de %d: %s", "⚠️ %s, warning %d of %d: %s"),
    "mod.limit_ban": _t("🚫 %s foi banido(a) depois de %d advertências.", "🚫 %s was banned after %d warnings."),
    "mod.limit_kick": _t("👢 %s foi removido(a) depois de %d advertências.", "👢 %s was removed after %d warnings."),
    "mod.limit_mute": _t("🔇 %s foi silenciado(a) depois de %d advertências, por %d h.", "🔇 %s was muted after %d warnings, for %d h."),
    "mod.reason_link": _t("links não são permitidos aqui", "links aren't allowed here"),
    "mod.reason_word": _t("palavra proibida", "forbidden word"),
    "mod.reason_forward": _t("mensagens encaminhadas não são permitidas", "forwarded messages aren't allowed"),
    "mod.reason_newcomer": _t("quem acabou de entrar ainda não pode mandar links ou mídia", "newcomers can't send links or media yet"),
    "mod.reason_slowmode": _t("modo lento", "slow mode"),
    "mod.reason_captcha": _t("verificação pendente", "verification pending"),
    "mod.reason_manual": _t("advertência de um administrador", "warning from an administrator"),
    "mod.reason_limit": _t("%d advertências", "%d warnings"),
    "mod.captcha_button": _t("👋 Bem-vindo(a), %s! Toque no botão abaixo em até %d minuto(s) para mostrar que não é um robô e liberar o chat.",
                             "👋 Welcome, %s! Tap the button below within %d minute(s) to show you're not a robot and unlock the chat."),
    "mod.captcha_math": _t("👋 Bem-vindo(a), %s! Para liberar o chat, toque no resultado de %d + %d em até %d minuto(s).",
                           "👋 Welcome, %s! To unlock the chat, tap the result of %d + %d within %d minute(s)."),
    "mod.captcha_ok": _t("✅ Não sou um robô", "✅ I'm not a robot"),
    "mod.captcha_not_you": _t("Esse botão é para quem acabou de entrar.", "This button is for the person who just joined."),
    "mod.captcha_wrong": _t("Resposta errada. Tente de novo.", "Wrong answer. Try again."),
    "mod.captcha_done": _t("Pronto! Bem-vindo(a) ao grupo.", "Done! Welcome to the group."),
    "mod.dur_minutes": _t("%d min", "%d min"),
    "mod.dur_hours": _t("%d h", "%d h"),
    "mod.dur_days": _t("%d dia(s)", "%d day(s)"),

    # -- atendimento, agenda e formulários --
    "label.support": _t("atendimento", "support"),
    "label.booking": _t("agenda", "booking"),
    "label.form": _t("formulário", "form"),
    "desk.header": _t("💬 <b>%s</b> · ID <code>%s</code>\n<i>Responda a esta mensagem para responder à pessoa.</i>",
                      "💬 <b>%s</b> · ID <code>%s</code>\n<i>Reply to this message to answer the person.</i>"),
    "desk.reply_failed": _t("Não deu para entregar a resposta (a pessoa pode ter bloqueado o bot).",
                            "Couldn't deliver the reply (the person may have blocked the bot)."),
    "desk.faq_menu": _t("❓ <b>Perguntas frequentes</b>\nToque numa pergunta para ver a resposta:",
                        "❓ <b>Frequently asked questions</b>\nTap a question to see the answer:"),
    "wd.0": _t("Seg", "Mon"), "wd.1": _t("Ter", "Tue"), "wd.2": _t("Qua", "Wed"), "wd.3": _t("Qui", "Thu"),
    "wd.4": _t("Sex", "Fri"), "wd.5": _t("Sáb", "Sat"), "wd.6": _t("Dom", "Sun"),
    "bk.when": _t("%s %s às %s", "%s %s at %s"),
    "bk.pick_day": _t("📅 <b>%s</b>\nEscolha o dia:", "📅 <b>%s</b>\nChoose the day:"),
    "bk.pick_day_plain": _t("📅 Escolha o dia:", "📅 Choose the day:"),
    "bk.no_days": _t("Não há horários livres nos próximos dias. Tente mais tarde.", "There are no free slots in the coming days. Try again later."),
    "bk.day_full": _t("Esse dia já está lotado. Escolha outro.", "That day is already full. Choose another one."),
    "bk.pick_time": _t("🕒 Horários livres em <b>%s</b>:", "🕒 Free times on <b>%s</b>:"),
    "bk.back": _t("« Voltar", "« Back"),
    "bk.confirm_q": _t("Confirmar o horário <b>%s</b>?", "Confirm <b>%s</b>?"),
    "bk.confirm_btn": _t("✅ Confirmar", "✅ Confirm"),
    "bk.confirmed": _t("✅ Agendado: <b>%s</b>.", "✅ Booked: <b>%s</b>."),
    "bk.how_cancel": _t("Para ver ou cancelar: %s", "To see or cancel: %s"),
    "bk.taken": _t("Esse horário acabou de ser ocupado. Escolha outro.", "That time was just taken. Choose another one."),
    "bk.too_many": _t("Você já tem %d horários marcados. Cancele um para marcar outro.", "You already have %d bookings. Cancel one to book another."),
    "bk.private_only": _t("A agenda funciona na conversa privada com o bot.", "Booking works in the private chat with the bot."),
    "bk.none_yet": _t("Você não tem horários marcados. Para marcar: %s", "You have no bookings. To book: %s"),
    "bk.mine_title": _t("📅 <b>Seus horários</b>", "📅 <b>Your bookings</b>"),
    "bk.cancel_btn": _t("Cancelar %s", "Cancel %s"),
    "bk.cancelled": _t("Horário <b>%s</b> cancelado.", "Booking <b>%s</b> cancelled."),
    "bk.cancelled_by_admin": _t("Seu horário <b>%s</b> foi cancelado. Para marcar outro, fale com a gente.", "Your booking <b>%s</b> was cancelled. Get in touch to book another one."),
    "bk.not_found": _t("Esse horário não existe mais.", "That booking no longer exists."),
    "bk.admin_new": _t("📅 Novo agendamento: <b>%s</b>, %s (%s)", "📅 New booking: <b>%s</b>, %s (%s)"),
    "bk.admin_cancel": _t("📅 Cancelado: <b>%s</b>, %s", "📅 Cancelled: <b>%s</b>, %s"),
    "bk.reminder": _t("⏰ Lembrete: seu horário é <b>%s</b>. %s", "⏰ Reminder: your booking is <b>%s</b>. %s"),
    "form.invalid_text": _t("Responda com um texto, por favor.", "Please answer with text."),
    "form.invalid_number": _t("Responda só com números, por favor.", "Please answer with numbers only."),
    "form.invalid_email": _t("Esse e-mail não parece válido.", "That e-mail doesn't look valid."),
    "form.invalid_phone": _t("Esse telefone não parece válido.", "That phone number doesn't look valid."),
    "form.invalid_choice": _t("Escolha uma das opções do teclado.", "Choose one of the keyboard options."),
    "form.done": _t("✅ Recebido, obrigado!", "✅ Received, thank you!"),
    "form.admin_title": _t("📝 <b>%s</b> — respostas de %s", "📝 <b>%s</b> — answers from %s"),
    "form.col_date": _t("Data", "Date"),
    "form.col_name": _t("Nome", "Name"),

    # -- loja: cupons, doações, frete, carrinho abandonado e novos pagamentos --
    "shop.gw_manual": _t("Pix / pagamento manual", "Manual payment"),
    "shop.gw_stripe": _t("Cartão (Stripe)", "Card (Stripe)"),
    "shop.gw_paypal": _t("PayPal", "PayPal"),
    "shop.gw_coupon": _t("Cupom", "Coupon"),
    "shop.sub_access": _t(" · acesso por %d dias a %s", " · %d days of access to %s"),
    "shop.physical_note": _t("📦 Enviado para o seu endereço.", "📦 Shipped to your address."),
    "shop.in_stock": _t("%d em estoque", "%d in stock"),
    "shop.btn_buy": _t("Comprar", "Buy"),
    "shop.btn_subscribe": _t("Assinar", "Subscribe"),
    "shop.btn_donate": _t("❤️ Doar", "❤️ Donate"),
    "shop.donation_min": _t("Valor mínimo: %s", "Minimum: %s"),
    "shop.donation_ask": _t("❤️ <b>%s</b>\nQuanto você quer doar?", "❤️ <b>%s</b>\nHow much would you like to give?"),
    "shop.donation_other_btn": _t("Outro valor", "Other amount"),
    "shop.donation_other": _t("Escreva o valor (mínimo %s):", "Type the amount (minimum %s):"),
    "shop.donation_invalid": _t("Não entendi o valor. Escreva só o número, a partir de %s.", "I didn't get the amount. Type just the number, from %s."),
    "shop.coupon_btn": _t("🏷️ Tenho um cupom", "🏷️ I have a coupon"),
    "shop.coupon_ask": _t("Escreva o código do cupom:", "Type the coupon code:"),
    "shop.coupon_ok": _t("🏷️ Cupom <b>%s</b> aplicado: %s de desconto.", "🏷️ Coupon <b>%s</b> applied: %s off."),
    "shop.coupon_invalid": _t("Esse cupom não existe. Confira o código.", "That coupon doesn't exist. Check the code."),
    "shop.coupon_expired": _t("Esse cupom já venceu.", "That coupon has expired."),
    "shop.coupon_used_up": _t("Esse cupom já foi usado o máximo de vezes.", "That coupon has reached its usage limit."),
    "shop.discount_line": _t("Desconto (%s): -%s", "Discount (%s): -%s"),
    "shop.shipping_sum": _t("Frete: %s", "Shipping: %s"),
    "shop.shipping_line": _t("Frete", "Shipping"),
    "shop.address_line": _t("Entrega: %s", "Delivery: %s"),
    "shop.address_ask": _t("📦 Para onde enviamos? Escreva o endereço completo, com CEP, numa mensagem só.",
                           "📦 Where should we ship it? Type the full address, with ZIP code, in a single message."),
    "shop.address_short": _t("Esse endereço parece incompleto. Escreva rua, número, bairro, cidade e CEP.",
                             "That address looks incomplete. Include street, number, city and ZIP code."),
    "shop.shipping_fee": _t("Frete: %s", "Shipping: %s"),
    "shop.shipping_free": _t("Frete grátis 🎉", "Free shipping 🎉"),
    "shop.physical_paid": _t("📦 Vamos preparar o envio e te avisamos aqui com o código de rastreio.",
                             "📦 We'll prepare the shipment and let you know here with the tracking code."),
    "shop.shipped": _t("📦 Seu pedido %s foi enviado!", "📦 Your order %s has shipped!"),
    "shop.tracking": _t("Código de rastreio: <code>%s</code>", "Tracking code: <code>%s</code>"),
    "shop.pay_link": _t("Pague pelo link abaixo. Assim que o pagamento for aprovado, a entrega chega aqui.",
                        "Pay with the link below. As soon as the payment is approved, the delivery arrives here."),
    "shop.pay_paypal": _t("Pague pelo PayPal no link abaixo. Depois de aprovar, a confirmação chega aqui sozinha.",
                          "Pay with PayPal using the link below. After you approve it, the confirmation arrives here by itself."),
    "shop.pix_manual": _t("Pix copia e cola (toque para copiar):\n<code>%s</code>", "Pix copy-and-paste code (tap to copy):\n<code>%s</code>"),
    "shop.manual_then": _t("Depois de pagar, toque em <b>Já paguei</b>: o vendedor confere e a entrega chega aqui.",
                           "After paying, tap <b>I've paid</b>: the seller checks it and the delivery arrives here."),
    "shop.manual_waiting": _t("Avisamos o vendedor. Assim que ele confirmar o pagamento, a entrega chega aqui.",
                              "We let the seller know. As soon as they confirm the payment, the delivery arrives here."),
    "shop.manual_admin": _t("💸 %s diz que pagou o pedido %s (%s). Confira e confirme:", "💸 %s says they paid order %s (%s). Check and confirm:"),
    "shop.manual_ok": _t("✅ Recebi", "✅ Received"),
    "shop.manual_no": _t("❌ Não recebi", "❌ Not received"),
    "shop.manual_confirmed": _t("✅ Pagamento do pedido %s confirmado e entregue.", "✅ Payment for order %s confirmed and delivered."),
    "shop.manual_rejected": _t("❌ Pedido %s: avisamos que o pagamento não foi encontrado.", "❌ Order %s: we told them the payment wasn't found."),
    "shop.manual_not_found": _t("O vendedor ainda não encontrou o pagamento do pedido %s. Confira e toque em Já paguei de novo.",
                                "The seller hasn't found the payment for order %s yet. Check it and tap I've paid again."),
    "shop.abandoned": _t("🛒 Você esqueceu algo! O pedido %s ainda está separado para você.", "🛒 You forgot something! Order %s is still reserved for you."),
    "shop.abandoned_btn": _t("Pagar agora", "Pay now"),
    "shop.admin_coupon": _t("Cupom %s: -%s", "Coupon %s: -%s"),
    "shop.admin_address": _t("Entregar em: %s (frete %s)", "Ship to: %s (shipping %s)"),
    "detail.stripe_no_key": _t("falta a chave secreta da Stripe", "the Stripe secret key is missing"),
    "detail.paypal_no_key": _t("falta o Client ID ou o Secret do PayPal", "the PayPal Client ID or Secret is missing"),
    "detail.paypal_currency": _t("o PayPal não aceita a moeda %s", "PayPal doesn't accept the currency %s"),
}
