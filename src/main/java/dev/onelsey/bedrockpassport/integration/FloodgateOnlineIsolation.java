package dev.onelsey.bedrockpassport.integration;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FloodgateOnlineIsolation implements AutoCloseable {
    private final Plugin floodgatePlugin;
    private final Logger logger;
    private Object injector;
    private Method injectMethod;
    private boolean restoreOnClose;
    private boolean isolated;

    public FloodgateOnlineIsolation(Plugin floodgatePlugin, Logger logger) throws ReflectiveOperationException {
        this.floodgatePlugin = floodgatePlugin;
        this.logger = Objects.requireNonNull(logger, "logger");

        if (floodgatePlugin == null || !floodgatePlugin.isEnabled()) {
            logger.info("BedrockPassport online mode: Floodgate is not active; packet isolation is not required.");
            return;
        }

        Object platform = readField(floodgatePlugin, "platform");
        if (platform == null) {
            throw new IllegalStateException("Floodgate platform is not initialized");
        }

        this.injector = readField(platform, "injector");
        if (injector == null) {
            throw new IllegalStateException("Floodgate platform injector is unavailable");
        }

        Method canRemoveInjection = injector.getClass().getMethod("canRemoveInjection");
        boolean removable = (boolean) canRemoveInjection.invoke(injector);
        if (!removable) {
            throw new IllegalStateException(
                    "Floodgate packet injection cannot be isolated on this platform; JAVA_ACCOUNT mode requires removable injection"
            );
        }

        Method removeInjection = injector.getClass().getMethod("removeInjection");
        this.injectMethod = injector.getClass().getMethod("inject");

        boolean wasInjected = readInjectedState(injector);
        removeInjection.invoke(injector);
        this.restoreOnClose = wasInjected;
        this.isolated = true;

        logger.info(
                "BedrockPassport online mode: Floodgate remains installed, but its server packet injector was suspended " +
                "for verified Java-account logins."
        );
    }

    public boolean isolated() {
        return isolated;
    }

    private static boolean readInjectedState(Object injector) {
        try {
            Method method = injector.getClass().getMethod("isInjected");
            return (boolean) method.invoke(injector);
        } catch (ReflectiveOperationException ignored) {
            return true;
        }
    }

    private static Object readField(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "#" + fieldName);
    }

    @Override
    public void close() {
        if (!isolated) {
            return;
        }
        isolated = false;

        if (!restoreOnClose || floodgatePlugin == null || !floodgatePlugin.isEnabled()) {
            return;
        }

        try {
            injectMethod.invoke(injector);
            logger.info("BedrockPassport restored Floodgate server packet injection.");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logger.log(Level.SEVERE, "BedrockPassport could not restore Floodgate packet injection.", cause);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "BedrockPassport could not restore Floodgate packet injection.", throwable);
        }
    }
}
