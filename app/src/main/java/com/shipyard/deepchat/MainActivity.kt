package com.shipyard.deepchat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shipyard.deepchat.ui.ChatApp
import com.shipyard.deepchat.ui.savePicked
import com.shipyard.deepchat.vm.ChatViewModel

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()
    private var shareToken by mutableIntStateOf(0)
    private var shareText by mutableStateOf("")
    private var shareImages by mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readShare(intent)
        setContent {
            ChatApp(vm)
            LaunchedEffect(shareToken) {
                if (shareToken > 0) {
                    if (vm.state.value.current == null) vm.newChat()
                    vm.send(shareText, shareImages)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
    }

    private fun readShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (type.startsWith("text/")) {
            val t = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            shareText = t
            shareImages = emptyList()
            shareToken++
        } else if (type.startsWith("image/")) {
            @Suppress("DEPRECATION")
            val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
            if (uri != null) {
                val name = savePicked(this, uri)
                if (name != null) {
                    shareText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    shareImages = listOf(name)
                    shareToken++
                }
            }
        }
    }
}
