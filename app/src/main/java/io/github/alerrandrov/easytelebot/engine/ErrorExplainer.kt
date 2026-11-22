package io.github.alerrandrov.easytelebot.engine

import android.content.Context
import io.github.alerrandrov.easytelebot.R

/** Ações que a tela de erro pode oferecer junto com a explicação. */
enum class ErrorAction { NONE, NEW_TOKEN, DELETE_WEBHOOK, BATTERY_GUIDE, FILES_PERMISSION }

data class Explanation(
    val title: String,
    val message: String,
    val steps: List<String>,
    val action: ErrorAction = ErrorAction.NONE,
)

/**
 * Princípio do app: nenhum erro técnico solto. Todo código vindo do motor vira
 * título + o que aconteceu + o que fazer.
 */
object ErrorExplainer {
    fun explain(context: Context, error: EngineError): Explanation {
        val r = context.resources
        fun e(title: Int, msg: Int, steps: Int?, action: ErrorAction = ErrorAction.NONE) = Explanation(
            r.getString(title),
            r.getString(msg),
            steps?.let { r.getStringArray(it).toList() } ?: emptyList(),
            action,
        )
        val base = when (error.code) {
            "token_format" -> e(R.string.err_token_format_title, R.string.err_token_format_msg, R.array.botfather_steps, ErrorAction.NEW_TOKEN)
            "invalid_token" -> e(R.string.err_invalid_token_title, R.string.err_invalid_token_msg, R.array.botfather_token_steps, ErrorAction.NEW_TOKEN)
            "network" -> e(R.string.err_network_title, R.string.err_network_msg, null)
            "conflict" -> e(R.string.err_conflict_title, R.string.err_conflict_msg, R.array.err_conflict_steps)
            "webhook_active" -> e(R.string.err_webhook_title, R.string.err_webhook_msg, null, ErrorAction.DELETE_WEBHOOK)
            "chat_not_found" -> e(R.string.err_chat_not_found_title, R.string.err_chat_not_found_msg, null)
            "cant_initiate" -> e(R.string.err_cant_initiate_title, R.string.err_cant_initiate_msg, null)
            "blocked_by_user" -> e(R.string.err_blocked_title, R.string.err_blocked_msg, null)
            "not_enough_rights" -> e(R.string.err_rights_title, R.string.err_rights_msg, R.array.err_rights_steps)
            "too_many_requests" -> e(R.string.err_429_title, R.string.err_429_msg, null)
            "parse_entities" -> e(R.string.err_parse_title, R.string.err_parse_msg, null)
            "killed" -> e(R.string.err_killed_title, R.string.err_killed_msg, null, ErrorAction.BATTERY_GUIDE)
            "media_error" -> e(R.string.err_media_title, R.string.err_media_msg, null)
            "media_missing" -> e(R.string.err_media_missing_title, R.string.err_media_missing_msg, null)
            "too_long" -> e(R.string.err_too_long_title, R.string.err_too_long_msg, null)
            "bad_button_url" -> e(R.string.err_button_url_title, R.string.err_button_url_msg, null)
            "photo_unsupported" -> e(R.string.err_photo_unsupported_title, R.string.err_photo_unsupported_msg, R.array.botfather_photo_steps)
            "wrong_bot" -> e(R.string.err_wrong_bot_title, R.string.err_wrong_bot_msg, null)
            "unreachable" -> e(R.string.err_unreachable_title, R.string.err_unreachable_msg, null)
            "broadcast_empty" -> e(R.string.err_broadcast_empty_title, R.string.err_broadcast_empty_msg, null)
            "broadcast_no_audience" -> e(R.string.err_broadcast_no_audience_title, R.string.err_broadcast_no_audience_msg, null)
            "broadcast_busy" -> e(R.string.err_broadcast_busy_title, R.string.err_broadcast_busy_msg, null)
            "storage_config" -> e(R.string.err_storage_config_title, R.string.err_storage_config_msg, null)
            "storage_auth" -> e(R.string.err_storage_auth_title, R.string.err_storage_auth_msg, R.array.err_storage_auth_steps)
            "storage_not_found" -> e(R.string.err_storage_not_found_title, R.string.err_storage_not_found_msg, R.array.err_storage_not_found_steps)
            "storage_network" -> e(R.string.err_storage_network_title, R.string.err_storage_network_msg, null)
            "storage_permission" -> e(R.string.err_storage_permission_title, R.string.err_storage_permission_msg, null, ErrorAction.FILES_PERMISSION)
            "storage_unknown" -> e(R.string.err_storage_unknown_title, R.string.err_storage_unknown_msg, null)
            "pay_config" -> e(R.string.err_pay_config_title, R.string.err_pay_config_msg, null)
            "pay_auth" -> e(R.string.err_pay_auth_title, R.string.err_pay_auth_msg, R.array.err_pay_auth_steps)
            "pay_network" -> e(R.string.err_pay_network_title, R.string.err_pay_network_msg, null)
            "pay_rejected" -> e(R.string.err_pay_rejected_title, R.string.err_pay_rejected_msg, null)
            "pay_no_gateway" -> e(R.string.err_pay_no_gateway_title, R.string.err_pay_no_gateway_msg, null)
            "pay_already" -> e(R.string.err_pay_already_title, R.string.err_pay_already_msg, null)
            "pay_not_found" -> e(R.string.err_pay_not_found_title, R.string.err_pay_not_found_msg, null)
            "pay_invite" -> e(R.string.err_pay_invite_title, R.string.err_pay_invite_msg, R.array.err_pay_invite_steps)
            "pay_unknown" -> e(R.string.err_pay_unknown_title, R.string.err_pay_unknown_msg, null)
            else -> e(R.string.err_unknown_title, R.string.err_unknown_msg, null)
        }
        val extra = buildList {
            error.retryIn?.let { add(r.getString(R.string.err_retry_in, it)) }
            if ((error.code == "unknown" || error.code.startsWith("storage_") || error.code.startsWith("pay_")) &&
                error.detail.isNotBlank()
            ) {
                add(r.getString(R.string.err_detail, error.detail))
            }
        }
        return if (extra.isEmpty()) base else base.copy(message = base.message + "\n\n" + extra.joinToString("\n"))
    }
}
