package com.jeep.claudecode

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose inside one of the app's windows made of views (a sheet, Settings): around it everything stays
 * the views it is, what it holds is drawn by Compose. MainActivity is a plain Activity, with no lifecycle for
 * Compose to follow, so the window carries one of its own, made with its first Compose content: resumed
 * while the window is on the screen, destroyed when it goes.
 *
 * One for the window, not for each content: Compose keeps one recomposer for a window, tied to the lifecycle
 * it first found, so a page filled again (its content made anew) must not end it.
 */
internal fun composeIn(window: Dialog, parent: ViewGroup, content: @Composable () -> Unit): ComposeView {
    return traced("cc compose") {
        val decor = window.window!!.decorView
        if (decor.findViewTreeLifecycleOwner() !is WindowOwner) WindowOwner(decor)
        val view = ComposeView(window.context)
        // Let go when the page it is on is filled again or the window goes.
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent { traced("cc composition") { content() } }
        parent.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return view
    }
}

/** A window's lifecycle: on its root, where Compose looks for it (from the content's root up, not down). */
private class WindowOwner(decor: View) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    init {
        saved.performRestore(null) // a window of the app's is made anew each time: nothing to restore
        registry.currentState = Lifecycle.State.CREATED
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
        if (decor.isAttachedToWindow) resume()
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = resume()
            override fun onViewDetachedFromWindow(v: View) {
                registry.currentState = Lifecycle.State.DESTROYED
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }
}
