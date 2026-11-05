package dev.optimus.lyricslistener

/** WindowManager assigns a root's parent before its first attachment/layout traversal. */
internal object OverlayWindowLifecycle {
    fun isManaged(isAttachedToWindow: Boolean, hasWindowParent: Boolean): Boolean =
        isAttachedToWindow || hasWindowParent

    data class Selection<T>(
        val survivor: T?,
        val duplicates: List<T>,
        val detached: List<T>
    )

    /** Keep pending-attachment roots eligible for reuse and explicit duplicate removal. */
    fun <T : Any> select(
        views: List<T>,
        preferredView: T?,
        isManaged: (T) -> Boolean
    ): Selection<T> {
        val (managed, detached) = views.distinct().partition(isManaged)
        val survivor = preferredView?.takeIf { preferred -> managed.any { it === preferred } }
            ?: managed.lastOrNull()
        return Selection(
            survivor = survivor,
            duplicates = managed.filter { it !== survivor },
            detached = detached
        )
    }
}
