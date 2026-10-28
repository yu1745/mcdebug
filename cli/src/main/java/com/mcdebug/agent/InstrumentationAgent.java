package com.mcdebug.agent;

import java.lang.instrument.Instrumentation;

/**
 * Dynamically attached to a running game client by {@code mcdebug gui hotswap}.
 * It only hands the {@link Instrumentation} to the mcdebug mod through the system
 * properties map (the mod lives in another class loader); the mod does the
 * redefinition itself. JDK-only on purpose: this class is loaded by the target's
 * system class loader from a one-class jar, never with the CLI's dependencies.
 */
public final class InstrumentationAgent {
    public static final String KEY = "mcdebug.instrumentation";

    private InstrumentationAgent() {
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        System.getProperties().put(KEY, instrumentation);
    }
}
