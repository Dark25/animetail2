package eu.kanade.tachiyomi.ui.player.cast

import android.content.Intent
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.media.widget.MiniControllerFragment
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CastMiniController(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var castState by remember { mutableIntStateOf(CastState.NO_DEVICES_AVAILABLE) }
    var isFragmentReady by remember { mutableStateOf(false) }
    var isSessionReady by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val castContext = when (context) {
        is PlayerActivity -> context.castManager.castContext
        is MainActivity -> context.castManager.castContext
        else -> null
    }

    // Función para limpiar el fragmento de forma segura
    fun cleanupFragment(fragmentManager: androidx.fragment.app.FragmentManager) {
        try {
            val fragment = fragmentManager.findFragmentById(R.id.castMiniController)
            if (fragment != null) {
                fragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitNowAllowingStateLoss()
            }
        } catch (e: Exception) {
            Log.e("CastMiniController", "Error cleaning up fragment", e)
        }
    }

    // Observe lifecycle to ensure fragment operations happen at the right time
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isFragmentReady = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (context is FragmentActivity) {
                        cleanupFragment(context.supportFragmentManager)
                    }
                    isFragmentReady = false
                    isSessionReady = false
                }
                Lifecycle.Event.ON_DESTROY -> {
                    if (context is FragmentActivity) {
                        cleanupFragment(context.supportFragmentManager)
                    }
                    isFragmentReady = false
                    isSessionReady = false
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (context is FragmentActivity) {
                cleanupFragment(context.supportFragmentManager)
            }
            isFragmentReady = false
            isSessionReady = false
        }
    }

    LaunchedEffect(castContext) {
        if (castContext == null) {
            Log.e("CastMiniController", "CastContext is null")
            return@LaunchedEffect
        }
        try {
            // Update state with current value and listen for changes
            castState = castContext.castState
            castContext.addCastStateListener { state ->
                castState = state
                if (state == CastState.CONNECTED) {
                    // Dar tiempo a que la sesión se inicialice completamente
                    scope.launch {
                        delay(500)
                        isSessionReady = true
                    }
                } else {
                    isSessionReady = false
                    if (context is FragmentActivity) {
                        cleanupFragment(context.supportFragmentManager)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CastMiniController", "Error initializing cast state", e)
            isSessionReady = false
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            castState == CastState.CONNECTED && isFragmentReady && isSessionReady -> {
                Log.d("CastMiniController", "CastState is CONNECTED and ready")
                AndroidView(
                    factory = { context ->
                        FragmentContainerView(context).apply {
                            id = R.id.castMiniController
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            // Asegurar que el fragmento se mantenga mientras navegamos
                            setOnClickListener {
                                if (context is FragmentActivity) {
                                    try {
                                        val castContext = when (context) {
                                            is PlayerActivity -> context.castManager.castContext
                                            is MainActivity -> context.castManager.castContext
                                            else -> null
                                        }
                                        
                                        // Solo navegar si hay una sesión activa
                                        if (castContext?.sessionManager?.currentCastSession?.isConnected == true) {
                                            val intent = Intent(context, ExpandedControlsActivity::class.java)
                                            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                            context.startActivity(intent)
                                        } else {
                                            Log.e("CastMiniController", "No active cast session")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CastMiniController", "Error navigating to ExpandedControlsActivity", e)
                                    }
                                }
                            }
                        }
                    },
                    update = { view ->
                        try {
                            val fragmentManager = (context as FragmentActivity).supportFragmentManager
                            if (fragmentManager.findFragmentById(view.id) == null) {
                                scope.launch {
                                    delay(100) // Pequeña espera para asegurar que todo esté listo
                                    try {
                                        if (isFragmentReady && isSessionReady) {
                                            val fragment = MiniControllerFragment()
                                            fragmentManager.beginTransaction()
                                                .replace(view.id, fragment)
                                                .commitNowAllowingStateLoss()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CastMiniController", "Error creating fragment", e)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CastMiniController", "Error updating fragment", e)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            castState == CastState.CONNECTING -> {
                Log.d("CastMiniController", "CastState is CONNECTING")
                CircularProgressIndicator()
            }
            else -> {
                Log.d("CastMiniController", "CastState is not ready: state=$castState, fragmentReady=$isFragmentReady, sessionReady=$isSessionReady")
                if (context is FragmentActivity) {
                    cleanupFragment(context.supportFragmentManager)
                }
            }
        }
    }
}
