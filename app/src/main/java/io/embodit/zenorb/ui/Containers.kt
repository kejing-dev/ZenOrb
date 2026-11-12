package io.embodit.zenorb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The orb volume is the default container (manifest). */
object Containers {
    const val VOLUME = "ZenOrbVolume"
    const val CONTROL = "ZenOrbControl"
    const val CONTROL_TAG = "control"
}

/** Control window sub-pages (design panels.control): settings is pushed; confirm is shared by quit / clear / give up. */
enum class Page { HOME, SETTINGS }

enum class Confirm { QUIT, CLEAR, GIVE_UP }

/** Window-page state shared process-wide so debug commands can switch pages. */
object Ui {
    var page by mutableStateOf(Page.HOME)
    var confirm by mutableStateOf<Confirm?>(null)
}
