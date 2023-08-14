package se.sundsvall.citizenassets.api.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import se.sundsvall.dept44.common.validators.annotation.ValidUuid;

@Getter
@Builder(setterPrefix = "with")
@AllArgsConstructor
public class AssetCreateRequest {

	@ValidUuid
	@Schema(description = "PartyId", example = "123e4567-e89b-12d3-a456-426614174000", requiredMode = Schema.RequiredMode.REQUIRED)
	private String partyId;

	@Schema(description = "Asset id", example = "PRH-123456789")
	private String assetId;

	@Schema(description = "Case reference ids", example = "[\"123e4567-e89b-12d3-a456-426614174000\"]")
	private List<String> caseReferenceIds;

	@Schema(description = "Asset type", example = "PERMIT")
	private String type;

	@Schema(description = "Issued date", example = "2021-01-01")
	private LocalDate issued;

	@Schema(description = "Valid to date", example = "2021-12-31")
	private LocalDate validTo;

	@Schema(description = "Asset status", example = "ACTIVE")
	private Status status;

	@Schema(description = "Asset description", example = "Asset description")
	private String description;

	@Schema(description = "Additional parameters", example = "{\"foo\":\"bar\"}")
	private Map<String, String> additionalParameters;
}
