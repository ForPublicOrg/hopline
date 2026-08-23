package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.hopline.service.Core
import app.hopline.service.Permissions

/** Decides which screen you need: name → permissions → group → chat. Never shows anything itself. */
class LaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Core.store
        val next = when {
            store.name.isBlank() -> WelcomeActivity::class.java
            !Permissions.allGranted(this) -> PermissionsActivity::class.java
            !store.permissionsDone -> PermissionsActivity::class.java
            store.group() == null -> GroupActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, next))
        finish()
    }
}
