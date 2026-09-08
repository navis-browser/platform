/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.engine

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle

/** Prepares public Android launch bounds only; the Runtime must verify the actual resulting task.
 * Device freeform support is necessary, not proof that its current display honors launch bounds.
 */
internal object AndroidWindowLaunchGeometry {
    private const val MAX_POSITION = 1_000_000L
    private const val MAX_SIZE = 32_768L
    private const val MIN_SIZE = 100L

    /** Neither a caller nor ActivityOptions can mutate the requested verification rectangle. */
    internal data class ExistingTask(val id: Int, val component: ComponentName, val packageName: String)

    internal class Prepared internal constructor(bounds: Rect, internal val existingTask: ExistingTask? = null) {
        private val requested = Rect(bounds)
        val bounds: Rect get() = Rect(requested)
        val options: Bundle get() = ActivityOptions.makeBasic().setLaunchBounds(Rect(requested)).toBundle()
    }

    fun prepare(
        activity: Activity,
        left: Int?,
        top: Int?,
        width: Int?,
        height: Int?,
    ): Prepared? {
        if (left == null && top == null && width == null && height == null) return null
        check(!activity.isFinishing && !activity.isDestroyed) { "The source Navis window is unavailable" }
        check(activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
            "Freeform window placement is unavailable on this Android device"
        }
        val source = if (left == null || top == null || width == null || height == null) sourceBounds(activity) else null
        val x = left?.toLong() ?: checkNotNull(source).left.toLong()
        val y = top?.toLong() ?: checkNotNull(source).top.toLong()
        val w = (width?.toLong() ?: checkNotNull(source).let { it.right.toLong() - it.left }).coerceAtLeast(MIN_SIZE)
        val h = (height?.toLong() ?: checkNotNull(source).let { it.bottom.toLong() - it.top }).coerceAtLeast(MIN_SIZE)
        require(x in -MAX_POSITION..MAX_POSITION && y in -MAX_POSITION..MAX_POSITION) {
            "Requested window position exceeds the supported coordinate range"
        }
        require(w <= MAX_SIZE && h <= MAX_SIZE) { "Requested window size exceeds the supported range" }
        return Prepared(checkedRect(x, y, w, h))
    }

    /** Normalize through the same policy as CREATE, and bind UPDATE to one existing task. */
    fun prepareUpdate(
        activity: Activity,
        left: Int?,
        top: Int?,
        width: Int?,
        height: Int?,
    ): Prepared? {
        val normalized = prepare(activity, left, top, width, height) ?: return null
        requireExistingMode(activity)
        val identity = ExistingTask(activity.taskId, activity.componentName, activity.packageName)
        currentTaskManager(activity, identity)
        return Prepared(normalized.bounds, identity)
    }

    /** Issuing the public operation is not a bounds acknowledgement; Runtime observes that. */
    fun moveExisting(activity: Activity, prepared: Prepared) {
        val identity = checkNotNull(prepared.existingTask) { "Bounds were not prepared for an existing task" }
        requireExistingMode(activity)
        val manager = currentTaskManager(activity, identity)
        manager.moveTaskToFront(identity.id, 0, prepared.options)
    }

    private fun requireExistingMode(activity: Activity) {
        check(!activity.isFinishing && !activity.isDestroyed) { "The Navis window is unavailable" }
        check(activity.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) &&
            activity.isInMultiWindowMode && !activity.isInPictureInPictureMode) {
            "Existing window placement requires a freeform-capable multi-window task outside picture-in-picture"
        }
        // isInMultiWindowMode cannot publicly distinguish split screen from freeform.
        // Nor is TaskInfo.isResizeable a public SDK field. Never infer success here.
    }

    private fun currentTaskManager(activity: Activity, identity: ExistingTask): ActivityManager {
        check(identity.id >= 0 && activity.taskId == identity.id && activity.componentName == identity.component &&
            activity.packageName == identity.packageName && identity.component.packageName == identity.packageName) {
            "The prepared task no longer belongs to this Navis Activity"
        }
        val manager = checkNotNull(activity.getSystemService(ActivityManager::class.java)) { "ActivityManager is unavailable" }
        val info = checkNotNull(manager.appTasks.mapNotNull { it.taskInfo }.singleOrNull { taskId(it) == identity.id }) {
            "The exact Navis task is no longer available"
        }
        check(info.topActivity == identity.component && info.baseActivity?.packageName == identity.packageName &&
            (Build.VERSION.SDK_INT < 29 || info.isRunning)) {
            "The task no longer presents the original Navis Activity"
        }
        return manager
    }

    @Suppress("DEPRECATION")
    private fun taskId(info: ActivityManager.RecentTaskInfo): Int =
        if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id

    private fun sourceBounds(activity: Activity): Rect {
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            Rect(activity.windowManager.currentWindowMetrics.bounds)
        } else {
            val decor = activity.window.decorView
            val position = IntArray(2)
            decor.getLocationOnScreen(position)
            checkedRect(position[0].toLong(), position[1].toLong(), decor.width.toLong(), decor.height.toLong())
        }
        check(bounds.right.toLong() > bounds.left.toLong() && bounds.bottom.toLong() > bounds.top.toLong()) {
            "The source window has no measured outer bounds"
        }
        return bounds
    }

    private fun checkedRect(left: Long, top: Long, width: Long, height: Long): Rect {
        val right = left + width
        val bottom = top + height
        require(right in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            bottom in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Window bounds overflow screen coordinates" }
        return Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }
}
