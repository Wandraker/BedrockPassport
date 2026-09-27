package dev.onelsey.bedrockpassport.data;

import java.util.UUID;

public record Identity(String xuid, String gameName, UUID floodgateUuid) {
}
