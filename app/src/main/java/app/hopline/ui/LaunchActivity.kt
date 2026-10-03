package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.hopline.service.Core
import app.hopline.service.Permissions

/**
 * Decides which screen you need: name → permissions → group → home ([ScreenRules.launch] holds
 * the table). Never shows anything itself.
 *
 * A phone whose groups were all left goes straight to Home, permissions or not: its old chats are
 * there to be read, and reading needs no radio. The permissions are asked for again the moment
 * there is a group to run — on rejoining one, or joining another.
 */
class LaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Core.store
        val next = when (ScreenRules.launch(
            named = store.name.isNotBlank(), inGroup = store.group() != null, anySaved = store.allGroups().isNotEmpty(),
            radioReady = Permissions.allGranted(this) && store.permissionsDone)) {
            ScreenRules.Screen.WELCOME -> WelcomeActivity::class.java
            ScreenRules.Screen.PERMISSIONS -> PermissionsActivity::class.java
            ScreenRules.Screen.JOIN -> GroupActivity::class.java
            ScreenRules.Screen.HOME -> HomeActivity::class.java
        }
        // Screens send people here when their group is gone, often with Home still underneath: the
        // next screen starts a clean task, so there is never a second Home stacked on the first.
        startActivity(Intent(this, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
