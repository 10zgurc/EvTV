package com.evtv.app

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf(vm.savedServer) }
    var user by remember { mutableStateOf(vm.savedUser) }
    var pass by remember { mutableStateOf("") }
    val fm = LocalFocusManager.current
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(100)
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("EvTV", color = C.accent, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Xtream Codes bilgilerini gir ya da sağlayıcının M3U linkini sunucu alanına yapıştır", color = C.sub, fontSize = 17.sp)
        Spacer(Modifier.height(28.dp))

        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvField("Sunucu adresi (örn. http://ornek.com:8080)", server, { server = it }, fm,
                KeyboardType.Uri, modifier = Modifier.focusRequester(first))
            TvField("Kullanıcı adı", user, { user = it }, fm)
            TvField("Şifre", pass, { pass = it }, fm, KeyboardType.Password, password = true, last = true)

            vm.error?.let { Text(it, color = C.error, fontSize = 16.sp) }

            FocusRow(onClick = { if (!vm.loading) vm.login(server, user, pass) }) {
                Text(
                    if (vm.loading) "Bağlanıyor…" else "Giriş yap",
                    color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
fun TvField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    fm: FocusManager,
    type: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    last: Boolean = false,
    imeSearch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = type,
            imeAction = when {
                imeSearch -> ImeAction.Search
                last -> ImeAction.Done
                else -> ImeAction.Next
            },
        ),
        keyboardActions = KeyboardActions(
            onNext = { fm.moveFocus(FocusDirection.Down) },
            onDone = { fm.moveFocus(FocusDirection.Down) },
            onSearch = { fm.moveFocus(FocusDirection.Down) },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.focus,
            unfocusedBorderColor = C.sub,
            focusedTextColor = C.text,
            unfocusedTextColor = C.text,
            focusedLabelColor = C.accent,
            unfocusedLabelColor = C.sub,
        ),
        // Kumandanın yukarı/aşağı tuşları alanlar arasında gezsin
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.nativeKeyEvent.keyCode) {
                AndroidKey.KEYCODE_DPAD_DOWN -> fm.moveFocus(FocusDirection.Down)
                AndroidKey.KEYCODE_DPAD_UP -> fm.moveFocus(FocusDirection.Up)
                AndroidKey.KEYCODE_DPAD_LEFT -> if (value.isEmpty()) fm.moveFocus(FocusDirection.Left) else false
                else -> false
            }
        },
    )
}
