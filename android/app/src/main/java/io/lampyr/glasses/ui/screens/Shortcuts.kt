package io.lampyr.glasses.ui.screens

import io.lampyr.glasses.input.*
import io.lampyr.glasses.render.*
import io.lampyr.glasses.ui.*

/** Lists only gestures the controller has; the sub line names the apps that keep a gesture for themselves (15.3). */
class Shortcuts(ui: Ui) : ListView(ui,"shortcuts",items = {
    ui.shortcuts.gestures().map { gesture -> Row(gesture.label, sub=ui.shortcuts.summary(gesture),
        open={ ui.push(ShortcutActions(ui,gesture)) }) }
},chrome={t->frame(ui,"Shortcuts","todo",t)}) {
    init {
        textTitle={"Shortcuts"};textHint={"Y assign B back"}
        textItems={
            val gestures=ui.shortcuts.gestures()
            rows().mapIndexed { i,row->row.copy(right=gestures.getOrNull(i)?.let(ui.shortcuts::assigned)?.label?.substringBefore(':') ?: "None")}
        }
    }
}
class ShortcutActions(ui: Ui,val gesture: ShortcutGesture) : ListView(ui,"shortcut-actions",items = {
    listOf(Row("None",open={ui.shortcuts.assign(gesture,null);ui.pop()})) + ui.shortcuts.actions(gesture).map { action ->
        Row(action.label,open={ui.shortcuts.assign(gesture,action.id);ui.pop()})
    }
},chrome={t->frame(ui,gesture.label,"todo",t)}) {
    init { textTitle={gesture.label};textHint={"Y assign B back"} }
}
