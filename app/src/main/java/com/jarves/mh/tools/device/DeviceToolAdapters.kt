package com.jarves.mh.tools.device

interface ClipboardAdapter {
    fun copy(text: String)
    fun read(): String
}

interface ToastAdapter {
    fun show(text: String): Boolean
}

interface NotificationAdapter {
    fun notify(title: String, text: String): DeviceNotificationDispatch
}

interface HttpsLinkAdapter {
    fun open(url: String)
}
