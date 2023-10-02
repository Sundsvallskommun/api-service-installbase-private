package se.sundsvall.partyassets.integration.party.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("integration.party")
public record PartyProperties(int connectTimeout, int readTimeout) {}
