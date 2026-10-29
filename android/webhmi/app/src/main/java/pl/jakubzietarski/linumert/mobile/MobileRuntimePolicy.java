/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

/** Deterministic backend selection; never treat a runtime probe as a CNC backend. */
public final class MobileRuntimePolicy {
    public enum Backend { CHAQUOPY, PROOT, REMOTE, UNAVAILABLE }
    public enum Purpose { SIMULATION, REAL_MACHINE }

    private MobileRuntimePolicy() {}

    /**
     * A backend is "ready" only after its actual WebHMI health endpoint passed.
     * Importing Python, launching PRoot or seeing an open port is NOT readiness.
     * Physical machine control must never silently fall back to local simulation.
     */
    public static Backend select(Purpose purpose, boolean preferLocal,
                                 boolean chaquopyWebHmiReady,
                                 boolean prootWebHmiReady,
                                 boolean remoteConfigured) {
        if (purpose == Purpose.REAL_MACHINE) {
            return remoteConfigured ? Backend.REMOTE : Backend.UNAVAILABLE;
        }
        if (preferLocal) {
            if (chaquopyWebHmiReady) return Backend.CHAQUOPY;
            if (prootWebHmiReady) return Backend.PROOT;
        }
        if (remoteConfigured) return Backend.REMOTE;
        return Backend.UNAVAILABLE;
    }

    public static boolean canUseLocalHealth(int httpStatus, boolean ok,
                                            int schema, String taskState) {
        return httpStatus == 200 && ok && schema == 1
                && taskState != null && !taskState.isEmpty();
    }
}
