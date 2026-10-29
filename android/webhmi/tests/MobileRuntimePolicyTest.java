/* SPDX-License-Identifier: GPL-2.0-or-later */
package pl.jakubzietarski.linumert.mobile;

/** Plain javac/java tests, intentionally independent of Android SDK. */
public final class MobileRuntimePolicyTest {
    private static int assertions;
    private static void eq(Object actual, Object expected) {
        if (!expected.equals(actual))
            throw new AssertionError("expected " + expected + ", got " + actual);
        assertions++;
    }
    private static void yes(boolean result) {
        if (!result) throw new AssertionError("expected true");
        assertions++;
    }
    private static void no(boolean result) {
        if (result) throw new AssertionError("expected false");
        assertions++;
    }

    public static void main(String[] args) {
        var simulation = MobileRuntimePolicy.Purpose.SIMULATION;
        var machine = MobileRuntimePolicy.Purpose.REAL_MACHINE;
        var c = MobileRuntimePolicy.Backend.CHAQUOPY;
        var p = MobileRuntimePolicy.Backend.PROOT;
        var r = MobileRuntimePolicy.Backend.REMOTE;
        var u = MobileRuntimePolicy.Backend.UNAVAILABLE;
        eq(MobileRuntimePolicy.select(simulation,true,true,true,true), c);
        eq(MobileRuntimePolicy.select(simulation,true,false,true,true), p);
        eq(MobileRuntimePolicy.select(simulation,true,false,false,true), r);
        eq(MobileRuntimePolicy.select(simulation,true,false,false,false), u);
        eq(MobileRuntimePolicy.select(simulation,false,true,true,true), r);
        eq(MobileRuntimePolicy.select(simulation,false,true,true,false), u);
        eq(MobileRuntimePolicy.select(machine,true,true,true,false), u);
        eq(MobileRuntimePolicy.select(machine,true,true,true,true), r);
        yes(MobileRuntimePolicy.canUseLocalHealth(200,true,1,"on"));
        yes(MobileRuntimePolicy.canUseLocalHealth(200,true,1,"estop"));
        no(MobileRuntimePolicy.canUseLocalHealth(200,true,0,"probe"));
        no(MobileRuntimePolicy.canUseLocalHealth(200,true,1,null));
        no(MobileRuntimePolicy.canUseLocalHealth(200,true,1,""));
        no(MobileRuntimePolicy.canUseLocalHealth(500,true,1,"on"));
        no(MobileRuntimePolicy.canUseLocalHealth(200,false,1,"on"));
        System.out.println("PASS: " + assertions + " runtime policy assertions");
    }
}
