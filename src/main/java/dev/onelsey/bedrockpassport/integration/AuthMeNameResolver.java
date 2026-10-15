package dev.onelsey.bedrockpassport.integration;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

public final class AuthMeNameResolver {
    private final Plugin authMePlugin;
    private final Logger logger;
    private final Method apiGetInstance;
    private final Method apiIsRegistered;
    private final Method apiGetPlayerInfo;
    private final Method apiGetRegisteredRealNames;
    private final Method authMePlayerGetName;

    public AuthMeNameResolver(Plugin authMePlugin, Logger logger) {
        this.authMePlugin = authMePlugin;
        this.logger = logger;

        Method nextGetInstance = null;
        Method nextIsRegistered = null;
        Method nextGetPlayerInfo = null;
        Method nextGetRegisteredRealNames = null;
        Method nextGetName = null;

        if (authMePlugin != null && authMePlugin.isEnabled()) {
            try {
                ClassLoader loader = authMePlugin.getClass().getClassLoader();
                Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi", true, loader);
                Class<?> playerClass = Class.forName("fr.xephi.authme.api.v3.AuthMePlayer", true, loader);
                nextGetInstance = apiClass.getMethod("getInstance");
                nextIsRegistered = apiClass.getMethod("isRegistered", String.class);
                nextGetPlayerInfo = apiClass.getMethod("getPlayerInfo", String.class);
                nextGetRegisteredRealNames = apiClass.getMethod("getRegisteredRealNames");
                nextGetName = playerClass.getMethod("getName");
            } catch (ReflectiveOperationException exception) {
                logger.warning("BedrockPassport detected AuthMe but could not initialize canonical-name integration: " + exception.getMessage());
            }
        }

        this.apiGetInstance = nextGetInstance;
        this.apiIsRegistered = nextIsRegistered;
        this.apiGetPlayerInfo = nextGetPlayerInfo;
        this.apiGetRegisteredRealNames = nextGetRegisteredRealNames;
        this.authMePlayerGetName = nextGetName;
    }

    public boolean available() {
        return authMePlugin != null
                && authMePlugin.isEnabled()
                && apiGetInstance != null
                && apiIsRegistered != null
                && apiGetPlayerInfo != null
                && apiGetRegisteredRealNames != null
                && authMePlayerGetName != null;
    }

    public Optional<String> registeredCanonicalName(String requestedName) {
        if (!available() || requestedName == null || requestedName.isBlank()) {
            return Optional.empty();
        }

        try {
            Object api = apiGetInstance.invoke(null);
            if (api == null) {
                return Optional.empty();
            }

            boolean registered = (boolean) apiIsRegistered.invoke(api, requestedName);
            if (!registered) {
                return Optional.empty();
            }

            String lookupName = requestedName.toLowerCase(Locale.ROOT);
            Object optionalValue = apiGetPlayerInfo.invoke(api, lookupName);
            if (optionalValue instanceof Optional<?> optional && optional.isPresent()) {
                Object player = optional.get();
                Object name = authMePlayerGetName.invoke(player);
                if (name instanceof String realName
                        && !realName.isBlank()
                        && realName.equalsIgnoreCase(requestedName)) {
                    return Optional.of(realName);
                }
            }

            Object realNamesValue = apiGetRegisteredRealNames.invoke(api);
            if (realNamesValue instanceof List<?> realNames) {
                for (Object value : realNames) {
                    if (value instanceof String realName
                            && realName.equalsIgnoreCase(requestedName)) {
                        return Optional.of(realName);
                    }
                }
            }

            logger.warning("AuthMe reports " + requestedName + " as registered but BedrockPassport could not resolve its canonical letter case.");
            return Optional.empty();
        } catch (ReflectiveOperationException exception) {
            logger.warning("BedrockPassport could not query AuthMe canonical username casing: " + exception.getMessage());
            return Optional.empty();
        }
    }
}
