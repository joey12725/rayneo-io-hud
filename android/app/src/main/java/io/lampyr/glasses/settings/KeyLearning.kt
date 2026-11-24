package io.lampyr.glasses.settings

import android.view.KeyEvent

/** While the controller screen is teaching buttons, key events are offered here before normal handling. */
object KeyLearning {
    var consume: ((KeyEvent) -> Boolean)? = null
    var raw: ((Int, Int) -> Boolean)? = null
}
