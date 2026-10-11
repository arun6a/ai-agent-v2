package com.ai.agent.rules;

import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;

/**
 * Java helper to call RemoteInput.addResultsToIntent without Kotlin overload resolution issues.
 *
 * The platform method signature is:
 *   addResultsToIntent(Intent intent, RemoteInput[] remoteInputs, Bundle results)
 *
 * Kotlin has trouble resolving this overload when the array is a platform type
 * (Array<RemoteInput!>). Java's overload resolution is simpler, so this helper
 * provides a single unambiguous entry point.
 *
 * Requires API 26+ (RemoteInput.addResultsToIntent was added in API 26).
 * Caller should check Build.VERSION.SDK_INT before calling.
 */
public final class NotificationReplyHelper {

    private NotificationReplyHelper() {
        // No instances
    }

    /**
     * Attach reply text results to an Intent so that PendingIntent.send() delivers them
     * to the notification's reply action target.
     *
     * @param intent          The intent to attach results to (will be passed to PendingIntent.send)
     * @param remoteInputs    The RemoteInput array from Notification.Action.getRemoteInputs()
     * @param results         Bundle mapping resultKey → reply text
     */
    public static void addResultsToIntent(RemoteInput[] remoteInputs, Intent intent, Bundle results) {
        RemoteInput.addResultsToIntent(remoteInputs, intent, results);
    }
}
