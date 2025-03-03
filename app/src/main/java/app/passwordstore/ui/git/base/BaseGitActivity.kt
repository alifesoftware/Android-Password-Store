/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.git.base

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import app.passwordstore.R
import app.passwordstore.injection.prefs.GitPreferences
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.git.ErrorMessages
import app.passwordstore.util.git.operation.BreakOutOfDetached
import app.passwordstore.util.git.operation.CloneOperation
import app.passwordstore.util.git.operation.GcOperation
import app.passwordstore.util.git.operation.PullOperation
import app.passwordstore.util.git.operation.PushOperation
import app.passwordstore.util.git.operation.ResetToRemoteOperation
import app.passwordstore.util.git.operation.SyncOperation
import app.passwordstore.util.settings.GitSettings
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.mapError
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.withContext
import logcat.asLog
import logcat.logcat
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException

/**
 * Abstract [AppCompatActivity] that holds some information that is commonly shared across
 * git-related tasks and makes sense to be held here.
 */
@AndroidEntryPoint
abstract class BaseGitActivity : AppCompatActivity() {

  /** Enum of possible Git operations than can be run through [launchGitOperation]. */
  enum class GitOp {
    BREAK_OUT_OF_DETACHED,
    CLONE,
    PULL,
    PUSH,
    RESET,
    SYNC,
    GC,
  }

  @Inject lateinit var gitSettings: GitSettings
  @Inject lateinit var dispatcherProvider: DispatcherProvider
  @GitPreferences @Inject lateinit var gitPrefs: SharedPreferences

  /**
   * Poor workaround to pass in a specified remote branch for [ResetToRemoteOperation]. Callers of
   * [launchGitOperation] should set this before calling the method with [GitOp.RESET].
   */
  protected var remoteBranch = ""

  /**
   * Attempt to launch the requested Git operation.
   *
   * @param operation The type of git operation to launch
   */
  suspend fun launchGitOperation(operation: GitOp): Result<Unit, Throwable> {
    if (gitSettings.url == null) {
      return Err(IllegalStateException("Git url is not set!"))
    }
    if (operation == GitOp.SYNC && !gitSettings.useMultiplexing) {
      // If the server does not support multiple SSH channels per connection, we cannot run
      // a sync operation without reconnecting and thus break sync into its two parts.
      return launchGitOperation(GitOp.PULL).andThen { launchGitOperation(GitOp.PUSH) }
    }
    val op =
      when (operation) {
        GitOp.CLONE -> CloneOperation(this, gitSettings.url!!)
        GitOp.PULL -> PullOperation(this, gitSettings.rebaseOnPull)
        GitOp.PUSH -> PushOperation(this)
        GitOp.SYNC -> SyncOperation(this, gitSettings.rebaseOnPull)
        GitOp.BREAK_OUT_OF_DETACHED -> BreakOutOfDetached(this)
        GitOp.RESET -> ResetToRemoteOperation(this, remoteBranch)
        GitOp.GC -> GcOperation(this)
      }
    return (if (op.requiresAuth) {
        op.executeAfterAuthentication(gitSettings.authMode)
      } else {
        op.execute()
      })
      .mapError(::transformGitError)
  }

  fun finishOnSuccessHandler(@Suppress("UNUSED_PARAMETER") nothing: Unit) {
    finish()
  }

  suspend fun promptOnErrorHandler(err: Throwable, onPromptDone: () -> Unit = {}) {
    val error = rootCauseException(err)
    if (!isExplicitlyUserInitiatedError(error)) {
      gitPrefs.edit { remove(PreferenceKeys.HTTPS_PASSWORD) }
      sharedPrefs.edit { remove(PreferenceKeys.SSH_OPENKEYSTORE_KEYID) }
      logcat { error.asLog() }
      withContext(dispatcherProvider.main()) {
        MaterialAlertDialogBuilder(this@BaseGitActivity).run {
          setTitle(resources.getString(R.string.jgit_error_dialog_title))
          setMessage(ErrorMessages[error])
          setPositiveButton(resources.getString(R.string.dialog_ok)) { _, _ -> }
          setOnDismissListener { onPromptDone() }
          show()
        }
      }
    } else {
      onPromptDone()
    }
  }

  /**
   * Takes the result of [launchGitOperation] and applies any necessary transformations on the
   * [throwable] returned from it
   */
  private fun transformGitError(throwable: Throwable): Throwable {
    val err = rootCauseException(throwable)
    return when {
      err.message?.contains("cannot open additional channels") == true -> {
        gitSettings.useMultiplexing = false
        SSHException(
          DisconnectReason.TOO_MANY_CONNECTIONS,
          "The server does not support multiple Git operations per SSH session. Please try again, a slower fallback mode will be used.",
        )
      }
      err.message?.contains("int org.eclipse.jgit.lib.AnyObjectId.w1") == true -> {
        IllegalStateException(
          "Your local repository appears to be an incomplete Git clone, please delete and re-clone from settings"
        )
      }
      err is TransportException &&
        err.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE -> {
        SSHException(
          DisconnectReason.HOST_KEY_NOT_VERIFIABLE,
          "WARNING: The remote host key has changed. If this is expected, please go to Git server settings and clear the saved host key.",
        )
      }
      else -> {
        err
      }
    }
  }

  /**
   * Check if a given [Throwable] is the result of an error caused by the user cancelling the
   * operation.
   */
  private fun isExplicitlyUserInitiatedError(throwable: Throwable): Boolean {
    var cause: Throwable? = throwable
    while (cause != null) {
      if (
        cause is SSHException && cause.disconnectReason == DisconnectReason.AUTH_CANCELLED_BY_USER
      )
        return true
      cause = cause.cause
    }
    return false
  }

  /**
   * Get the real root cause of a [Throwable] by traversing until known wrapping exceptions are no
   * longer found.
   */
  private fun rootCauseException(throwable: Throwable): Throwable {
    var rootCause = throwable
    // JGit's InvalidRemoteException and TransportException hide the more helpful SSHJ
    // exceptions.
    // Also, SSHJ's UserAuthException about exhausting available authentication methods hides
    // more useful exceptions.
    while (
      (rootCause is org.eclipse.jgit.errors.TransportException ||
        rootCause is org.eclipse.jgit.api.errors.TransportException ||
        rootCause is org.eclipse.jgit.api.errors.InvalidRemoteException ||
        (rootCause is UserAuthException &&
          rootCause.message == "Exhausted available authentication methods"))
    ) {
      rootCause = rootCause.cause ?: break
    }
    return rootCause
  }
}
