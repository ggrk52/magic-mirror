package com.codex.magicmirrorcontroller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.magicmirrorcontroller.data.MagicMirrorApi
import com.codex.magicmirrorcontroller.data.MagicMirrorDiscovery
import com.codex.magicmirrorcontroller.data.SecureTokenStore
import com.codex.magicmirrorcontroller.data.ServerConfigRepository
import com.codex.magicmirrorcontroller.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val viewModel: MainViewModel = viewModel(
                factory = MainViewModel.factory(
                    api = MagicMirrorApi(),
                    repository = ServerConfigRepository(applicationContext),
                    discovery = MagicMirrorDiscovery(applicationContext),
                    tokenStore = SecureTokenStore(applicationContext),
                ),
            )

            MagicMirrorApp(viewModel = viewModel)
        }
    }
}
