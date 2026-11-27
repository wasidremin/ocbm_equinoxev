package wasidremin.gmccpa.av

/**
 * Android's touch events as CarPlay's fingers: which finger, which phase, where. Pinch-to-zoom (owner,
 * 2026-11-26: "lets check on the pinch to zoom"). The adapter's document always declares Apple's two-finger
 * touchscreen (`VehicleConfigYaml.renderAdapter`, `touchScreenSupportsMultiTouch: true`) and `carplayd`
 * tracks two contacts by the finger number each `INPUT_TOUCH` carries, but until this the app sent only
 * its first pointer, as finger 0, so a pinch reached the phone as a one-finger drag.
 *
 * A finger keeps its number (0 or 1) from its down to its up; a pointer that lands while both are held
 * is not sent at all (Apple's descriptor has two contacts, and `carplayd` would drop it too). Pure: the
 * Activity feeds it the event's action and pointers in view pixels and sends what comes back.
 */
class TouchFingers {
    /** One pointer as the event has it: Android's pointer id and its position in view pixels. */
    data class Pointer(val id: Int, val x: Float, val y: Float)

    /** One report to send: [phase] is [DOWN], [MOVE], [UP] or [CANCEL]. */
    data class Touch(val finger: Int, val phase: Int, val x: Float, val y: Float)

    /** Android pointer id to finger number, for the pointers being sent. */
    private val fingers = LinkedHashMap<Int, Int>()

    val down: Int get() = fingers.size

    /**
     * Pure apart from this tracker's own state: [action] is `MotionEvent.getActionMasked()`, [actionId]
     * the pointer id at the action index, [pointers] every pointer in the event.
     */
    fun on(action: Int, actionId: Int, pointers: List<Pointer>): List<Touch> {
        fun at(id: Int) = pointers.firstOrNull { it.id == id }
        return when (action) {
            ACTION_DOWN -> {
                fingers.clear()
                val p = at(actionId) ?: return emptyList()
                fingers[actionId] = 0
                listOf(Touch(0, DOWN, p.x, p.y))
            }
            ACTION_POINTER_DOWN -> {
                if (fingers.size >= MAX_FINGERS || actionId in fingers) return emptyList()
                val p = at(actionId) ?: return emptyList()
                val f = (0 until MAX_FINGERS).first { it !in fingers.values }
                fingers[actionId] = f
                listOf(Touch(f, DOWN, p.x, p.y))
            }
            ACTION_MOVE -> fingers.mapNotNull { (id, f) -> at(id)?.let { Touch(f, MOVE, it.x, it.y) } }
            ACTION_POINTER_UP -> {
                val f = fingers.remove(actionId) ?: return emptyList()
                val p = at(actionId) ?: return emptyList()
                listOf(Touch(f, UP, p.x, p.y))
            }
            ACTION_UP -> {
                // The last pointer up. Any other still held (a missed POINTER_UP) is lifted with it.
                val out = fingers.mapNotNull { (id, f) -> at(id)?.let { Touch(f, UP, it.x, it.y) } }
                fingers.clear()
                out
            }
            ACTION_CANCEL -> {
                val out = fingers.mapNotNull { (id, f) -> at(id)?.let { Touch(f, CANCEL, it.x, it.y) } }
                fingers.clear()
                out
            }
            else -> emptyList()
        }
    }

    companion object {
        /** Apple's two-finger touchscreen descriptor (`HIDTouchScreenMultiCreateDescriptor`). */
        const val MAX_FINGERS = 2
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        /** Not on the wire: the Activity turns it into a displaced lift (see `CarPlayActivity.onTouch`). */
        const val CANCEL = 3
        // MotionEvent's masked actions, so this stays testable on the JVM.
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
    }
}
