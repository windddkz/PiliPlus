package com.example.piliplus.official;

public final class ProcessReclaimPolicyTest {
    public static void main(String[] args) {
        if (!ProcessReclaimPolicy.canRecover(true, 3, 0, null)) throw new AssertionError("low memory");
        if (!ProcessReclaimPolicy.canRecover(true, 13, 0, "kill_bg_proc")) throw new AssertionError("system background reclaim");
        for (int reason : new int[]{0, 1, 2, 4, 5, 6, 9, 10, 11, 12, 14, 15}) {
            if (ProcessReclaimPolicy.canRecover(true, reason, 0, "kill_bg_proc")) throw new AssertionError("unsafe reason " + reason);
        }
        if (ProcessReclaimPolicy.canRecover(false, 3, 0, null)) throw new AssertionError("intentional exit");
        if (ProcessReclaimPolicy.canRecover(true, 13, 9, "kill_bg_proc")) throw new AssertionError("unexpected status");
        if (ProcessReclaimPolicy.canRecover(true, 13, 0, "other reason")) throw new AssertionError("unknown system kill");
        if (ProcessReclaimPolicy.canRecover(true, 13, 0, null)) throw new AssertionError("missing description");
        System.out.println("Process reclaim policy assertions passed");
    }
}
