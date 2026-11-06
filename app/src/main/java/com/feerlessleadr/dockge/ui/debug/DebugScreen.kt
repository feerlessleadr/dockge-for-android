package com.feerlessleadr.dockge.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.feerlessleadr.dockge.api.FailureKind
import com.feerlessleadr.dockge.data.ConnectionState

@Composable
fun DebugScreen(modifier: Modifier = Modifier, vm: DebugViewModel = viewModel()) {
    val connection by vm.connection.collectAsState()
    val stacks by vm.stacks.collectAsState()
    val error by vm.error.collectAsState()

    // Plain remember on purpose: secrets must never be written to saved instance state.
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("M1 debug: connect and sign in (read only)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = address, onValueChange = { address = it }, label = { Text("Instance address (https)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Button(onClick = { vm.connect(address) }, enabled = address.isNotBlank()) { Text("Connect") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("State: ${describe(connection)}")

        when (val c = connection) {
            is ConnectionState.NeedsLogin -> {
                c.lastFailure?.let { Text(reason(it), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = username, onValueChange = { username = it }, label = { Text("Username") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(
                    onClick = { vm.submitCredentials(username.trim(), password); password = "" },
                    enabled = username.isNotBlank() && password.isNotEmpty(),
                ) { Text("Sign in") }
            }
            is ConnectionState.NeedsTwoFactor -> {
                c.lastFailure?.let { Text(reason(it), color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = code, onValueChange = { code = it.take(20) }, label = { Text("6-digit code") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(onClick = { vm.submitCode(code.trim()); code = "" }, enabled = code.isNotBlank()) { Text("Verify") }
            }
            ConnectionState.Connected -> {
                Text("Signed in. Stacks (${stacks?.size ?: "waiting"}):")
                stacks?.forEach { Text("• $it") }
                OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out (forget token)") }
            }
            else -> Unit
        }
    }
}

private fun describe(s: ConnectionState): String = when (s) {
    ConnectionState.Disconnected -> "disconnected"
    ConnectionState.Connecting -> "connecting"
    is ConnectionState.NeedsLogin -> "needs sign-in"
    is ConnectionState.NeedsTwoFactor -> "needs 2FA code"
    ConnectionState.LoggingIn -> "signing in"
    ConnectionState.Connected -> "connected"
    is ConnectionState.Failed -> "failed: ${s.message}"
}

private fun reason(k: FailureKind): String = when (k) {
    FailureKind.InvalidCredentials -> "Wrong user name or password."
    FailureKind.InvalidCode -> "That code was not accepted. Wait for the next code and try again."
    FailureKind.ChallengeExpired -> "The code step timed out. Enter your password again."
    FailureKind.RateLimited -> "The server is rate limiting logins. Wait a minute."
    FailureKind.Revoked -> "Your saved sign-in is no longer valid. Sign in again."
    FailureKind.Turnstile -> "This server requires a Turnstile check, which the app cannot do."
    FailureKind.LocalLoginDisabled -> "Password login is turned off on this server."
    FailureKind.Network -> "No answer from the server."
    FailureKind.Other -> "Sign-in failed."
}
