package org.ds.tuifileman.app;

@FunctionalInterface
public interface Command {
    void execute(App app);
}
