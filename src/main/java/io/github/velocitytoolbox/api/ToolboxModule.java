package io.github.velocitytoolbox.api;

/**
 * A reloadable module hosted by VelocityToolbox.
 *
 * <p>Implementations are discovered from the module JAR through
 * META-INF/services/io.github.velocitytoolbox.api.ToolboxModule.</p>
 */
public interface ToolboxModule {

    String id();

    void enable(ToolboxContext context) throws Exception;

    void disable() throws Exception;
}
