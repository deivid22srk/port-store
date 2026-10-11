package com.deivid22srk.portstore.ui.virtual

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.deivid22srk.portstore.R
import com.deivid22srk.portstore.virtual.VirtualFailure

/** Mensagem localizada para cada motivo de falha de instalação virtual. */
@Composable
fun virtualFailureMessage(reason: VirtualFailure): String = when (reason) {
    VirtualFailure.ENGINE_UNAVAILABLE -> stringResource(R.string.virtual_err_engine)
    VirtualFailure.FILE_MISSING -> stringResource(R.string.virtual_err_file)
    VirtualFailure.HOST_PACKAGE -> stringResource(R.string.virtual_err_host)
    VirtualFailure.ARCH_INCOMPATIBLE -> stringResource(R.string.virtual_err_arch)
    VirtualFailure.SHA_MISMATCH -> stringResource(R.string.virtual_err_sha)
    VirtualFailure.ENGINE_ERROR -> stringResource(R.string.virtual_err_engine_error)
}
