package se.sundsvall.partyassets.pr3import;

import static generated.se.sundsvall.party.PartyType.PRIVATE;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;

import se.sundsvall.partyassets.Application;
import se.sundsvall.partyassets.integration.db.AssetRepository;
import se.sundsvall.partyassets.integration.db.model.AssetEntity;
import se.sundsvall.partyassets.integration.party.PartyClient;

@ActiveProfiles("junit")
@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)
class PR3ImporterTest {

    @MockBean
    private AssetRepository mockAssetRepository;
    @MockBean
    private PartyClient mockPartyClient;

    @Value("classpath:test.xlsx")
    private Resource importFileResource;

    @Autowired
    private PR3Importer importer;

    @Test
    void importFromExcel() throws IOException {
        when(mockPartyClient.getPartyId(eq(PRIVATE), anyString()))
            .thenReturn(of(UUID.randomUUID().toString()));

        var result = importer.importFromExcel(importFileResource.getInputStream());

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getSuccessful()).isEqualTo(2);
        assertThat(result.getFailed()).isOne();

        verify(mockPartyClient, times(2)).getPartyId(eq(PRIVATE), any(String.class));
        verifyNoMoreInteractions(mockPartyClient);
        verify(mockAssetRepository, times(2)).existsByAssetId(any(String.class));
        verify(mockAssetRepository, times(2)).save(any(AssetEntity.class));
        verifyNoMoreInteractions(mockAssetRepository);
    }

    @Test
    void addCenturyDigitToLegalId() {
        assertThat(importer.addCenturyDigitToLegalId("")).isNull();
        assertThat(importer.addCenturyDigitToLegalId("not-a-legal-id")).isNull();
        assertThat(importer.addCenturyDigitToLegalId("196505018585")).isEqualTo("196505018585");
        assertThat(importer.addCenturyDigitToLegalId("200301021456")).isEqualTo("200301021456");
        assertThat(importer.addCenturyDigitToLegalId("6505018585")).isEqualTo("196505018585");
        assertThat(importer.addCenturyDigitToLegalId("0301021456")).isEqualTo("200301021456");
    }
}
