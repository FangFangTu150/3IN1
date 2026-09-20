package io.github.threeinone

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.github.threeinone.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutTest {
    @Test fun aboutIsLastControlAndOpensExactAuthorUrl() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            fun descendants(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                else emptyList()
            val buttons = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            val about = buttons.last()
            assertEquals("关于", about.text.toString())
            about.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            assertEquals("版本 $version", shadowOf(dialog).message.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://www.coolapk.com/u/17348848", intent.data.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
