package com.deivid22srk.portstore.ui.virtual

import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.virtual.VirtualFailure

/**
 * Recurso de string localizada para cada motivo de falha de instalação
 * virtual. Retorna o ID (não é @Composable) para poder ser usado em
 * LaunchedEffect/Toasts via context.getString.
 */
fun virtualFailureRes(reason: VirtualFailure): Int = when (reason) {
    VirtualFailure.ENGINE_UNAVAILABLE -> R.string.virtual_err_engine
    VirtualFailure.FILE_MISSING -> R.string.virtual_err_file
    VirtualFailure.HOST_PACKAGE -> R.string.virtual_err_host
    VirtualFailure.ARCH_INCOMPATIBLE -> R.string.virtual_err_arch
    VirtualFailure.SHA_MISMATCH -> R.string.virtual_err_sha
    VirtualFailure.ENGINE_ERROR -> R.string.virtual_err_engine_error
}
