package com.example.dosewear.presentation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.dosewear.data.Prefs
import com.example.dosewear.data.Reminder
import com.example.dosewear.data.ReminderItem
import com.example.dosewear.data.ReminderWithItems
import com.example.dosewear.data.Supplement

/**
 * Duzenleme ekranlarinin gecici durumu.
 * Saatte ekranlar arasi gidip gelirken (ornegin takviye secimi) girilenlerin
 * kaybolmamasi icin tek bir process-scoped taslak tutuyoruz.
 */

data class DraftItem(val supplementId: Long, var amount: Double)

object ReminderDraft {
    var id by mutableStateOf(0L)
    var label by mutableStateOf("")
    var hour by mutableStateOf(9)
    var minute by mutableStateOf(0)
    var daysMask by mutableStateOf(Reminder.ALL_DAYS)
    var enabled by mutableStateOf(true)
    var snoozeMinutes by mutableStateOf(10)
    var jitterMinutes by mutableStateOf(3)
    var maxSnoozes by mutableStateOf(3)
    val items = mutableStateListOf<DraftItem>()

    private var loadedFor: Long = -1L

    /** true iken "yeni hatirlatici" ekrani taslagi sifirlamaz (kopyalama akisi). */
    var pendingCopy: Boolean = false

    fun startNew(prefs: Prefs) {
        id = 0L
        label = ""
        hour = 9
        minute = 0
        daysMask = Reminder.ALL_DAYS
        enabled = true
        snoozeMinutes = prefs.defaultSnoozeMinutes
        jitterMinutes = prefs.defaultJitterMinutes
        maxSnoozes = prefs.defaultMaxSnoozes
        items.clear()
        loadedFor = 0L
    }

    /** Ayni hatirlatici icin ikinci kez yuklemeyi engeller (kullanicinin girdisi silinmesin). */
    fun loadOnce(rwi: ReminderWithItems) {
        if (loadedFor == rwi.reminder.id) return
        val r = rwi.reminder
        id = r.id
        label = r.label
        hour = r.hour
        minute = r.minute
        daysMask = r.daysMask
        enabled = r.enabled
        snoozeMinutes = r.snoozeMinutes
        jitterMinutes = r.snoozeJitterMinutes
        maxSnoozes = r.maxSnoozes
        items.clear()
        rwi.items.forEach { items.add(DraftItem(it.item.supplementId, it.item.amount)) }
        loadedFor = r.id
    }

    fun invalidate() {
        loadedFor = -1L
    }

    /**
     * Mevcut taslagi yeni bir hatirlatici olarak isaretler: saat, gunler, ilaclar
     * ve erteleme ayarlari aynen kalir, sadece kimlik dusurulur.
     */
    fun prepareCopy(labelSuffix: String) {
        id = 0L
        if (label.isNotBlank()) label = "$label $labelSuffix"
        loadedFor = 0L
        pendingCopy = true
    }

    fun toggle(supplementId: Long) {
        val idx = items.indexOfFirst { it.supplementId == supplementId }
        if (idx >= 0) items.removeAt(idx) else items.add(DraftItem(supplementId, 1.0))
    }

    fun amountOf(supplementId: Long): Double =
        items.firstOrNull { it.supplementId == supplementId }?.amount ?: 0.0

    fun setAmount(supplementId: Long, amount: Double) {
        val idx = items.indexOfFirst { it.supplementId == supplementId }
        if (idx >= 0) items[idx] = items[idx].copy(amount = amount.coerceIn(0.5, 20.0))
    }

    fun isSelected(supplementId: Long) = items.any { it.supplementId == supplementId }

    fun toReminder(): Reminder = Reminder(
        id = id,
        label = label,
        hour = hour,
        minute = minute,
        daysMask = daysMask,
        enabled = enabled,
        snoozeMinutes = snoozeMinutes,
        snoozeJitterMinutes = jitterMinutes,
        maxSnoozes = maxSnoozes
    )

    fun toItems(): List<ReminderItem> =
        items.map { ReminderItem(reminderId = id, supplementId = it.supplementId, amount = it.amount) }
}

object SupplementDraft {
    var id by mutableStateOf(0L)
    var name by mutableStateOf("")
    var strength by mutableStateOf("")
    var unit by mutableStateOf("pcs")
    var stock by mutableStateOf(30.0)
    var threshold by mutableStateOf(5.0)
    var refill by mutableStateOf(30.0)
    var colorIndex by mutableStateOf(0)
    var active by mutableStateOf(true)

    private var loadedFor: Long = -1L

    fun startNew() {
        id = 0L
        name = ""
        strength = ""
        unit = "pcs"
        stock = 30.0
        threshold = 5.0
        refill = 30.0
        colorIndex = (0..7).random()
        active = true
        loadedFor = 0L
    }

    fun loadOnce(s: Supplement) {
        if (loadedFor == s.id) return
        id = s.id
        name = s.name
        strength = s.strength
        unit = s.unit
        stock = s.stock
        threshold = s.lowStockThreshold
        refill = s.refillAmount
        colorIndex = s.colorIndex
        active = s.active
        loadedFor = s.id
    }

    fun invalidate() {
        loadedFor = -1L
    }

    fun toSupplement(existing: Supplement? = null, fallbackName: String = "?"): Supplement = Supplement(
        id = id,
        name = name.ifBlank { fallbackName },
        strength = strength,
        unit = unit,
        stock = stock,
        lowStockThreshold = threshold,
        refillAmount = refill,
        colorIndex = colorIndex,
        active = active,
        lowStockAlerted = existing?.lowStockAlerted ?: false,
        createdAt = existing?.createdAt ?: System.currentTimeMillis()
    )
}
