package uz.ekranoquvchi.mvp.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ikki marta bosishda ishlatiladigan yordamchi amallar (ReaderService.activateByDoubleTap): navigatsiya paneli tugmalari va
 * element markaziga barmoq bilan tegish. ReaderService kattalashib ketmasligi uchun alohida faylga ajratilgan.
 */
object TouchActions {
    private const val SYSTEM_UI = "com.android.systemui"

    /**
     * Navigatsiya paneli (SystemUI) tugmasi bo'lsa (Orqaga, Bosh ekran, So'nggilar, Maxsus imkoniyatlar), unga mos tizim amali;
     * aks holda null. Bunday tugmani bosish ba'zan o'tmasligi mumkin, tizim amalining o'zi esa har doim ishlaydi.
     */
    fun navigationBarAction(node: AccessibilityNodeInfo): Int? {
        if (node.packageName?.toString() != SYSTEM_UI) return null
        val id = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
        return when (id) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recent_apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "accessibility_button" -> AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON
            else -> null
        }
    }

    /** Bosiladigan tugun topilmaganda: element markaziga barmoq bilan tegadi (tizimning o'zi ham shunday qilardi). */
    fun tapCenter(service: AccessibilityService, node: AccessibilityNodeInfo, longPress: Boolean): Boolean {
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (_: Throwable) {
            return false
        }
        if (r.isEmpty) return false
        return try {
            val path = Path()
            path.moveTo(r.exactCenterX(), r.exactCenterY())
            val stroke = GestureDescription.StrokeDescription(path, 0L, if (longPress) 600L else 50L)
            service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (_: Throwable) {
            false
        }
    }
}
