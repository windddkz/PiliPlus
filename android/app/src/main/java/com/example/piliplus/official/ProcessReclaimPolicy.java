package com.example.piliplus.official;

import android.app.ApplicationExitInfo;

final class ProcessReclaimPolicy {
    static boolean canRecover(
            boolean unexpectedPlaybackExit, int reason, int status, String description) {
        if (!unexpectedPlaybackExit)
            return false;
        return reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || (reason == ApplicationExitInfo.REASON_OTHER && status == 0 && description != null
                        && description.startsWith("kill_bg_proc"));
    }
}
